package qupath.ext.efficientv2unet;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.collections.ListChangeListener;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import org.controlsfx.control.ListSelectionView;
import org.controlsfx.dialog.ProgressDialog;
import org.controlsfx.tools.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.biop.cmd.VirtualEnvironmentRunner;
import qupath.fx.dialogs.Dialogs;
import qupath.fx.utils.FXUtils;
import qupath.fx.utils.GridPaneUtils;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.dialogs.ProjectDialogs;
import qupath.lib.gui.tools.GuiTools;
import qupath.lib.objects.*;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.*;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;



/**
 * Allows to create training data set and train an Efficient V2 UNet model
 *
 * @author Loïc Sauteur
 */
public class EV2UNetTrainCommand implements Runnable {
    private QuPathGUI qupath;
    private String title = "Train an Efficient V2 UNet";
    private static final Logger logger = LoggerFactory.getLogger(EV2UNetTrainCommand.class);
    private ObjectProperty<Future<?>> runningTask = new SimpleObjectProperty<>();
    private static final  Map<String, String> baseModelMap = Map.of("EfficientNetV2-B0", "b0", "EfficientNetV2-B1", "b1", "EfficientNetV2-B2", "b2", "EfficientNetV2-B3", "b3","EfficientNetV2-S", "s", "EfficientNetV2-M", "m","EfficientNetV2-L", "l");

    // Class variables
    private Project<BufferedImage> project;
    private ListSelectionView<ProjectImageEntry<BufferedImage>> listSelectionView;
    private List<ProjectImageEntry<BufferedImage>> previousImages = new ArrayList<>();
    List<ProjectImageEntry<BufferedImage>> availableImageList = new ArrayList<>();


    // GUI
    private Dialog<ButtonType> dialog = null;
    private Boolean export_only = false;
    // own GUI elements
    private ComboBox<String> pathClassCropCombo;
    private ComboBox<String> pathClassFGCombo;
    private ButtonType btnTrain = new ButtonType("Export & Train", ButtonBar.ButtonData.OK_DONE);
    private ComboBox<String> baseModelCombo;
    private TextField epochsField;

    // Train variables
    private String cropSelection;
    private String fgSelection;
    private String baseModel;
    private int epochs;
    private List<ProjectImageEntry<BufferedImage>> selectedImages;

    /**
     * Constructor
     * @param qupath
     */
    public EV2UNetTrainCommand(final QuPathGUI qupath) {
        this.qupath = qupath;
    }

    /**
     * Basically the main method
     */
    @Override
    public void run() {
        if (!createAndShowDialog()) return;
        // Only export if selected
        if (export_only) {
            OpInEx ops = new OpInEx(qupath);
            ops.exportImageMaskPair(selectedImages, cropSelection, fgSelection);
            Dialogs.showConfirmDialog("Image/Mask paris exported", "The image/mask paris have been exported to\n " +
                    ops.getImages_dir() + "\n" + ops.getMasks_dir() + "\nrespectively.");
            return;
        }
        // Start exporting and training
        train();
    }

    /**
     * Show a dialog to choose on which images to train on..
     * based on QuPaths MeasurementExportCommand:
     * https://github.com/qupath/qupath/blob/main/qupath-gui-fx/src/main/java/qupath/lib/gui/commands/MeasurementExportCommand.java
     * @return
     */
    private boolean createAndShowDialog() {
        // Get the project
        project = qupath.getProject();
        if (project == null) {
            GuiTools.showNoProjectError(title);
            return false;
        }

        // get a list of annotation classes in the project
        ArrayList<String> allPathClassesList = new ArrayList<>();
        project.getPathClasses().forEach(c -> allPathClassesList.add(c.getName()));
        ArrayList<String> validPathClasses  = (ArrayList<String>) allPathClassesList.clone();
        validPathClasses.remove(null);

        // Build dialog
        BorderPane mainPane = new BorderPane();
        GridPane optionsPane = new GridPane();
        GridPane trainPane = new GridPane();
        optionsPane.setHgap(5.0);
        optionsPane.setVgap(5.0);
        BorderPane imageEntryPane = new BorderPane();

        // options pane     ----------------------------------------------------
        int row = 0;
        // First row, option to save image/mask paris only
        CheckBox cbExportOnly = new CheckBox("Only export image / mask pairs?");
        cbExportOnly.setTooltip(new Tooltip("No training, only exporting image / mask pairs"));
        cbExportOnly.setSelected(false);
        GridPaneUtils.addGridRow(optionsPane, row++, 0, "Option to only export image / mask pairs",
                cbExportOnly, cbExportOnly, cbExportOnly);

        // Second row, choosing the PathClass for image cropping
        Label cropChoiceLabel = new Label("Region for image cropping (optional)");
        pathClassCropCombo = new ComboBox<>();
        pathClassCropCombo.getItems().setAll(allPathClassesList);
        pathClassCropCombo.getSelectionModel().selectFirst();

        GridPaneUtils.addGridRow(optionsPane, row++, 0, "Select the kind of Annotation class used for image cropping.",
                cropChoiceLabel, cropChoiceLabel, cropChoiceLabel, pathClassCropCombo);

        // Third row, choosing the foreground path class
        Label fgChoiceLabel = new Label("Foreground Annotation class");
        pathClassFGCombo = new ComboBox<>();
        pathClassFGCombo.getItems().setAll(validPathClasses);
        pathClassFGCombo.getSelectionModel().selectFirst();


        GridPaneUtils.addGridRow(optionsPane, row++, 0, "Select the ind of Annotation class used for 'segmenting'/foreground.",
                fgChoiceLabel, fgChoiceLabel, fgChoiceLabel, pathClassFGCombo);

        // image (entry) selection pane ----------------------------------------
        String currentFGselection = pathClassFGCombo.getSelectionModel().getSelectedItem();
        availableImageList = getProjectImagesFilteredForAnno(currentFGselection);
        String sameImageWarning = "A selected image is open in the viewer!\nData should be saved before exporting.";
        var listSelectionView = ProjectDialogs.createImageChoicePane(qupath, availableImageList, previousImages, sameImageWarning);
        // add listener to en/dis-able the train button
        listSelectionView.getTargetItems().addListener(new ListChangeListener<ProjectImageEntry<BufferedImage>>() {
            @Override
            public void onChanged(Change<? extends ProjectImageEntry<BufferedImage>> change) {
                if (listSelectionView.getTargetItems().isEmpty()) {
                    dialog.getDialogPane().lookupButton(btnTrain).setDisable(true);
                } else dialog.getDialogPane().lookupButton(btnTrain).setDisable(false);
            }
        });


        // Add action when pathClass choice changes (cannot be before listSelectionView is defined)
        pathClassFGCombo.setOnAction(e -> {
            availableImageList = getProjectImagesFilteredForAnno(pathClassFGCombo.getSelectionModel().getSelectedItem());
            // Keep target items that have been selected before and have the annotation
            List<ProjectImageEntry<BufferedImage>> targetImages = new ArrayList<>();
            previousImages = listSelectionView.getTargetItems();
            if (previousImages != null) {
                previousImages.stream().forEach(img -> {
                    List<PathObject> prevAnnos = new ArrayList<>();
                    try {
                        prevAnnos = img.readHierarchy().getAnnotationObjects().stream().toList();
                    } catch (Exception ex) {
                        logger.error("Could not check annotations of already selected image: " + img.getImageName(), ex.getLocalizedMessage());
                    }
                    // add image to targetsImages, if it contains the Annotation class of interest
                    if (prevAnnos.stream().anyMatch(a -> a.getPathClass().equals(PathClass.getInstance(pathClassFGCombo.getSelectionModel().getSelectedItem())))) {
                        // add the image to targetImages
                        targetImages.add(img);
                        // remove the image from the availableImages list
                        if (availableImageList.contains(img)) availableImageList.remove(img);

                    }
                });
            }
            listSelectionView.getTargetItems().setAll(targetImages);
            listSelectionView.getSourceItems().setAll(availableImageList);
        });

        // Training option pane     --------------------------------------------
        row = 0;
        Label baseModelLabel = new Label("Select the EfficientNetV2 base model");
        baseModelCombo = new ComboBox<>();
        baseModelCombo.getItems().setAll(baseModelMap.keySet().stream().sorted().toList());
        baseModelCombo.getSelectionModel().selectFirst();
        GridPaneUtils.addGridRow(trainPane, row++, 0, "Select the EfficientNetV2 base model to use as a training backbone.",
                baseModelLabel, baseModelLabel, baseModelLabel, baseModelCombo);

        Label numEpochsLabel = new Label("Number of epochs");
        epochsField = new TextField("100");
        epochsField.setPrefColumnCount(4);
        numericField(epochsField);

        GridPaneUtils.addGridRow(trainPane, row++, 0, "Number of epochs to train for.",
                numEpochsLabel, numEpochsLabel, numEpochsLabel, epochsField);

        // Create and show the dialog       ------------------------------------
        FXUtils.getContentsOfType(optionsPane, Label.class, false).forEach(e -> e.setMinWidth(160));
        FXUtils.getContentsOfType(trainPane, Label.class, false).forEach(e -> e.setMinWidth(160));
        dialog = Dialogs.builder()
                .title(title)
                .buttons(btnTrain, ButtonType.CANCEL)
                .content(mainPane)
                .build();
        dialog.getDialogPane().setPrefSize(600, 400);

        imageEntryPane.setCenter(listSelectionView);

        mainPane.setTop(optionsPane);
        mainPane.setCenter(imageEntryPane);
        mainPane.setBottom(trainPane);

        // Start with disabled button
        dialog.getDialogPane().lookupButton(btnTrain).setDisable(true);

        Optional<ButtonType> result = dialog.showAndWait();

        //  ---------------------       Do actions      ------------------------
        // If dialog is cancelled
        if (!result.isPresent() || result.get() != btnTrain || result.get() == ButtonType.CANCEL) {
            return false;
        }

        // Get the dialog selections
        export_only = cbExportOnly.isSelected();
        cropSelection = pathClassCropCombo.getSelectionModel().getSelectedItem();
        fgSelection = pathClassFGCombo.getSelectionModel().getSelectedItem();
        baseModel = baseModelMap.get(baseModelCombo.getSelectionModel().getSelectedItem());
        selectedImages = listSelectionView.getTargetItems().stream().collect(Collectors.toList());
        // Set the epochs to 100 if empty string
        if (epochsField.getText().isEmpty()) {
            epochs = 100;
        } else epochs = Integer.parseInt(epochsField.getText());

        return true;

    } // end createAndShowDialog



    /**
     * Get a list of images that contain the desired Annotation class
     * @param pathClass: String name of the Annotation class
     * @return List of ProjectImageEntry
     */
    private List<ProjectImageEntry<BufferedImage>> getProjectImagesFilteredForAnno(String pathClass) {
        if (project == null) {
            logger.error("No project selected, but there should be one...");
        }
        // project should be defined, as this function is only called after defining it.
        List<ProjectImageEntry<BufferedImage>> all_image_list = project.getImageList();
        List<ProjectImageEntry<BufferedImage>> filtered_image_list = new ArrayList<>();

        all_image_list.forEach(e -> {
            try {
                Collection<PathObject> annos = e.readHierarchy().getAnnotationObjects();
                if (annos.stream().anyMatch(obj -> obj.getPathClass().getName().equals(pathClass))) {
                    filtered_image_list.add(e);
                }
            } catch (Exception ex) {
                logger.error("Exception in getProjectImagesFilteredForAnno - image = " + e.getImageName() + " -> " + ex);
            }
        });
        return filtered_image_list;
    }

    /**
     * Make a TextField numeric only.
     * @param field: TextField
     */
    public static void numericField(final TextField field) {
        field.setPrefColumnCount(4);
        field.textProperty().addListener(new ChangeListener<String>() {
            @Override
            public void changed(ObservableValue<? extends String> observable, String oldValue, String newValue) {
                if (!newValue.matches("\\d*")) {
                    field.setText(newValue.replaceAll("[^\\d]", ""));
                }
            }
        });
    }


    /**
     * Calls the corresponding train method, with the variables defined in the dialog
     */
    private void train() {
        train(cropSelection, fgSelection, baseModel, epochs, selectedImages);
    }


    /**
     * Exports the selected images (with optional cropping to crop_selection),
     * and trains a model.
     * @param crop_selection: String name of the annotation to use for cropping, can be null (-> full image)
     * @param fg_selection: String name of the ground truth annotation labelling
     * @param base_model: String name of the base model (b0, b1, b2, b3, s, m or l)
     * @param epochs: Integer number of epochs
     * @param selected_images: List of ProjectImageEntry to be exported and used for training
     */
    public void train(
            String crop_selection,
            String fg_selection,
            String base_model,
            int epochs,
            List<ProjectImageEntry<BufferedImage>> selected_images) {

        // Create task for training
        TrainTask worker = new TrainTask(crop_selection, fg_selection, base_model, epochs, selected_images);

        ProgressDialog progress = new ProgressDialog(worker);
        progress.initOwner(qupath.getStage());
        if (Platform.getCurrent() == Platform.OSX) progress.setTitle("Training... (May not work on OSX)");
        else progress.setTitle("Training...");
        progress.getDialogPane().setHeaderText("Training...\nImages will be saved to your project folder.");
        progress.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);
        progress.getDialogPane().setGraphic(null);
        progress.setWidth(600); // this does not work...
        progress.setHeight(400);
        progress.setResizable(true);

        // Actions on cancel
        progress.getDialogPane().lookupButton(ButtonType.CANCEL).addEventFilter(ActionEvent.ACTION, e -> {
            if (worker.error > 1) {
                progress.setHeaderText("Cancelling...");
                progress.getDialogPane().lookupButton(ButtonType.CANCEL).setDisable(true);
                worker.cancel(true);
            }
            // Fixme: if cancel, then some tasks might still run in the background.
            else if (Dialogs.showYesNoDialog("Cancel training?", "Are you sure you want to cancel the training?\nSome tasks might still run in the background...")) {
                progress.setHeaderText("Cancelling...");
                progress.getDialogPane().lookupButton(ButtonType.CANCEL).setDisable(true);
                worker.cancel(true);
            }
            e.consume();
        });

        // Create & run task
        runningTask.set(qupath.getThreadPoolManager().getSingleThreadExecutor(this).submit(worker));
        progress.showAndWait();
        // Throw errors
        if (worker.error != 1) {
            if (worker.error == 0) logger.info("Training was cancelled.");
            else if (worker.error == 2) {
                throw new RuntimeException("Not enough training images!\nYou need at least 3 training images to train the model.");
            }
            else if (worker.error == 3) {
                throw new RuntimeException("Data already split into train/val/test folders present!\n" +
                        "Please move images from sub-folders in 'yourProject/Efficient_V2_UNet/images' to the images folder and delete the sub-folders.\n" +
                        "Please move images from sub-folders in 'yourProject/Efficient_V2_UNet/masks' to the masks folder and delete the sub-folders.\n" +
                        "Then cancel and try again.");
            }
            else if (worker.error == 4) {
                throw new IllegalStateException("The EfficientV2UNet python path is empty. Please set it in Edit > Preferences.");
            }
            else if (worker.error == 5) {
                throw new RuntimeException("CLI execution/interruption error...");
            }
        }
        else {
            logger.info("Finished training!");
            OpInEx ops = worker.getOps();
            String path = "EfficientV2UNet_" + base_model + "_epochs" + epochs;
            path = ops.getTraining_root() + "/models/" + path;
            Dialogs.showConfirmDialog("Finished training!", "Your EfficientV2UNet models were saved to:\n" +
                    path + "\nImages were stored to:\n" + ops.getImages_dir() + " \nand\n" + ops.getMasks_dir());
            logger.trace("The EfficientV2UNet models were saved to:\n"  + path + "\nImages were stored to:\n" + ops.getImages_dir() + " \nand\n" + ops.getMasks_dir());
        }
    }

    class TrainTask extends Task<Void> {
        private String crop_selection;
        private String fg_selection;
        private String base_model;
        private Integer epochs;
        private List<ProjectImageEntry<BufferedImage>> selected_images;
        private OpInEx ops;
        private boolean quietCancel = false;
        private EV2UnetSetup setup = EV2UnetSetup.getInstance();
        private int error = 1; // 0 = cancelled, 1 = all fine, 2 = not enough training images,
                               // 3 = crop sub-folder present, 4 = invalid setup, 5 = other error

        /**
         * Constructor
         * @param crop_selection: String name of the annotation to use for cropping, can be null (-> full image)
         * @param fg_selection: String name of the ground truth annotation labelling
         * @param base_model: String name of the base model (b0, b1, b2, b3, s, m or l)
         * @param epochs: Integer number of epochs
         * @param selected_images: List of ProjectImageEntry to be exported and used for training
         */
        public TrainTask(
                String crop_selection,
                String fg_selection,
                String base_model,
                int epochs,
                List<ProjectImageEntry<BufferedImage>> selected_images) {
            this.crop_selection = crop_selection;
            this.fg_selection = fg_selection;
            this.base_model = base_model;
            this.epochs = epochs;
            this.selected_images = selected_images;
            this.ops = new OpInEx(qupath);
        }

        /**
         * Getter for OpInEx
         * @return OpInEx
         */
        public OpInEx getOps() {
            return ops;
        }

        @Override
        public boolean cancel(boolean b) {
            this.error = 0;
            return super.cancel(b);
        }

        public boolean dataAlreadySplit(String path) {
            if (new File(path, "train").exists()) return true;
            else if (new File(path, "train").exists()) return true;
            else if (new File(path, "train").exists()) return true;
            else return false;
        }

        private void read_metrics(File json_file) {
            // TODO implement, but it is not super necessary
        }

        @Override
        protected Void call() {

            // FIXME TEMP for testing the read_metrics() -> to show them at the end of the training.
            /*
            // try to show metrics
            File json_file = new File(ops.getTraining_root(), "models/EfficientV2UNet_"+base_model+"_epochs"+epochs+"/EfficientV2UNet_"+base_model+"_epochs"+epochs+".json");
            System.out.println("should be folder: " + json_file.getAbsolutePath());
            // temp overwriting with existing file
            json_file = new File(qupath.getProject().getPath().getParent().toFile(), "models/b3_Ntrain66_Nval14_Ntest14_epochs200/b3_Ntrain66_Nval14_Ntest14_epochs200.json");
            System.out.println("poject folder exists: " + json_file.exists() + ": " + json_file.getAbsolutePath());
            read_metrics(json_file);

            if (2==2) return null;
            */
            //FIXME TEMP

            // Check that the environment was set up properly
            if (setup.getEv2unetPythonPath().isEmpty()) {
                this.error = 4;
                updateMessage("The EfficientV2UNet python path is empty.\nPlease set it in\nEdit > Preferences.");
                try {
                    TimeUnit.SECONDS.sleep(30);
                } catch (InterruptedException e) {
                    this.error = 4;
                }
                return null;
            }
            long startTime = System.currentTimeMillis();
            int count = 0;
            // Export images to be trained
            updateProgress(count, 2);
            count++;
            updateMessage("Exporting images");

            // Check if data has already been split
            boolean image_subdir_exists = dataAlreadySplit(ops.getImages_dir());
            boolean mask_subdir_exists = dataAlreadySplit(ops.getMasks_dir());

            if (image_subdir_exists || mask_subdir_exists) {
                this.error = 3;
                String msg = "Training data has already been organised into\ntrain, validation and test sets.\n";
                if (image_subdir_exists) msg += "Please move the images in the 'train', 'val' & 'test'\nsub-folders to 'projectFolder/Efficient_V2_UNet/images'\nand delete the sub-folders.\n";
                if (mask_subdir_exists) msg += "Please move the masks in the 'train', 'val' & 'test'\nsub-folders to 'projectFolder/Efficient_V2_UNet/masks'\nand delete the sub-folders.\n";
                msg += "Then cancel and try again.";
                updateMessage(msg);
                try {
                    TimeUnit.MINUTES.sleep(1);
                } catch (InterruptedException e) {
                    this.error = 3;
                }
                return null;
            }

            ops.exportImageMaskPair(selected_images, crop_selection, fg_selection);
            // Check if there is enough tiffs and abort if not?
            List<File> availableTifFiles = ops.getTifFilesInFolder(ops.getImages_dir());
            if (availableTifFiles.size() < 3) {
                this.error = 2;
                updateMessage("Error: Not enough training images!\nYou need at least 3 training images.\nBut only " + availableTifFiles.size() + " are present.\nCancel and try again.");
                try {
                    TimeUnit.MINUTES.sleep(1);
                } catch (InterruptedException e) {
                    this.error = 2;
                }
                return null;
            }

            // start the training
            updateProgress(count, 2);
            count++;
            updateMessage("Training...");
            // Create Venv runner
            VirtualEnvironmentRunner venv = new VirtualEnvironmentRunner(
                    setup.getEv2unetPythonPath(), setup.getEnvtype(), this.getClass().getSimpleName()
            );
            // Create cli command
            List<String> args = new ArrayList<>(Arrays.asList("-W", "ignore", "-m", "efficient_v2_unet", "--train"));
            args.add("--images");
            args.add(ops.getImages_dir());
            args.add("--masks");
            args.add(ops.getMasks_dir());
            args.add("--basedir");
            args.add(ops.getTraining_root());
            args.add("--name");
            args.add("EfficientV2UNet_" + base_model + "_epochs" + epochs);
            args.add("--basemodel");
            args.add(base_model);
            args.add("--epochs");
            args.add(epochs.toString());
            venv.setArguments(args);

            // run the command
            try {
                venv.runCommand(false);
            } catch (IOException e) {
                this.error = 5;
                logger.error("Exception while running the CLI command:" + e.getMessage());
            }

            // Get the cli log...
            Process process = venv.getProcess();

            List<String> logResults = new ArrayList<>();
            Thread t = new Thread(Thread.currentThread().getName() + "-" + process.hashCode()) {
                @Override
                public void run() {
                    BufferedReader stdIn = new BufferedReader(new InputStreamReader(process.getInputStream()));
                    String message = "Training... \n";
                    if (Platform.getCurrent() == Platform.OSX) message = "Training... May not work on MacOS" + "\n";
                    try {
                        for (String line = stdIn.readLine(); line != null; ) {
                            logResults.add(line);
                            if (line.startsWith("Epoch")) message = "Training... " + line + "\n";
                            updateMessage(message + line);
                            line = stdIn.readLine();
                        }
                    } catch (IOException e) {
                        logger.warn(e.getMessage());
                    }
                }
            };
            t.setDaemon(true);
            t.start();

            // wait for the process to finish
            try {
                process.waitFor();
            } catch (InterruptedException e) {
                logger.error("CLI execution/interruption error: " + e);
                error = 5;
                return null;
            }

            updateProgress(count, 2);
            count++;
            updateMessage("Done!");

            long endTime = System.currentTimeMillis();
            logger.info("Training took " + (endTime - startTime) / 1000 + " seconds.");

            return null;
        }
    } // End TrainTask class

} // end class