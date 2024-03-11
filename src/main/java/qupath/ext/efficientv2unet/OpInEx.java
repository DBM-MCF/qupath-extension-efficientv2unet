package qupath.ext.efficientv2unet;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import ij.IJ;
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
import qupath.lib.images.servers.LabeledImageServer;
import qupath.lib.images.writers.ImageWriterTools;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.regions.ImagePlane;
import qupath.lib.regions.RegionRequest;
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
    public ArrayList<File> temp_files = null;

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
     * Getter for the prediction_dir
     * @return String prediction_dir
     */
    public String getPrediction_dir() {
        return prediction_dir.getAbsolutePath();
    }

    /**
     * Creates output folders
     * training_root = project_folder_path / Efficient_V2_UNet
     * images_dir = project_folder_path / Efficient_V2_UNet / images
     * masks_dir = project_folder_path / Efficient_V2_UNet / masks
     */
    private void create_output_folders() {
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
            if (Dialogs.showConfirmDialog("TIF images already exist", "The output folders already contain images.\nImages may be overwritten.\n'OK' to delete, 'Cancel' to keep them")) {
                Arrays.stream(images_dir.listFiles()).toList().forEach(File::delete);
                Arrays.stream(masks_dir.listFiles()).toList().forEach(File::delete);
            }
        }
    }

    /**
     * Delete temp files
     * @return boolean
     */
    public boolean deleteTempFiles() {
        if (temp_files != null) {
            temp_files.forEach(f -> {
                if (f.delete()) {
                    logger.trace("Deleted temp file: " + f.getAbsolutePath());
                }
                else logger.trace("Could not delete temp file: " + f.getAbsolutePath());
            });
            return true;
        }
        else return false;
    }


    /**
     * Saves a list of images to the temp folder
     *
     * @param imageList = List of ProjectImageEntries to be saved as tif
     * @return List of saved files
     */
    public ArrayList<File> exportTempImages(List<ProjectImageEntry<BufferedImage>> imageList) {
        // First create the output folders
        create_output_folders();

        // remember the files that have been written (return of this function)
        ArrayList<File> out_files = new ArrayList<File>();

        imageList.forEach(i -> {
            // get the file name as it is in QuPath
            String image_name = i.getImageName();
            System.out.println("image name    : " + image_name);
            // since getImageName is odd, i get the image name from the uri
            List<URI> uri = null;
            try {
                uri = i.getURIs().stream().collect(Collectors.toList());
            } catch (IOException ex)  {
                logger.error("Error: could not get image path fro image: " + image_name);
            }
            if (uri == null) logger.error("Error: could not read image path for image <" + image_name + ">");
            else if (uri.size() > 1) logger.error("Error: multiple paths for image <" + image_name + ">: currently not supported");
            else {
                // save image to temp folder
                image_name = GeneralTools.stripExtension(new File(uri.get(0).getPath()).getName());
                System.out.println("image name NOW: " + image_name);
                File out_file = new File(temp_dir, image_name + ".tif");
                ImageData<BufferedImage> image_data = null;
                try {
                    image_data = i.readImageData();
                } catch (IOException ex) {
                    throw new RuntimeException("Could not read image data for " + image_name);
                }
                try {
                    ImageWriterTools.writeImage(image_data.getServer(), out_file.getAbsolutePath());
                    out_files.add(out_file);
                    logger.trace("Saved image: " + out_file.getAbsolutePath());
                } catch (IOException ex) {
                    throw new RuntimeException("Could not write image:" + out_file.getAbsolutePath());
                }
                System.out.println("Image should have been written to: " + out_file.getAbsolutePath());
            }
            System.out.println("image <"+image_name+"> done; temp_folder_path= " + temp_dir.getAbsolutePath());
        });
        // remember the files that have been written (in the class)
        temp_files = out_files;
        return out_files;
    }

    /**
     * Export images with corresponding masks.
     * @param imageList: List of ProjectImageEntries to be exported
     * @param cropPathClass: String name for Annotation class to be used for region cropping
     * @param fgPathClass: String name for Annotation class used as foreground label
     */
    public void exportImageMaskPair(List<ProjectImageEntry<BufferedImage>> imageList, String cropPathClass, String fgPathClass) {
        // First create the output folders
        create_output_folders();

        // loop over the ProjectImageEntries
        imageList.forEach(image -> {
            // get the image name (without extension), and read the image data
            String image_name = GeneralTools.stripExtension(image.getImageName());
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
            requestROIs.forEach(roi -> {
                // Create a RegionRequest without downsampling
                RegionRequest request = RegionRequest.createInstance(image_data.getServer().getPath(), 1, roi);

                // write images to file
                File image_file = new File(images_dir, image_name + "_" + count.get() + ".tif");
                File mask_file = new File(masks_dir, image_name + "_" + count.get() + ".tif");

                try {
                    ImageWriterTools.writeImageRegion(image_data.getServer(), request, image_file.getAbsolutePath());
                    logger.info("Image has been written to: " + image_file.getAbsolutePath());
                    ImageWriterTools.writeImageRegion(mask, request, mask_file.getAbsolutePath());
                    logger.info("Mask has been written to: " + mask_file.getAbsolutePath());
                } catch (Exception ex) {
                    logger.error("Error in writing images to file: {} ({})", image_name, ex.getLocalizedMessage());
                }
                count.getAndIncrement();
            });

        }); // end loop over selected project image entries
    } // export_images






    //      ---------------- FIXME old testing functions    --------------------



    public void load_a_segmentation(String path, String anno_name) {
        // FIXME I am immediately overwriting the file path variable for testing
        create_output_folders(); // FIXME temp
        path = new File(masks_dir, "mCherry.tif").getAbsolutePath(); // FIXME temp

        // get the imageData and check that an image is open
        ImageData<BufferedImage> imageData = qupath.getImageData();
        if (imageData == null) {
            GuiTools.showNoImageError("Please open an image first");
            return; // return if no image (ends the function)
        }

        // load the mask
        List<PathObject> annotations;
        try {
            SimpleImage image = new PixelImageIJ(IJ.openImage(path).getProcessor());
            annotations = ContourTracing.createAnnotations(image, RegionRequest.createAllRequests(imageData.getServer(), 1).get(0), 1, 1);
        }
        catch (Exception e) {
            logger.error("Unable to load mask", e);
            return;
        }
        try {
            assert annotations.size() == 1;
        }
        catch (AssertionError e) {
            logger.error("Something went wrong, there should be only one annotation, but found {}", annotations.size(), e);
        }
        PathObject anno = annotations.get(0);
        anno.setName("EfficientV2UNet_Results");
        anno.setPathClass(PathClass.getInstance(anno_name));
        logger.info("is annotation = {}", anno.isAnnotation());
        // add the annotations to the image
        imageData.getHierarchy().addObjects(annotations);


        // load the mask as simple image (for single channel will do)
        //SimpleImage image = mask.
        // use contour tracing for creating annotaitons

    }

    /**
     * Gets (currently) the current image and saves the image and mask into
     * corresponding folders.
     * Uses ROI_name to crop the input image.
     * Uses anno_name as objects of interest (which will have a value of 1).
     * @param ROI_name String: currently a placeholder for a PathClass
     * @param anno_name String: currently a placeholder for PathClass
     */
    public void write_an_image(String ROI_name, String anno_name) {
        // create output folders
        logger.info("Creating output folders");
        create_output_folders();
        // get the imageData and image name
        ImageData<BufferedImage> imageData = qupath.getImageData();
        
        String image_name = null;
        try {
            image_name = imageData.getServer().getMetadata().getName();
        }
        catch (Exception e) {
            GuiTools.showNoImageError("Please open an image first");
            return; // return if no image
        }
        image_name = GeneralTools.stripExtension(image_name); // remove extension
        logger.info("image_name= " + image_name);

        // get all image annotations
        Collection<PathObject> all_annos = imageData.getHierarchy().getAnnotationObjects();
        // get only the ones called "ROIS"
        List<PathObject> rois = all_annos.stream().filter(a -> a.getPathClass() == PathClass.getInstance(ROI_name)).collect(Collectors.toList());
        ROI requestROI;
        int downsample = 1;
        if (rois.isEmpty()) {
            logger.warn("No annotations called '{}' found", ROI_name);
            // create ROI for full image
            Geometry roi = createRectangle(0, 0, imageData.getServer().getWidth(), imageData.getServer().getHeight());
            requestROI = geometryToROI(roi, ImagePlane.getDefaultPlane());
        }
        else if (rois.size() > 1) {
            logger.info("Found {} '{}'", rois.size(), ROI_name);
            requestROI = make_single_roi(rois);
            // test the roi on the image
            //PathObject anno = createAnnotationObject(roi, PathClass.getInstance("Other"));
            //imageData.getHierarchy().addObject(anno);

        }
        else {
            logger.info("Found exactly 1 '{}'", ROI_name);
            requestROI = rois.get(0).getROI();
        }
        // create region request from the defined ROI
        RegionRequest request = RegionRequest.createInstance(imageData.getServer().getPath(),downsample, requestROI);

        // create the mask of the region
        LabeledImageServer mask = new LabeledImageServer.Builder(imageData)
                .backgroundLabel(0, ColorTools.BLACK)
                .multichannelOutput(false)
                .useAnnotations()
                .addLabel(anno_name, 1)
                //.useFilter(o -> o.getPathClass() == PathClass.getInstance(anno_name))
                .build();

        // write image to file
        File image_file = new File(images_dir, image_name  + ".tif");
        File mask_file = new File(masks_dir, image_name  + ".tif");
        try {
            ImageWriterTools.writeImageRegion(imageData.getServer(), request, image_file.getAbsolutePath());
            logger.info("Wrote image to: " + image_file.getAbsolutePath());
            ImageWriterTools.writeImageRegion(mask, request, mask_file.getAbsolutePath());
            logger.info("Wrote mask to: " + mask_file.getAbsolutePath());
        }
        catch (Exception e) {
            logger.error(e.getMessage(), e);
        }

    } // end "write_an_image" function

    /**
     * Takes a list of annotations (PathObjects) and returns a single ROI which encloses them all.
     * @param rois: list of PathObjects
     * @return ROI
     */
    private ROI make_single_roi(List<PathObject> rois) {
        // Find the min/max x and y
        double x_min = Double.POSITIVE_INFINITY;
        double y_min = Double.POSITIVE_INFINITY;
        double x_max = 0;
        double y_max = 0;
        for (int i = 0; i < rois.size(); i++) {
            double t_l_x = rois.get(i).getROI().getBoundsX(); // top left x
            double t_l_y = rois.get(i).getROI().getBoundsY(); // top left y
            double b_r_x = rois.get(i).getROI().getBoundsX() + rois.get(i).getROI().getBoundsWidth(); // bottom right x
            double b_r_y = rois.get(i).getROI().getBoundsY() + rois.get(i).getROI().getBoundsHeight(); // bottom right y

            if (t_l_x < x_min) x_min = t_l_x;
            if (t_l_y < y_min) y_min = t_l_y;
            if (b_r_x > x_max) x_max = b_r_x;
            if (b_r_y > y_max) y_max = b_r_y;
        }
        Geometry geo = createRectangle(x_min, y_min, x_max - x_min, y_max - y_min);
        return geometryToROI(geo, rois.get(0).getROI().getImagePlane()); // return a ROI
    }

    //      --------- TODO: List of ideas for future development    ------------
    //

    /*
     * TODO IDEA:
     * Have a tile writer directily for training?
     * see:
     * https://github.com/qupath/qupath/blob/13bdeed047b4d05f35f47308b36b48c0f2bb3a24/qupath-core/src/main/java/qupath/lib/images/writers/TileExporter.java#L564
     *
     */


} // end class