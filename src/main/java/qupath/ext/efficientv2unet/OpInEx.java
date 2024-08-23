package qupath.ext.efficientv2unet;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import ij.IJ;
import ij.ImagePlus;
import org.locationtech.jts.geom.Geometry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.fx.dialogs.Dialogs;
import qupath.imagej.tools.PixelImageIJ;
import qupath.lib.analysis.images.ContourTracing;
import qupath.lib.analysis.images.SimpleImage;
import qupath.lib.common.ColorTools;
import qupath.lib.common.GeneralTools;
import qupath.lib.images.ImageData;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.tools.GuiTools;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.LabeledImageServer;
import qupath.lib.images.writers.ImageWriterTools;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.regions.ImagePlane;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.RoiTools;
import qupath.lib.roi.interfaces.ROI;

import static qupath.lib.roi.GeometryTools.createRectangle;
import static qupath.lib.roi.GeometryTools.geometryToROI;


/**
 * I/O operations for the EfficientV2UNet Extension
 *
 * @author Loïc Sauteur
 */
public class OpInEx {
    // Class variables
    private final static Logger logger = LoggerFactory.getLogger(OpInEx.class);
    private final QuPathGUI qupath;
    private File project_dir;
    public File training_root;
    public File images_dir; // train images
    public File masks_dir; // train masks
    public File prediction_dir; // predicted images
    public File temp_dir; // folder to put images to be predicted
    public ArrayList<File> temp_files = new ArrayList<>();

    /**
     * Just gets and sets the current project folder.
     * Constructor
     * @param qupath
     */
    public OpInEx(QuPathGUI qupath) {
        this.qupath = qupath;
        // get & set the project folder File
        project_dir = retrieve_Project_dir();
        if (project_dir == null) throw new RuntimeException("Please open a project first");
        // initialize / create output folders
        create_output_folders();
    }

    /**
     * Get the project folder path from the current project
     * @return File path to project folder
     */
    private File retrieve_Project_dir() {
        try {
            // create a temp folder
            project_dir = qupath.getProject().getPath().getParent().toFile();
        }
        catch (Exception e) {
            GuiTools.showNoProjectError("Please open a project first");

        }
    return project_dir;
    }

    /**
     * Getter for the project folder dir String
     * @return String path of project folder
     */
    public String getProject_dir() {
        return project_dir.getAbsolutePath();
    }
    /**
     * Getter for the temp_dir
     * @return String temp_dir
     */
    public String getTemp_dir() {
        return temp_dir.getAbsolutePath();
    }

    /**
     * Set the temp_dir class variable.
     * Expects the path to exist. Throws exception if not.
     * @param path: String path to folder
     */
    public void setTemp_dir(String path) {
        temp_dir = new File(path);
        if (!temp_dir.exists()) {
            throw new RuntimeException("Temporary folder does not exist: " + temp_dir.getAbsolutePath());
        }
    }

    /**
     * Set the prediction_dir class variable.
     * Expects the path to exist. Throws exception if not.
     * @param path: String path to folder
     */
    public void setPrediction_dir(String path) {
        prediction_dir = new File(path);
        if (!prediction_dir.exists()) {
            throw new RuntimeException("Prediction folder does not exist: " + prediction_dir.getAbsolutePath());
        }

    }

    /**
     * Getter for the prediction_dir
     * @return String prediction_dir
     */
    public String getPrediction_dir() {
        return prediction_dir.getAbsolutePath();
    }

    /**
     * Getter for the training image dir
     * @return String of training image dir
     */
    public String getImages_dir() {
        return images_dir.getAbsolutePath();
    }

    /**
     * Getter for the training mask dir
     * @return String of training mask dir
     */
    public String getMasks_dir() {
        return masks_dir.getAbsolutePath();
    }

    /**
     * Getter for the training root dir (aka. base_dir)
     * @return String of training root dir
     */
    public String getTraining_root() {
        return training_root.getAbsolutePath();
    }

    /**
     * Creates output folders
     * training_root = project_folder_path / Efficient_V2_UNet
     * images_dir = project_folder_path / Efficient_V2_UNet / images
     * masks_dir = project_folder_path / Efficient_V2_UNet / masks
     */
    public void create_output_folders() {
        training_root = new File(project_dir, "Efficient_V2_UNet");
        images_dir = new File(training_root, "images");
        masks_dir = new File(training_root, "masks");
        prediction_dir = new File(training_root, "prediction");
        temp_dir = new File(training_root, "temp");
        /*
        if (images_dir.exists() || masks_dir.exists()) {
             // following function is deprecated but I did not find any other fitting function in GuiTools
            Dialogs.showWarningNotification("Training folders already exist", "Training images & masks will be overwritten");
        }
        */
        try {
            images_dir.mkdirs();
            masks_dir.mkdirs();
            prediction_dir.mkdirs();
            temp_dir.mkdirs();
        }
        catch (Exception e) {
            logger.error(e.getMessage(), e);
        }

        // Delete previous data?
        boolean hasTifImages = Arrays.stream(images_dir.listFiles()).toList().stream().anyMatch(f -> f.getName().endsWith(".tif"));
        boolean hasTifMasks = Arrays.stream(masks_dir.listFiles()).toList().stream().anyMatch(f -> f.getName().endsWith(".tif"));

        if (hasTifImages || hasTifMasks) {
            if (!Dialogs.showYesNoDialog("TIF images already exist", "The output folders already contain images.\nImages may be overwritten.\nDo you want to keep the images?\nPress 'No' to delete them.")) {
                // Does not delete subfolders
                Arrays.stream(images_dir.listFiles()).toList().forEach(File::delete);
                Arrays.stream(masks_dir.listFiles()).toList().forEach(File::delete);
            }
        }
    }


    /**
     * Delete temp files (file list created when using exportTempImages())
     * @return boolean
     */
    public boolean deleteTempFiles() {
        ArrayList<File> temp_files_copy = new ArrayList<>();
        if (temp_files != null && !temp_files.isEmpty()) {
            temp_files.forEach(f -> {
                if (f.delete()) {
                    logger.info("Deleted temp file: " + f.getAbsolutePath());
                }
                else {
                    logger.warn("Could not delete temp file: " + f.getAbsolutePath());
                    temp_files_copy.add(f);
                }
            });
            temp_files = temp_files_copy;
            return temp_files.isEmpty();
        }
        return false;
    }

    /**
     * Delete the prediction files.
     * (or any list of files)
     * @param file_list = List of files that need to be deleted. If null, then all tif files in the prediction_dir will be deleted
     * @return boolean
     */
    public boolean deletePredictionFiles(List<File> file_list) {
        boolean success = true;
        // if file_list is null, populate the list with all tif files in prediction_dir
        if (file_list == null) {
            file_list = getPredictionFiles();
        }
        // don't do anything if file_list is empty
        if (file_list.isEmpty()) return true;

        // delete the files
        for (File f : file_list) {
            if (f.delete()) {
                logger.info("Deleted file: " + f.getAbsolutePath());
            }
            else {
                logger.error("Could not delete file: " + f.getAbsolutePath());
                success = false;
            }
        }
        return success;
    }

    /**
     * Get a list of all tif files in the prediction_dir
     * @return List<File>
     */
    public List<File> getPredictionFiles() {
        List<File> file_list = List.of(prediction_dir.listFiles(new FileFilter() {
            @Override
            public boolean accept(File pathname) {
                return pathname.getName().endsWith(".tif");
            }
        }));
        return file_list;
    }

    /**
     * Get a list of tif files in a folder
     * @param folder_path: String path to folder
     * @return List<File>
     */
    public List<File> getTifFilesInFolder(String folder_path) {
        File file_path = new File(folder_path);
        if (!file_path.exists()) {
            return null;
        }
        List<File> file_list = List.of(file_path.listFiles(new FileFilter() {
            @Override
            public boolean accept(File pathname) {
                return pathname.getName().endsWith(".tif");
            }
        }));
        return file_list;
    }


    /**
     * @deprecated
     * Use: load_predictions(HashMap<ProjectImageEntry<BufferedImage>, PredictionFile> map_files,
     *                       boolean doSplit, boolean doRemove, Map<Integer, String> map_anno_class)
     *
     * For all ProjectImageEntries will load the predicted tif files and add
     * the objects to the ProjectImageEntry.
     *
     * @param mapped_files = Map of ProjectImageEntry<BufferedImage> and corresponding predicted File
     * @param doSplit = boolean, whether to split the found objects
     * @param doRemove = boolean, whether to remove all existing objects
     * @param map_anno_class = Map of labels to annotation classes
     */
    @Deprecated
    public void batch_load_maskFiles(HashMap<ProjectImageEntry<BufferedImage>, File> mapped_files,
                                     boolean doSplit, boolean doRemove, Map<Integer, String> map_anno_class) {
        if (mapped_files == null || mapped_files.isEmpty()) {
            logger.error("No mask files to load");
            return;
        }
        for (Map.Entry<ProjectImageEntry<BufferedImage>, File> entry : mapped_files.entrySet()) {
            logger.debug(entry.getKey().getImageName() + " -> " + entry.getValue().getAbsolutePath());
            // Here the file from the map is actually the RGB temp image
            // need to find the corresponding predicted mask
            File mask_file = new File(getPrediction_dir(), entry.getValue().getName());
            if (!mask_file.exists()) {
                logger.error("Mask file does not exist: " + mask_file.getAbsolutePath());
            }
            else {
                load_maskFile(mask_file, entry.getKey(), doSplit, doRemove, map_anno_class);
            }
        }
    }


    /**
     * @deprecated
     * For a list of mask files, will find the matching QuPath image entry (based on image name),
     * then load the mask using the load_maskFile function.
     * @param file_list = List of mask files
     * @param image_entries = List of ProjectImageEntry
     * @param doSplit = boolean, whether to split the detected object into individual ones
     * @param doRemove = boolean, whether to remove ALL existing objects in the QuPath image entry
     * @param map_anno_class = Map<Integer, String>, map of label id (intestine in mask) mapped to
     *                         the class name that the that annotation should be added to.
     */
    @Deprecated
    public void batch_load_maskFiles(List<File> file_list, List<ProjectImageEntry<BufferedImage>> image_entries,
                                     boolean doSplit, boolean doRemove, Map<Integer, String> map_anno_class) {
        logger.debug("@Deprecated batch_load_maskFiles function");
        if (file_list == null || file_list.isEmpty()) {
            logger.error("No mask files to load");
        }
        for (File f : file_list) {
            List<ProjectImageEntry<BufferedImage>> found_entry = image_entries.stream().filter(ie -> ie.getImageName().contains(GeneralTools.stripExtension(f.getName()))).collect(Collectors.toList());
            if (found_entry.isEmpty()) {
                logger.error("Could not find image entry for mask file - MASK NOT LOADED: " + f.getAbsolutePath());
            }
            else if (found_entry.size() > 1) {
                logger.error("Found more than one image entry for mask file - MASK NOT LOADED:: " + f.getAbsolutePath());
            }
            else {
                load_maskFile(f, found_entry.get(0), doSplit, doRemove, map_anno_class);
            }
        }
    }


    /**
     * For batching prediction mask loading, using a map of ProjectImageEntry and PredictionFile
     * @param map_files = HashMap<ProjectImageEntry<BufferedImage>, PredictionFile>
     * @param doSplit = boolean for splitting the found objects
     * @param doRemove = boolean for removing all existing objects
     * @param map_anno_class = Map<Integer, String>, map of label id (intensity in mask) mapped to a PathClass name
     */
    public void load_predictions(HashMap<ProjectImageEntry<BufferedImage>, PredictionFile> map_files,
                                 boolean doSplit, boolean doRemove, Map<Integer, String> map_anno_class) {
        if (map_files == null || map_files.isEmpty()) {
            logger.error("No mask files to load");
            return;
        }
        for (Map.Entry<ProjectImageEntry<BufferedImage>, PredictionFile> entry : map_files.entrySet()) {
            File prediction_file = entry.getValue().getPredictedFile();
            if (!prediction_file.exists()) {
                logger.error("Mask file does not exist: " + prediction_file.getAbsolutePath());
            }
            else {
                load_pred(entry.getValue(), entry.getKey(), doSplit, doRemove, map_anno_class);
            }
        }
    }

    /**
     * Load a prediction file matched to a ProjectImageEntry.
     * Optionally, splitting the found objects.
     * Optionally, removing all existing objects in the ProjectImageEntry.
     * The map provides matching PathClass names to the label ID (for future multiclass segmentation)
     * @param pred_file = PredictionFile, with RegionRequest and link to the prediction mask file
     * @param imageEntry = ProjectImageEntry
     * @param doSplit = boolean for splitting the found objects
     * @param doRemove = boolean for removing all existing objects
     * @param map_anno_class = Map<Integer, String>, map of label id (intensity in mask) mapped to a PathClass name
     */
    public void load_pred(PredictionFile pred_file, ProjectImageEntry<BufferedImage> imageEntry,
                          boolean doSplit, boolean doRemove, Map<Integer, String> map_anno_class) {
        // Sanity test
        if (!qupath.getProject().getImageList().contains(imageEntry)) {
            logger.error("Project does not contain image: " + imageEntry.getImageName());
            throw new RuntimeException("Project does not contain image: " + imageEntry.getImageName());
        }
        if (!pred_file.getPredictedFile().exists()) {
            logger.error("Mask file does not exist: " + pred_file.getPredictedFile().getAbsolutePath());
            throw new RuntimeException("Mask file does not exist: " + pred_file.getPredictedFile().getAbsolutePath());
        }

        // Quick load the mask
        List<PathObject> anno_list;
        try {
            // Each label will be an object in the list
            anno_list = ContourTracing.labelsToAnnotations(pred_file.getPredictedFile().toPath(), pred_file.getRequest());
        } catch (IOException e) {
            logger.error("Failed to load predicted mask: " + pred_file.getPredictedFile().getAbsolutePath());
            throw new RuntimeException(e);
        }

        if (anno_list.isEmpty()) {
            logger.warn("No objects were found in the predicted image.");
        }

        // Load the ImageData to add the annotations
        ImageData<BufferedImage> image_data;
        try {
            image_data = imageEntry.readImageData();
        } catch (IOException e) {
            logger.error("Failed to load image: " + imageEntry.getImageName());
            throw new RuntimeException(e);
        }

        // remove existing annotations from the image
        if (doRemove) {
            image_data.getHierarchy().clearAll();
            logger.debug("Removed all objects from the image " + imageEntry.getImageName());
        }

        // Add the annotations
        for (int i = 0; i < anno_list.size(); i++) {
            // Check that the class map contains a string for the given label
            String pClassName = map_anno_class.get(i + 1);
            if (pClassName == null) {
                logger.warn("Found label value {} in prediction image but no associated PathClass classification was given. Annotation for this label will have no classification.", i+1);
            }
            PathClass pClass = PathClass.getInstance(map_anno_class.get(i + 1));
            if (doSplit) {
                logger.debug("Splitting the annotation into individual ROIs");
                // Split the ROI into individual ROIs
                List<ROI> split_ROIs = RoiTools.splitROI(anno_list.get(i).getROI());
                // Convert the ROIs to PathObjects
                List<PathObject> split_annotations = new ArrayList<>();
                split_ROIs.forEach(r -> split_annotations.add(PathObjects.createAnnotationObject(r, pClass)));
                // Add the PathObjects
                image_data.getHierarchy().addObjects(split_annotations);
            }
            else {
                image_data.getHierarchy().addObject(PathObjects.createAnnotationObject(anno_list.get(i).getROI(), pClass));
            }
        }
        try {
            imageEntry.saveImageData(image_data);
        } catch (IOException e) {
            logger.error("Failed to save image: " + imageEntry.getImageName());
            throw new RuntimeException(e);
        }
    }


    /**
     * Load a segmentation mask and adds it to a specific QuPath image entry
     * @param file = File of the mask
     * @param imageEntry = ProjectImageEntry, of the QuPath image that gets the mask
     * @param doSplit = boolean, whether to split the detected object into individual ones
     * @param doRemove = boolean, whether to remove ALL existing objects in the QuPath image entry
     * @param map_anno_class = Map<Integer, String>, map of label id (intestine in mask) mapped to
     *                       the class name that the that annotation should be added to.
     */
    public void load_maskFile(File file, ProjectImageEntry<BufferedImage> imageEntry,
                              boolean doSplit, boolean doRemove, Map<Integer, String> map_anno_class) {
        // Sanity test
        if (!qupath.getProject().getImageList().contains(imageEntry)) {
            logger.error("Project does not contain image: " + imageEntry.getImageName());
            throw new RuntimeException("Project does not contain image: " + imageEntry.getImageName());
        }
        if (!file.exists()) {
            logger.error("Mask file does not exist: " + file.getAbsolutePath());
            throw new RuntimeException("Mask file does not exist: " + file.getAbsolutePath());
        }

        // Load mask file
        logger.debug("Loading mask file: " + file.getAbsolutePath());
        ImagePlus mask = IJ.openImage(file.getAbsolutePath());

        // in case the image cannot be opened
        if (mask == null) {
            logger.error("Could not open mask file: " + file.getAbsolutePath());
            throw new RuntimeException("Could not open mask file: " + file.getAbsolutePath());
        }

        SimpleImage image = new PixelImageIJ(mask.getProcessor());
        ImageData<BufferedImage> imageData;
        // load objects into a Map<AnnotationClassName, List<PathObject>>
        Map<String, List<PathObject>> annotation_map = new HashMap<>();
        try {
            imageData = imageEntry.readImageData();
            RegionRequest request = RegionRequest.createAllRequests(imageData.getServer(), 1).get(0);
            for (int label : map_anno_class.keySet()) {
                List<PathObject> annotation = ContourTracing.createAnnotations(image, request, label, label);
                annotation_map.put(map_anno_class.get(label), annotation);
            }

        } catch (IOException e) {
            throw new RuntimeException("Could not read imageData from QuPath image: " + imageEntry.getImageName() + " -> " + e);
        }
        mask.close();

        // remove existing annotations from the image
        if (doRemove) imageData.getHierarchy().clearAll();

        // add the objects to the image
        for (Map.Entry<String, List<PathObject>> entry : annotation_map.entrySet()) {
            logger.debug("Start adding objects for label: " + entry.getKey() + " having n objects: " + entry.getValue().size());
            // IMPORTANT if there is no object (PathObject-list is empty), then skip (otherwise other images will be skipped)
            if (entry.getValue().isEmpty()) {
                logger.warn("No objects found for image <" + imageEntry.getImageName() + "> for annotation class <" + entry.getKey() + ">.");
            }
            else {
                if (!doSplit) {
                    imageData.getHierarchy().addObject(
                            PathObjects.createAnnotationObject(entry.getValue().get(0).getROI(), PathClass.getInstance(entry.getKey()))
                    );
                } else {
                    // Create the split ROIs
                    List<ROI> split_ROIs = RoiTools.splitROI(entry.getValue().get(0).getROI());
                    logger.debug("Split object into n ROIs: " + split_ROIs.size());
                    // Convert the ROIs to PathObjects
                    List<PathObject> split_annotations = new ArrayList<>();
                    split_ROIs.forEach(r -> split_annotations.add(
                            PathObjects.createAnnotationObject(r, PathClass.getInstance(entry.getKey()))
                    ));
                    // Add the PathObjects to the image
                    imageData.getHierarchy().addObjects(split_annotations);
                }
            }
        }

        // save the image
        try {
            imageEntry.saveImageData(imageData);
            logger.debug("Saved imageData: " + imageEntry.getImageName());
        } catch (IOException e) {
            logger.error("Could not add (save) the loaded mask to the image: " + imageEntry.getImageName() + " -> " + e);
        }
    }

    /**
     * Export images to predict to the temp folder.
     * @param imageList: List of ProjectImageEntry
     * @param downscale_factor: Integer downscale factor to export image at lower resolution
     * @param roi: ROI for a part of an image, null for full image
     * @return: map of matched ProjectImageEntry and PredictionFile (latter contains temp files and RegionRequest)
     */
    public HashMap<ProjectImageEntry<BufferedImage>, PredictionFile> exportImagesToPredict(
            List<ProjectImageEntry<BufferedImage>> imageList, Integer downscale_factor, ROI roi) {
        // Initialise return map
        HashMap<ProjectImageEntry<BufferedImage>, PredictionFile> out_map = new HashMap<>();

        // Export each image in the list
        imageList.forEach(i -> {
            // Get the QuPath image name and uri
            String image_name = i.getImageName();
            List<URI> uri = null;
            try {
                uri = i.getURIs().stream().collect(Collectors.toList());
            } catch (IOException ex) {
                logger.error("Error: could not get image path for image: <" + image_name + ">");
            }
            if (uri == null) logger.error("Could not read the image path for image <" + image_name + ">");
            else if (uri.isEmpty()) logger.error("No image path found for image <" + image_name + ">");
            else if (uri.size() > 1) logger.error("More than one path found for image <" + image_name + ">");
            else {
                logger.debug("current image: <" + image_name + ">, with image uri: " + uri.get(0));
                // Save image to the temp folder
                String clean_name = GeneralTools.stripExtension(new File(uri.get(0).getPath()).getName());
                File out_file = new File(temp_dir, clean_name + ".tif");

                // Get the ImageData
                ImageData<BufferedImage> imageData;
                try {
                    imageData = i.readImageData();
                } catch (IOException e) {
                    throw new RuntimeException("could not read image data for " + image_name + ". ", e);
                }
                // Write a downscaled version of the image
                ImageServer<BufferedImage> server = imageData.getServer();
                RegionRequest request;
                if (roi == null) request = RegionRequest.createInstance(server, downscale_factor);
                else request = RegionRequest.createInstance(server.getPath(), downscale_factor, roi);

                try {
                    ImageWriterTools.writeImageRegion(server, request, out_file.getAbsolutePath());
                    logger.info("Wrote image to: " + out_file.getAbsolutePath());
                    // Add the image to the return map
                    out_map.put(i, new PredictionFile(out_file, request, new File(prediction_dir, clean_name + ".tif")));
                    // Remember the temp_file in the class variable
                    temp_files.add(out_file);
                } catch (IOException e) {
                    logger.error("Could not write image region: " + image_name + " -> " + e);
                    throw new RuntimeException(e);
                }
            }
        });
        // Return the map
        return out_map;
    }

    /**
     * Deprecated:
     *  use exportImagesToPredict(List<ProjectImageEntry<BufferedImage>> imageList, Integer downscale_factor, ROI roi)
     *
     * Save a list of ImageEntries to the temp folder.
     * Same as the previous exportTempImages, but returns a map of image entries linked to their temp files.
     *
     * @param imageList: List of ProjectImageEntry
     * @return HashMap of ProjectImageEntry<BufferedImage> to temp file
     */
    @Deprecated
    public HashMap<ProjectImageEntry<BufferedImage>, File> exportImagesToPredict(List<ProjectImageEntry<BufferedImage>> imageList) {
        // Initialise return map
        HashMap<ProjectImageEntry<BufferedImage>, File> out_map = new HashMap<>();

        logger.debug("number of images to export: " + imageList.size());

        imageList.forEach(i -> {
            // get the QuPath image name and uri
            String image_name = i.getImageName();
            logger.debug("start export for image: <" + image_name + ">");
            List<URI> uri = null;
            try {
                uri = i.getURIs().stream().collect(Collectors.toList());
            } catch (IOException ex) {
                logger.error("Error: could not get image path for image: <" + image_name + ">");
            }
            if (uri == null) logger.error("Error: could not read image path for image <" + image_name + ">");
            else if (uri.size() > 1) logger.error("Error: more than one image path for image <" + image_name + ">");
            else {
                logger.debug("current image: <" + image_name + ">, with image uri: " + uri.get(0));
                // save image to temp folder
                image_name = GeneralTools.stripExtension(new File(uri.get(0).getPath()).getName());
                File out_file = new File(temp_dir, image_name + ".tif");
                ImageData<BufferedImage> image_data;
                try {
                    image_data = i.readImageData();
                } catch (IOException ex) {
                    throw new RuntimeException("Could not read image dta for " + image_name);
                }
                try {
                    ImageWriterTools.writeImage(image_data.getServer(), out_file.getAbsolutePath());
                    logger.trace("Saved image " + out_file.getAbsolutePath());
                    logger.debug("Saved image " + out_file.getAbsolutePath());
                    // add ImageEntry and file to return map
                    out_map.put(i, out_file);
                    // remember the temp file in class variable
                    temp_files.add(out_file);
                } catch (IOException ex) {
                    logger.debug("Caught IOException for writing temp images: " + ex.getMessage());
                    throw new RuntimeException("Could not save image " + out_file.getAbsolutePath());
                }
            }
        });
        return out_map;
    }


    /**
     * Export images with corresponding masks.
     * @param imageList: List of ProjectImageEntries to be exported
     * @param cropPathClass: String name for Annotation class to be used for region cropping (or null if no cropping)
     * @param fgPathClass: String name for Annotation class used as foreground label
     */
    // TODO check the image sizes it should not be below 256 in XY (check also if 256 is the correct number in my efficient net code)
    public void exportImageMaskPair(List<ProjectImageEntry<BufferedImage>> imageList, String cropPathClass, String fgPathClass) {
        // loop over the ProjectImageEntries
        imageList.forEach(image -> {
            // get the image name (without extension), and read the image data
            String image_name = image.getImageName();
            // if the image_name also contains a path, remove it (else Windows will not write the file)
            if ((image_name.contains(File.separator) || image_name.contains("\\")) && image_name.contains(" - ")) {
                image_name = image_name.split(" - ")[0];
                logger.trace("********* Removed path from image name: " + image_name);
            }
            image_name = GeneralTools.stripExtension(image_name);
            ImageData<BufferedImage> image_data;
            try {
                image_data = image.readImageData();
            } catch (Exception ex) {
                logger.error("Could not read image data for {} ({})", image.getImageName(), ex.getLocalizedMessage());
                throw new RuntimeException("Could not read image data for " + image_name);
            }

            // Create a list of ROIs for region cropping
            List<ROI> requestROIs = new ArrayList<>();

            // If no cropping region class selected, create region request for full image
            if (cropPathClass == null) {
                // create ROI for full image
                Geometry roi = createRectangle(0, 0, image_data.getServer().getWidth(), image_data.getServer().getHeight());
                requestROIs.add(geometryToROI(roi, ImagePlane.getDefaultPlane()));
            }
            else {
                List<PathObject> cropAnnotations = new ArrayList<>();
                Collection<PathObject> allAnnotations = image_data.getHierarchy().getAnnotationObjects();
                allAnnotations.stream().forEach(a -> {
                    // create ROI for annotation (that is not null)
                    if (a.getPathClass() != null && a.getPathClass().getName().equals(cropPathClass)) cropAnnotations.add(a);
                });

                // if there are no fitting cropping annotations, create regionROI for full image
                if (cropAnnotations.isEmpty()) {
                    Geometry roi = createRectangle(0, 0, image_data.getServer().getWidth(), image_data.getServer().getHeight());
                    requestROIs.add(geometryToROI(roi, ImagePlane.getDefaultPlane()));
                }
                // create regionROIs for all annotations objects
                else {
                    cropAnnotations.forEach(a -> {
                        Geometry roi = createRectangle(a.getROI().getBoundsX(), a.getROI().getBoundsY(), a.getROI().getBoundsWidth(), a.getROI().getBoundsHeight());
                        requestROIs.add(geometryToROI(roi,ImagePlane.getDefaultPlane()));
                    });
                }
            }

            // create a label mask
            LabeledImageServer mask = new LabeledImageServer.Builder(image_data)
                    .backgroundLabel(0, ColorTools.BLACK)
                    .multichannelOutput(false)
                    .useAnnotations()
                    .addLabel(fgPathClass, 1)
                    .build();

            // Save image and mask for each requestROI
            AtomicInteger count = new AtomicInteger(1);
            String name = image_name;
            requestROIs.forEach(roi -> {
                // Create a RegionRequest without downsampling
                RegionRequest request = RegionRequest.createInstance(image_data.getServer().getPath(), 1, roi);

                // write images to file
                File image_file = new File(images_dir, name + "_" + count.get() + ".tif");
                File mask_file = new File(masks_dir, name + "_" + count.get() + ".tif");

                try {
                    ImageWriterTools.writeImageRegion(image_data.getServer(), request, image_file.getAbsolutePath());
                    if (image_file.exists()) logger.info("Image has been written to: " + image_file.getAbsolutePath());
                    else logger.error("Image was NOT written to: " + image_file.getAbsolutePath());
                    ImageWriterTools.writeImageRegion(mask, request, mask_file.getAbsolutePath());
                    if (mask_file.exists()) logger.info("Mask has been written to: " + mask_file.getAbsolutePath());
                    else logger.error("Mask was NOT written to: " + mask_file.getAbsolutePath());
                } catch (Exception ex) {
                    logger.error("Error in writing images to file: {} ({})", name, ex.getLocalizedMessage());
                }
                count.getAndIncrement();
            });

        }); // end loop over selected project image entries
    } // export_images


    /**
     * Helper function to avoid manually reorganizing the training data,
     * after a training has already been initialised within the project.
     * Moves training images and masks, that were already split in train/val/test,
     * back to the original folders (images / masks folders).
     * And deletes the sub-folders (including image crops/patches).
     */
    public void resetTrainFolders() {
        List<String> subfolders = Arrays.asList("train", "val", "test");
        // Sanity checks
        for (String subfolder : subfolders) {
            File image_subfolder = new File(images_dir.getAbsolutePath(), subfolder);
            File mask_subfolder = new File(masks_dir.getAbsolutePath(), subfolder);
            if (!image_subfolder.exists()) {
                logger.error("The folder does not exist: " + image_subfolder.getAbsolutePath());
                throw new RuntimeException("The folder does not exist: " + image_subfolder.getAbsolutePath());
            }
            if (!mask_subfolder.exists()) {
                logger.error("The folder does not exist: " + mask_subfolder.getAbsolutePath());
                throw new RuntimeException("The folder does not exist: " + mask_subfolder.getAbsolutePath());
            }
        }

        // Get the images and masks that need to be moved
        List<File> image_files = new ArrayList<>();
        List<File> mask_files = new ArrayList<>();
        // Image files in the 3 subfolders
        for (String subfolder : subfolders) {
            for (File f : new File(images_dir.getAbsolutePath(), subfolder).listFiles()) {
                if (f.getName().endsWith(".tif")) image_files.add(f);
            }
        }
        // Mask files in the 3 subfolders
        for (String subfolder : subfolders) {
            for (File f : new File(masks_dir.getAbsolutePath(), subfolder).listFiles()) {
                if (f.getName().endsWith(".tif")) mask_files.add(f);
            }
        }

        // Move the images and masks
        for (File f : image_files) {
            File movedFile = new File(images_dir, f.getName());
            boolean isMoved = f.renameTo(movedFile);
            if (!isMoved) logger.error("Could not move file: " + f.getAbsolutePath());
            else logger.trace("Moved file to: " + movedFile.getAbsolutePath());
        }
        for (File f : mask_files) {
            File movedFile = new File(masks_dir, f.getName());
            boolean isMoved = f.renameTo(movedFile);
            if (!isMoved) logger.error("Could not move file: " + f.getAbsolutePath());
            else logger.trace("Moved file to: " + movedFile.getAbsolutePath());
        }

        // Delete the train/val/test subfolders
        List<Path> folders = new ArrayList<>();
        for (String subfolder : subfolders) {
            folders.add(Paths.get(images_dir.getAbsolutePath(), subfolder));
            folders.add(Paths.get(masks_dir.getAbsolutePath(), subfolder));
        }

        for (Path folder : folders) {
            try {
                Files.walk(folder).sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.delete(path);
                        logger.trace("Deleted file: " + path);
                    } catch (IOException e) {
                        logger.error("Could not delete file: " + path);
                        logger.error(e.getMessage(), e);
                        throw new RuntimeException("Could not delete file: " + path);
                    }
                });
            } catch (IOException e) {
                logger.error("Could not 'walk' path: " + folder);
                throw new RuntimeException(e);
            }
        }
    } // resetTrainFolders()

    public static class PredictionFile {
        private final RegionRequest request;
        private final File temp_file; // Exported "raw image"
        private File predicted_file;

        // Constructors
        PredictionFile(File temp_file, RegionRequest request, File predicted_file) {
            this.request = request;
            this.temp_file = temp_file;
            this.predicted_file = predicted_file;
        }

        PredictionFile(File temp_file, RegionRequest request) {
            this.request = request;
            this.temp_file = temp_file;
            this.predicted_file = null;
        }

        // Getters
        public RegionRequest getRequest() {
            return request;
        }

        public File getTempFile() {
            return temp_file;
        }

        public File getPredictedFile() {
            return predicted_file;
        }

        // Setter
        public void setPredicted_file(File predicted_file) {
            this.predicted_file = predicted_file;
        }
    }

} // end class