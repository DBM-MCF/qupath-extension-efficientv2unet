package qupath.ext.efficientv2unet;

import ij.IJ;
import ij.ImagePlus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.biop.cmd.VirtualEnvironmentRunner;
import qupath.fx.dialogs.Dialogs;
import qupath.imagej.tools.PixelImageIJ;
import qupath.lib.analysis.images.ContourTracing;
import qupath.lib.analysis.images.SimpleImage;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.tools.GuiTools;
import qupath.lib.images.ImageData;
import qupath.lib.images.writers.ImageWriterTools;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.projects.Project;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.RoiTools;
import qupath.lib.roi.interfaces.ROI;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * This class should be very similar to:
 * https://github.com/BIOP/qupath-extension-cellpose/blob/main/src/main/java/qupath/ext/biop/cellpose/CellposeBuilder.java
 * 
 */

// FIXME currently a problem: I want this builder, to have the possibility to use it in scripts
//   However: - it will work with the extension
//            - for script, i need to have an way to get the current image and save it to a default folder
//              - need to have a way to save the image as tif, predict it, load the mask and eventually delete the temporary files
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
        private String predict_dir;
        private String predict_out_dir;
        private Integer resolution;
        private Double threshold;
        private boolean use_less_memory = true;
        // Post-prediction settings
        private String annotation_class_name = "Region";
        private boolean split_annotations = false;
        private boolean remove_annotations = false;


        /**
         * @Deprecated - should not use this, as model_path variable is specific to predict only
         * Constructor
         *
         * @param model_path: String path of the model
         *
        protected Builder(String model_path) {
            this.model_path = model_path;
            this.setup = EV2UnetSetup.getInstance();
        }
        */

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
         * @param base_dir: String path to exisiting folder
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
         * @param training_batch_size
         * @return
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
         * @param predict_dir: String path to folder
         * @return this builder
         */
        public Builder setTempDir(String predict_dir) {
            this.predict_dir = predict_dir;
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
         * Specify the resolution at which the images should be predicted.
         * e.g. 1 = full resolution, 2 = half resolution
         *
         * @param resolution: Integer
         * @return this builder
         */
        public Builder setResolution(Integer resolution) {
            this.resolution = resolution;
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
        public Builder doSplitObject(boolean split) {
            this.split_annotations = split;
            return this;
        }

        /**
         * Specify whether to remove existing annotations from the image
         * @param remove: boolean
         * @return this builder
         */
        public Builder doRemoveExistingAnnotations(boolean remove) {
            this.remove_annotations = remove;
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
                if (predict_dir == null) {
                    predict_dir = new File(project.getPath().getParent().toString(), "temp").getAbsolutePath();
                    logger.info("Set the temporary directory to default: " + predict_dir);
                }
                if (!new File(predict_dir).exists()) {
                    new File(predict_dir).mkdirs();
                    logger.info("Created temporary directory: " + predict_dir);
                }

                // Set the default output directory (for predictions) if not specified (and create it if it doesn't exist)
                if (predict_out_dir == null) {
                    predict_out_dir = new File(predict_dir, "predictions").getAbsolutePath();
                    logger.info("Set the prediction output directory to default: " + predict_out_dir);
                }

                if (!new File(predict_out_dir).exists()) {
                    new File(predict_out_dir).mkdirs();
                    logger.info("Created output directory: " + predict_out_dir);
                }

                // Set the default resolution if not specified
                if (resolution == null) {
                    resolution = 1;
                    logger.warn("Resolution not specified, defaulting to 1 (full resolution)");
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
            ev2unet.predict_dir = predict_dir;
            ev2unet.predict_out_dir = predict_out_dir;
            ev2unet.resolution = resolution;
            ev2unet.threshold = threshold;
            ev2unet.use_less_memory = use_less_memory;
            ev2unet.annotation_class_name = annotation_class_name;
            ev2unet.split_annotations = split_annotations;
            ev2unet.remove_annotations = remove_annotations;
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
    private String predict_dir;
    private String predict_out_dir;
    private Integer resolution;
    private Double threshold;
    private boolean use_less_memory;
    // Post-prediction settings
    private String annotation_class_name = "Region";
    private boolean split_annotations = false;
    private boolean remove_annotations = false;

    /**
     * Create a builder to customize EfficientV2UNet parameters
     * @return
     */
    public static Builder builder() {
        return new Builder();
    }

    // EfficientV2UNet methods (e.g. to process) // TODO split into subproceses that are controlled via main one.

    /**
     * This function is to be used when running via script
     */
    public void process() {
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
            ImageData<BufferedImage> image_data = QuPathGUI.getInstance().getImageData();
            String image_name = QuPathGUI.getInstance().getDisplayedImageName(image_data);
            if (image_data == null) {
                logger.trace("Error: Please open an image first");
                throw new RuntimeException("--> Please open an image first <--");
            }
            image_name = "temp_image.tif";
            System.out.println("Image name is now: " + image_name);
            File temp_file = new File(this.predict_dir, image_name);
            try {
                ImageWriterTools.writeImage(image_data.getServer(), temp_file.getAbsolutePath());
            } catch (IOException e) {
                throw new RuntimeException("Could not write image!  >" + e);
            }
            logger.info("Saved current image to: " + temp_file.getAbsolutePath());

            // predict the image
            doPredict();
            logger.info("Predicted image");

            // Load the mask
            File prediction_file = new File(this.predict_out_dir, image_name);
            ImagePlus mask = IJ.openImage(prediction_file.getAbsolutePath());
            if (mask == null) throw new RuntimeException("Could not open predicted file: " + prediction_file.getAbsolutePath());
            SimpleImage mask_image = new PixelImageIJ(mask.getProcessor());
            List<PathObject> annotation = ContourTracing.createAnnotations(mask_image, RegionRequest.createAllRequests(image_data.getServer(), 1).get(0), 1, 1);
            logger.info("Loaded predicted image");

            // Remove existing annotations
            if (this.remove_annotations) {
                image_data.getHierarchy().clearAll();
                logger.info("Removed all existing annotations from current image");
            }

            if (this.split_annotations) {
                List<ROI> split_ROIs = RoiTools.splitROI(annotation.get(0).getROI());
                List<PathObject> split_annotation = new ArrayList<>();
                split_ROIs.forEach(r -> split_annotation.add(
                        PathObjects.createAnnotationObject(r, PathClass.getInstance(this.annotation_class_name))
                ));
                image_data.getHierarchy().addObjects(split_annotation);
            } else {
                image_data.getHierarchy().addObject(
                        PathObjects.createAnnotationObject(annotation.get(0).getROI(), PathClass.getInstance(this.annotation_class_name))
                );
            }
            // Fire global update event
            image_data.getHierarchy().fireHierarchyChangedEvent(image_data.getHierarchy());
            logger.info("Added the predicted objects to the current image");

            // Delete the temp file
            if (temp_file.delete()) logger.trace("Deleted temporary file: " + temp_file.getAbsolutePath());
            else logger.info("Could not delete temporary file: " + temp_file.getAbsolutePath());

            // Delete temp prediction
            if (prediction_file.delete()) logger.trace("Deleted temporary prediction file: " + prediction_file.getAbsolutePath());
            else logger.info("Could not delete temporary prediction file: " + prediction_file.getAbsolutePath());

        }

    }


    // FIXME: I could have a 'doPredict' with more control, that i can listen to files being created, similar to:
    //  https://github.com/BIOP/qupath-extension-cellpose/blob/679839a95302470eb12b5038b418be7454916137/src/main/java/qupath/ext/biop/cellpose/Cellpose2D.java#L786

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
        args.add(predict_dir);
        args.add("--model");
        args.add(model_path);
        args.add("--resolution");
        args.add(resolution.toString());
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
