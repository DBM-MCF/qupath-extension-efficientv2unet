package qupath.ext.efficientv2unet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.biop.cmd.VirtualEnvironmentRunner;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.tools.GuiTools;
import qupath.lib.images.ImageData;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.roi.RectangleROI;
import qupath.lib.roi.interfaces.ROI;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * This class is very similar to:
 * https://github.com/BIOP/qupath-extension-cellpose/blob/main/src/main/java/qupath/ext/biop/cellpose/CellposeBuilder.java
 * 
 */

public class EfficientV2UNet {
    private static final Logger logger = LoggerFactory.getLogger(EfficientV2UNet.class);

    public static class Builder {
        private Project<BufferedImage> project;
        private final List<String> BASEMODELS = new ArrayList<String>(Arrays.asList("b0", "b1", "b2", "b3", "s", "m", "l"));

        // General settings
        private final transient EV2UnetSetup setup;
        private boolean train = false;
        private boolean predict = false;
        // Train settings
        private String train_image_dir;
        private String train_mask_dir;
        private String base_dir;
        private String name;
        private Integer epochs;
        private Integer training_batch_size;
        private String basemodel;
        // Predict settings
        private String model_path;
        private String temp_dir;
        private String predict_out_dir;
        private Integer downscale_factor;
        private Double threshold;
        private boolean predict_in_selection = false;
        private ROI roi;
        private boolean delete_temp_files = false;
        private boolean delete_prediction_files = false;
        private boolean use_less_memory = true;
        // Post-prediction settings
        private String annotation_class_name = "Region";
        private boolean split_object = false;
        private boolean remove_objects = false;

        /**
         * Constructor
         *
         */
        protected Builder() {
            this.setup = EV2UnetSetup.getInstance();
        }


        /**
         * Specify whether to train a model
         *
         * @param train: boolean
         * @return this builder
         */
        public Builder doTrain(boolean train) {
            this.train = train;
            return this;
        }

        /**
         * Sets the train flag to true
         * @return this Builder
         */
        public Builder doTrain() {
            this.train = true;
            return this;
        }

        /**
         * Specify whether to predict using a model
         *
         * @param predict: boolean
         * @return this builder
         */
        public Builder doPredict(boolean predict) {
            this.predict = predict;
            return this;
        }

        /**
         * Sets the predict flag to true
         * @return this Builder
         */
        public Builder doPredict() {
            this.predict = true;
            return this;
        }

        /**
         * Specify the (raw) image directory for training
         *
         * @param train_image_dir: String path to folder
         * @return this builder
         */
        public Builder setTrainImageDirectory(String train_image_dir) {
            this.train_image_dir = train_image_dir;
            return this;
        }

        /**
         * Specify the mask directory for training
         *
         * @param train_mask_dir: String path to folder
         * @return this builder
         */
        public Builder setTrainMaskDirectory(String train_mask_dir) {
            this.train_mask_dir = train_mask_dir;
            return this;
        }

        /**
         * Specify the base directory for saving the model
         *
         * @param base_dir: String path to existing folder
         * @return this builder
         */
        public Builder setBaseDirectory(String base_dir) {
            this.base_dir = base_dir;
            return this;
        }

        /**
         * Specify the name of the model
         *
         * @param name: String
         * @return this builder
         */
        public Builder setName(String name) {
            this.name = name;
            return this;
        }

        /**
         * Specify the base model (B0, B1, B2, B3, S, M or L)
         * @param basemodel: String
         * @return this builder
         */
        public Builder setBasemodel(String basemodel) {
            this.basemodel = basemodel;
            return this;
        }

        /**
         * Specify the number of epochs to train a model
         *
         * @param epochs: Integer
         * @return this builder
         */
        public Builder setEpochs(Integer epochs) {
            this.epochs = epochs;
            return this;
        }

        /**
         * Specify the training batch size.
         * Must be a power of 2
         * Reducing the batch size can help avoiding out of memory errors.
         * @param training_batch_size: Integer batch size
         * @return this builder
         */
        public Builder setTrainBatchSize(Integer training_batch_size) {
            this.training_batch_size = training_batch_size;
            return this;
        }

        /**
         * Specify the path to the model
         *
         * @param model_path: String path to h5 model file
         * @return this builder
         */
        public Builder setModelPath(String model_path) {
            this.model_path = model_path;
            return this;
        }

        /**
         * Specify the directory to predict images from
         *
         * @param temp_dir: String path to folder
         * @return this builder
         */
        public Builder setTempDir(String temp_dir) {
            this.temp_dir = temp_dir;
            return this;
        }

        /**
         * Specify the output folder to place the prediction in
         *
         * @param predict_out_dir: String path to folder
         * @return this builder
         */
        public Builder setPredictOutputDirectory(String predict_out_dir) {
            this.predict_out_dir = predict_out_dir;
            return this;
        }

        /**
         * Specify the resolution/downscale-factor,
         * at which the images should be predicted.
         * e.g. 1 = no downscaling, 2 = half resolution
         *
         * @param downscale_factor: Integer
         * @return this builder
         */
        public Builder setDownscale_factor(Integer downscale_factor) {
            this.downscale_factor = downscale_factor;
            return this;
        }

        /**
         * Specify whether to delete the temporary files.
         * If called will set delete_temp_files to true,
         * to flag the exported raw tifs to be deleted
         *
         * @return this builder
         */
        public Builder deleteTempFiles() {
            this.delete_temp_files = true;
            return this;
        }

        /**
         * Specify whether to delete the prediction files.
         * If called will set the delete_prediction_files to true,
         * to flag the generated prediction tif to be deleted.
         *
         * @return this builder
         */
        public Builder deletePredictionFiles() {
            this.delete_prediction_files = true;
            return this;
        }

        /**
         * Flag if to predict only in the current selection
         * @return this builder
         */
        public Builder predictInSelection() {
            this.predict_in_selection = true;
            return this;
        }

        /**
         * Specify the threshold to use for the prediction masks
         *
         * @param threshold: Double
         * @return this builder
         */
        public Builder setThreshold(Double threshold) {
            this.threshold = threshold;
            return this;
        }

        /**
         * Specify whether to use less memory, by predicting images one by one.
         * Rather than keeping all images in memory.
         *
         * @param use_less_memory: boolean
         * @return this builder
         */
        public Builder setUseLessMemory(boolean use_less_memory) {
            this.use_less_memory = use_less_memory;
            return this;
        }

        /**
         * Specify the class name for the annotations
         * @param name: String
         * @return this builder
         */
        public Builder setAnnotationClassName(String name) {
            this.annotation_class_name = name;
            return this;
        }

        /**
         * Specify whether to split the new objects into individual ones (or
         * keep them as a single object)
         * @param split: boolean
         * @return this builder
         */
        public Builder splitObject(boolean split) {
            this.split_object = split;
            return this;
        }

        /**
         * Sets the split_object flag to true
         * @return this Builder
         */
        public Builder splitObject() {
            this.split_object = true;
            return this;
        }

        /**
         * Specify whether to remove existing annotations from the image
         * @param remove: boolean
         * @return this builder
         */
        public Builder removeExistingObjects(boolean remove) {
            this.remove_objects = remove;
            return this;
        }

        /**
         * Flags to remove all existing image objects.
         * @return this Builder
         */
        public Builder removeExistingObjects() {
            this.remove_objects = true;
            return this;
        }

        /**
         * Create the EfficientV2UNet object for processing
         * @return
         */
        public EfficientV2UNet build() {
            // Check for a project (sanity checks)
            project = QuPathGUI.getInstance().getProject();
            if (project == null) {
                GuiTools.showNoProjectError("You need a project to run this plugin.");
                throw new IllegalStateException("You need a project to run this plugin.");
            }
            if (project.getPath() == null) throw new RuntimeException("Could not identify the path to the project. Make sure that the project is on a local file system.");

            EfficientV2UNet ev2unet = new EfficientV2UNet();

            // check that the setup is fine
            if (setup.getEv2unetPythonPath().isEmpty()) {
                throw new IllegalStateException("The EfficientV2UNet python path is empty. Please set it in Edit > Preferences.");
            }

            // check if training or predicting
            if (train == predict) {
                throw new IllegalArgumentException("Specify to either train or predict.");
            }
            // Training             --------------------------------------------
            else if (train) {
                // Check if the train image dir exists
                if (train_image_dir == null || !new File(train_image_dir).exists()) {
                    throw new IllegalArgumentException("Training image directory does not exist: " + train_image_dir);
                }
                // Check if the train mask dir exists
                if (train_mask_dir == null || !new File(train_mask_dir).exists()) {
                    throw new IllegalArgumentException("Training mask directory does not exist: " + train_mask_dir);
                }
                // Check the base_dir
                if (base_dir == null) {
                    base_dir = new File(project.getPath().getParent().toString(), "models").getAbsolutePath();
                    logger.info("Set the base directory to default: " + base_dir);
                }
                // Check basemodel
                if (basemodel == null) {
                    basemodel = "b0";
                    logger.info("Set the base model to default: " + basemodel);
                }
                else if (!BASEMODELS.contains(basemodel.toLowerCase())) {
                    throw new IllegalArgumentException("Invalid base model: " + basemodel);
                }
                else basemodel = basemodel.toLowerCase();
                // Check desired model name
                if (name == null) {
                    name = "EfficientV2UNet_" + basemodel;
                    logger.info("Set the model name to default: " + name);
                }
                // Check epochs
                if (epochs == null) {
                    epochs = 50;
                    logger.info("Set the number of epochs to default: " + epochs);
                }
                else if (epochs <= 0) {
                    throw new IllegalArgumentException("Invalid number of epochs: " + epochs);
                }
                // Check the training batch size
                if (training_batch_size == null || training_batch_size <= 0) {
                    training_batch_size = 32;
                    logger.info("Set the training batch size to default: " + training_batch_size);
                }
                else if (Math.log(training_batch_size) / Math.log(2) % 1 != 0) {
                    // Not a power of 2, round it
                    training_batch_size = (int) Math.pow(2, Math.round(Math.log(training_batch_size) / Math.log(2)));
                    logger.info("Rounded the training batch size to a power of 2: " + training_batch_size);
                }
            }

            // Predict               -------------------------------------------
            else {
                // check if model path exists
                if (model_path == null) {
                    throw new IllegalArgumentException("Model path cannot be null");
                }
                else if ( !new File(model_path).exists()) {
                    throw new IllegalArgumentException("Model path does not exist: " + model_path);
                }

                // Set the default temp directory (QuPathProject/temp) if not specified (and create it if it doesn't exist)
                if (temp_dir == null) {
                    temp_dir = new File(project.getPath().getParent().toString(), "temp").getAbsolutePath();
                    logger.info("Set the temporary directory to default: " + temp_dir);
                }
                if (!new File(temp_dir).exists()) {
                    new File(temp_dir).mkdirs();
                    logger.info("Created temporary directory: " + temp_dir);
                }

                // Set the default output directory (for predictions) if not specified (and create it if it doesn't exist)
                if (predict_out_dir == null) {
                    predict_out_dir = new File(temp_dir, "predictions").getAbsolutePath();
                    logger.info("Set the prediction output directory to default: " + predict_out_dir);
                }

                if (!new File(predict_out_dir).exists()) {
                    new File(predict_out_dir).mkdirs();
                    logger.info("Created output directory: " + predict_out_dir);
                }

                // Set the default downscale factor if not specified
                if (downscale_factor == null) {
                    downscale_factor = 1;
                    logger.warn("Downscaling factor not specified, defaulting to 1 (no downscaling)");
                }

                // Set the default threshold if not specified
                if (threshold == null || threshold < 0 || threshold > 1) {
                    threshold = 0.5;
                    logger.warn("Threshold not valid {}, defaulting to 0.5", threshold.toString());
                }
            }
            // set the other variables
            ev2unet.project = project;
            ev2unet.setup = setup;
            ev2unet.model_path = model_path;
            ev2unet.train = train;
            ev2unet.predict = predict;
            ev2unet.train_image_dir = train_image_dir;
            ev2unet.train_mask_dir = train_mask_dir;
            ev2unet.base_dir = base_dir;
            ev2unet.name = name;
            ev2unet.basemodel = basemodel;
            ev2unet.epochs = epochs;
            ev2unet.training_batch_size = training_batch_size;
            ev2unet.temp_dir = temp_dir;
            ev2unet.predict_out_dir = predict_out_dir;
            ev2unet.downscale_factor = downscale_factor;
            ev2unet.delete_temp_files = delete_temp_files;
            ev2unet.delete_prediction_files = delete_prediction_files;
            ev2unet.predict_in_selection = predict_in_selection;
            ev2unet.threshold = threshold;
            ev2unet.use_less_memory = use_less_memory;
            ev2unet.annotation_class_name = annotation_class_name;
            ev2unet.split_object = split_object;
            ev2unet.remove_objects = remove_objects;
            return ev2unet;
        }


    } // end of Builder class

    // EfficientV2UNet class variables
    Project<BufferedImage> project;
    // Set defaults

    // General settings
    private EV2UnetSetup setup;
    private String model_path;
    private boolean train;
    private boolean predict;
    // Train settings
    private String train_image_dir;
    private String train_mask_dir;
    private String base_dir;
    private String basemodel;
    private String name;
    private Integer epochs;
    private Integer training_batch_size;
    // Predict settings
    private String temp_dir;
    private String predict_out_dir;
    private Integer downscale_factor;
    private Double threshold;
    private boolean use_less_memory;
    private boolean delete_temp_files;
    private boolean delete_prediction_files;
    private boolean predict_in_selection;
    // Post-prediction settings
    private String annotation_class_name = "Region";
    private boolean split_object = false;
    private boolean remove_objects = false;

    /**
     * Create a builder to customize EfficientV2UNet parameters
     * @return
     */
    public static Builder builder() {
        return new Builder();
    }

    // EfficientV2UNet methods (e.g. to process) // TODO split into subproceses that are controlled via main one.

    /**
     * Calls the corresponding function with null argument for the image data
     */
    public void process() {
        process(null, null);
    }

    /**
     * This function is to be used when running via script.
     *
     * @param image_data: ImageData<BufferedImage> of the current image or null. Allows to run for project
     */
    public void process(ImageData<BufferedImage> image_data, ROI selection) {
        if (this.predict == this.train) {
            throw new IllegalArgumentException("Specify either train or predict");
        }

        if (this.train) {
            //throw new RuntimeException("Training is not yet implemented!"); // FIXME
            // FIXME not sure if I should check if tif files exist here or ignore it
            logger.info("Start training");
            doTrain();
        }
        // Predict the current image
        else {
            // Get the currently opened image
            logger.debug("n objects: " + image_data.getHierarchy().getAllObjects(true).size());
            if (image_data == null) {
                Dialogs.showErrorMessage("No image open", "Please open an image first");
                throw new RuntimeException("Please open an image first");
            }
            // Find the ProjectImageEntry for the current image
            ProjectImageEntry<BufferedImage> img_entry = project.getEntry(image_data);

            // Create the OpInEx object to use image export and import functions
            OpInEx ops = new OpInEx(QuPathGUI.getInstance());
            ops.setTemp_dir(temp_dir); // The temp dir has already been created
            ops.setPrediction_dir(predict_out_dir); // The prediction dir has already been created

            // Optionally, predict only in the current selection
            if (predict_in_selection) {
                // Non-rectangle ROIs will be bounding-box loaded segmentation
                // Only allow rectangles as selections
                if (selection == null) {
                    logger.warn("No active selection. Using entire image for prediction!");
                }
                else if (selection.getClass() != RectangleROI.class) {
                    logger.error("Only rectangle selections are supported. Selection was: " + selection.getClass());
                    throw new RuntimeException("Only rectangle selections are supported");
                }
                logger.debug("selection: " + selection);
            }

            // Export the image
            logger.info("Exporting image...");
            HashMap<ProjectImageEntry<BufferedImage>, OpInEx.PredictionFile> map_entry_prediction = ops.exportImagesToPredict(Arrays.asList(img_entry), downscale_factor, selection);

            logger.info("Predicting image...");
            doPredict();

            logger.info("Loading prediction...");
            Map<Integer, String> map_label_anno = Map.of(1, annotation_class_name);
            ops.load_pred(map_entry_prediction.get(img_entry), img_entry, split_object, remove_objects, map_label_anno);

            logger.info("Finished loading prediction!");

            // Delete all temp files that are remembered in the ops
            if (delete_temp_files) {
                logger.info("Deleting temporary raw file...");
                ops.deleteTempFiles();
            }
            // Delete only the generated prediction file
            if (delete_prediction_files) {
                logger.info("Deleting temporary prediction file...");
                ops.deletePredictionFiles(Arrays.asList(map_entry_prediction.get(img_entry).getPredictedFile()));
            }

            // Update the viewer. Needs a dirty trick to actually load the changes...
            ImageData<BufferedImage> img_data;
            try {
                // read the ImageData from the modified project entry
                img_data = img_entry.readImageData();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            // Set the current viewer ImageData-Hierarchy to the one of the modified project entry
            image_data.getHierarchy().setHierarchy(img_data.getHierarchy());
            // Fire the change event
            image_data.getHierarchy().fireHierarchyChangedEvent(image_data.getHierarchy());
            // No need to re-save the ImageData, since it is already saved...
        }
    } // end process function


    /**
     * runs the prediction, which blocks qupath
     * is called by the process function, or also directly via the PredictCommand
     * see comment above...
     * currently public, as I directly access it in the PredictCommand
     */
    public void doPredict(){
        VirtualEnvironmentRunner venv = new VirtualEnvironmentRunner(
                setup.getEv2unetPythonPath(), setup.getEnvtype(), this.getClass().getSimpleName()
        );
        // build the cli arguments
        List<String> args = new ArrayList<>(Arrays.asList("-W", "ignore", "-m", "efficient_v2_unet", "--predict"));
        args.add("--dir");
        args.add(temp_dir);
        args.add("--model");
        args.add(model_path);
        args.add("--resolution");
        args.add("1"); // The resolution here is fixed to 1, as QuPath takes care of downscaling
        args.add("--threshold");
        args.add(threshold.toString());
        args.add("--savedir");
        args.add(predict_out_dir);
        if (use_less_memory) args.add("--use_less_memory");

        // run the command
        venv.setArguments(args);
        try {
            venv.runCommand(false);
        } catch (IOException e) {
            logger.error("Exception while running the CLI command: " + e.getLocalizedMessage());
        }
        // wait for the command to finish
        try {
            venv.getProcess().waitFor();
        } catch (InterruptedException e) {
            throw new RuntimeException("CLI execution/interruption error: " + e);
        }
        List<String> log = venv.getProcessLog();
        System.out.println("Prediction finished!");
    }

    /**
     * Run the training.
     *
     */
    public void doTrain() {
        // Check if training data already exist and allow to reset the split data
        if (!resetTrainingData()) {
            logger.warn("Training aborted. Training data split into train/val/test already exists. Manual clean-up has been selected.");
            return;
        }

        VirtualEnvironmentRunner venv = new VirtualEnvironmentRunner(
                setup.getEv2unetPythonPath(), setup.getEnvtype(), this.getClass().getSimpleName()
        );
        // Build the cli arguments
        List<String> args = new ArrayList<>(Arrays.asList("-W", "ignore", "-m", "efficient_v2_unet", "--train"));
        args.add("--images");
        args.add(train_image_dir);
        args.add("--masks");
        args.add(train_mask_dir);
        args.add("--basedir");
        args.add(base_dir);
        args.add("--name");
        args.add(name);
        args.add("--basemodel");
        args.add(basemodel);
        args.add("--train_batch_size");
        args.add(training_batch_size.toString());
        args.add("--epochs");
        args.add(epochs.toString());

        // run the command
        venv.setArguments(args);
        try {
            venv.runCommand(false);
        } catch (IOException e) {
            logger.error("Exception while running the CLI command: " + e.getLocalizedMessage());
        }
        // FIXME would be nice to have the progress dialog here...? probably not possible...
        // wait for the command to finish
        try {
            venv.getProcess().waitFor();
        } catch (InterruptedException e) {
            throw new RuntimeException("CLI execution/interruption error: " + e);
        }
        List<String> log = venv.getProcessLog();
        System.out.println("Training finished!");

    }

    /**
     * Will re-organise training data already split into train/val/test, after prompting the user.
     * It moves images in those sub-folders back to the images/mask directories,
     * and deletes all the sub-folders and their data.
     * @return boolean:
     *          - true if none of the sub-folders exist, or deletion was successful
     *          - false if user does not want automatic file moving/deletion
     */
    public boolean resetTrainingData() {
        List<String> subfolders = Arrays.asList("train", "val", "test");
        // Sanity checks
        boolean subfoldersExist = false;
        for (String subfolder : subfolders) {
            File image_subfolder = new File(train_image_dir, subfolder);
            File mask_subfolder = new File(train_mask_dir, subfolder);
            if (image_subfolder.exists()) {
               subfoldersExist = true;
               break;
            }
            if (mask_subfolder.exists()) {
                subfoldersExist = true;
                break;
            }
        }
        // Continue if none of the sub-folders exist
        if (!subfoldersExist) return true;

        // Prompt user if
        boolean reset = Dialogs.showYesNoDialog("Training data already split",
                "The training data is already split in train/val/test.\nDo you want to reset it?\n" +
                      "(Yes)\nWill move training images & masks\n" +
                      "and delete all existing sub-folders and patches.\n" +
                      "(No)\nWill abort the training and allow you to\n" +
                      "re-organise the data manually."
                );
        if (!reset) return false;

        // Get all files in the 3 sub-folders
        List<File> image_files = new ArrayList<>();
        List<File> mask_files = new ArrayList<>();
        for (String subfolder : subfolders) {
            for (File f : new File(train_image_dir, subfolder).listFiles()) {
                if (f.getName().endsWith(".tif")) image_files.add(f);
            }
        }
        for (String subfolder : subfolders) {
            for (File f : new File(train_mask_dir, subfolder).listFiles()) {
                if (f.getName().endsWith(".tif")) mask_files.add(f);
            }
        }

        // Move files to their respective folders
        try {
            for (File f : image_files) {
                File movedFile = new File(train_image_dir, f.getName());
                boolean isMoved = f.renameTo(movedFile);
                if (!isMoved) logger.error("Could not move file: " + f.getAbsolutePath());
                else logger.info("Moved file to: " + movedFile.getAbsolutePath());
            }
            for (File f : mask_files) {
                File movedFile = new File(train_mask_dir, f.getName());
                boolean isMoved = f.renameTo(movedFile);
                if (!isMoved) logger.error("Could not move file: " + f.getAbsolutePath());
                else logger.info("Moved file to: " + movedFile.getAbsolutePath());
            }
        } catch (Exception e) {
            logger.error("Exception while moving files: " + e.getLocalizedMessage());
            throw new RuntimeException("Exception while moving files: " + e.getLocalizedMessage());
        }

        // Delete the sub-folders
        List<Path> folders = new ArrayList<>();
        for (String subfolder : subfolders) {
            folders.add(Paths.get(train_image_dir, subfolder));
            folders.add((Paths.get(train_mask_dir, subfolder)));
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
                logger.error("Could not 'walk' the path to delete them: " + folder);
                throw new RuntimeException(e);
            }
        }
        // Return true that moving and deleting worked fine
        return true;
    }


} // end of EfficientV2UNet class
