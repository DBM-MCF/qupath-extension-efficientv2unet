package qupath.ext.efficientv2unet;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.ListChangeListener;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import org.controlsfx.control.ListSelectionView;
import org.controlsfx.dialog.ProgressDialog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.fx.dialogs.Dialogs;
import qupath.fx.dialogs.FileChoosers;
import qupath.fx.utils.FXUtils;
import qupath.fx.utils.GridPaneUtils;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.dialogs.ProjectDialogs;
import qupath.lib.gui.tools.GuiTools;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.util.*;
import java.util.concurrent.Future;
import java.util.stream.Collectors;


public class EV2UNetPredictCommand implements Runnable{
    private QuPathGUI qupath;
    private String title = "Predict an image with Efficient V2 UNet";
    private static final Logger logger = LoggerFactory.getLogger(EV2UNetPredictCommand.class);
    private ObjectProperty<Future<?>> runningTask = new SimpleObjectProperty<>();
    private Project<BufferedImage> project;
    private ListSelectionView<ProjectImageEntry<BufferedImage>> listSelectionView;
    private List<ProjectImageEntry<BufferedImage>> availableImageList;

    // GUI components
    private ComboBox<String> pathClassCombo;
    private TextField modelFilePathField = new TextField();
    private File modelFilePath = null;
    private Dialog<ButtonType> dialog;
    private ButtonType btnPredict = new ButtonType("Predict", ButtonBar.ButtonData.OK_DONE);
    private Label infoModelName = new Label();
    private Label infoModel = new Label();
    private Label infoModelBest = new Label();

    // Prediction variables
    private String modelPath;
    private Double threshold;
    private Integer resolution;
    private String anno_name;
    private Boolean doSplit;
    private Boolean doRemove;
    private List<ProjectImageEntry<BufferedImage>> selectedImages = new ArrayList<>();

    /**
     * Constructor
     * @param qupath
     */
    public EV2UNetPredictCommand(final QuPathGUI qupath) {
        this.qupath = qupath;
    }

    @Override
    public void run() {
        if (!createAndShowDialog()) return;
        // Start image prediction (one by one)
        predictImages(); // shows a progress dialog
    }

    /**
     * Create and show a dialog for predicting images from a selection.
     * @return boolean: true if the dialog was not cancelled
     */
    private boolean createAndShowDialog() {
        // get the project
        project = qupath.getProject();
        if (project == null) {
            logger.error("No project selected");
            GuiTools.showNoProjectError("Please open a project first");
            return false;
        }

        // get a list of annotation classes in the project
        ArrayList<String> allPathClassesList = new ArrayList<>();
        project.getPathClasses().forEach(c -> allPathClassesList.add(c.getName()));
        ArrayList<String> validPathClasses  = (ArrayList<String>) allPathClassesList.clone();
        validPathClasses.remove(null);

        // Build dialog panes
        BorderPane mainPane = new BorderPane();
        GridPane optionsPane = new GridPane();
        optionsPane.setHgap(5.0);
        optionsPane.setVgap(5.0);
        GridPane infoPane = new GridPane();
        infoPane.setHgap(5.0);
        infoPane.setVgap(5.0);
        BorderPane imageEntryPane = new BorderPane();

        // Options pane     ----------------------------------------------------
        int row = 0;
        // Chooser for model file
        Label modelFilePathLabel = new Label("Path to model (.h5) file");
        Button btnChooseFile = new Button("Choose");
        btnChooseFile.setOnAction(e -> {
            modelFilePath = FileChoosers.promptForFile(title, FileChoosers.createExtensionFilter("File types", ".h5"));
            if (modelFilePath == null) {
                logger.error("Chosen file is null!");
                dialog.getDialogPane().lookupButton(btnPredict).setDisable(true);
                if (!modelFilePathField.getText().endsWith(".h5")) {
                    // reset the info text
                    infoModelName.setText("");
                    infoModel.setText("");
                    infoModelBest.setText("");
                }
            }
            else {
                modelFilePathField.setText(modelFilePath.getAbsolutePath());
                dialog.getDialogPane().lookupButton(btnPredict).setDisable(false);
                readModelMetadata(); // to set the info for the optimal model parameters
            }
            // deactivate the predict button if no image is selected
            if (listSelectionView.getTargetItems().isEmpty()) {
                dialog.getDialogPane().lookupButton(btnPredict).setDisable(true);
            }
        });
        modelFilePathLabel.setLabelFor(modelFilePathField);
        GridPaneUtils.addGridRow(optionsPane, row++, 0, "Path to the model (.h5) file",
                modelFilePathLabel, modelFilePathField, btnChooseFile);
        modelFilePathField.setMaxWidth(Double.MAX_VALUE);
        btnChooseFile.setMaxWidth(Double.MAX_VALUE);

        // Chooser for path class
        Label pathClassLabel = new Label("Assign to class");
        pathClassCombo = new ComboBox<>();
        pathClassCombo.getItems().setAll(validPathClasses);
        pathClassCombo.getSelectionModel().selectFirst();
        GridPaneUtils.addGridRow(optionsPane, row++, 0, "Select the Annotation-class to which the segmentation should be added",
                pathClassLabel, pathClassCombo);

        // Chooser for splitting ROIs
        CheckBox cbSplitROIs = new CheckBox("Split Annotations");
        cbSplitROIs.setTooltip(new Tooltip("Split the mask object into individual objects"));
        cbSplitROIs.setSelected(false);
        GridPaneUtils.addGridRow(optionsPane, row++, 0, "Split the mask object into individual objects",
                cbSplitROIs, cbSplitROIs, cbSplitROIs);

        // Chooser for removing existing ROIs
        CheckBox cbRemoveAnnos = new CheckBox("Remove existing Objects");
        cbRemoveAnnos.setTooltip(new Tooltip("Remove ALL existing Objects before adding the predicted objects"));
        cbRemoveAnnos.setSelected(false);
        GridPaneUtils.addGridRow(optionsPane, row++, 0, "Remove ALL existing Objects before adding the predicted objects",
                cbRemoveAnnos, cbRemoveAnnos, cbRemoveAnnos);

        // Slider for threshold
        Label thresholdLabel = new Label("Threshold");
        Slider thresholdSlider = new Slider(0.0, 1.0, 0.5);
        thresholdSlider.setBlockIncrement(0.05);
        thresholdSlider.setMajorTickUnit(0.1);
        thresholdSlider.setMinorTickCount(1);  // to allow only 0.05 steps
        thresholdSlider.setSnapToTicks(true); // rounds values to 0.05
        TextField tf = new TextField();
        tf.setPrefColumnCount(3);
        FXUtils.bindSliderAndTextField(thresholdSlider, tf, false);
        GuiTools.installRangePrompt(thresholdSlider); // adds mouse listener to slider
        GridPaneUtils.addGridRow(optionsPane, row++, 0, "Select a threshold for the predicted mask",
                thresholdLabel, thresholdSlider, tf);

        // Drop-down for resolution
        Label resolutionLabel = new Label("Inference resolution");
        ComboBox<String> resolutionCombo = new ComboBox<>();
        resolutionCombo.getItems().setAll("1", "2", "3");
        resolutionCombo.getSelectionModel().selectFirst();
        GridPaneUtils.addGridRow(optionsPane, row++, 0, "Select the resolution for the inference (1 = full resolution, 2 = 1/2 original resolution, 3 = 1/3 original resolution)",
                resolutionLabel, resolutionCombo);

        // Image entry pane     ------------------------------------------------
        availableImageList = project.getImageList();
        listSelectionView = ProjectDialogs.createImageChoicePane(qupath, availableImageList, selectedImages, null);
        // Enable predict button only if target item selected
        listSelectionView.getTargetItems().addListener(new ListChangeListener<ProjectImageEntry<BufferedImage>>() {
            @Override
            public void onChanged(Change<? extends ProjectImageEntry<BufferedImage>> change) {
                if (listSelectionView.getTargetItems().isEmpty()) {
                    dialog.getDialogPane().lookupButton(btnPredict).setDisable(true);
                }
                else if (modelFilePathField.getText().isEmpty() || modelFilePathField.getText() == null) {
                    dialog.getDialogPane().lookupButton(btnPredict).setDisable(true);
                }
                else dialog.getDialogPane().lookupButton(btnPredict).setDisable(false);
            }
        });

        // Info text-fields for suggested parameters
        GridPaneUtils.addGridRow(infoPane, 0, 0, "Name of the currently selected model", infoModelName);
        GridPaneUtils.addGridRow(infoPane, 1, 0, "Suggested parameters for the model", infoModel);
        GridPaneUtils.addGridRow(infoPane, 2, 0, "Suggested parameters for the best-checkpoint model", infoModelBest);


        // Create and show the dialog       ------------------------------------
        FXUtils.getContentsOfType(optionsPane, Label.class, false).forEach(e -> e.setMinWidth(160));
        dialog = Dialogs.builder()
                .title(title)
                .buttons(btnPredict, ButtonType.CANCEL)
                .content(mainPane)
                .build();
        dialog.getDialogPane().setPrefSize(600, 400);

        imageEntryPane.setCenter(listSelectionView);
        mainPane.setTop(optionsPane);
        mainPane.setCenter(infoPane);
        mainPane.setBottom(imageEntryPane);
        dialog.getDialogPane().lookupButton(btnPredict).setDisable(true); // disable predict button at start

        Optional<ButtonType> result = dialog.showAndWait();

        // Dialog readout and actions       ------------------------------------
        // If dialog is cancelled
        if (!result.isPresent() || result.get() != btnPredict || result.get() == ButtonType.CANCEL) {
            logger.warn("dialog was cancelled");
            return false;
        }

        // Get the dialog selections and set them to the class variables
        modelPath = modelFilePath.getAbsolutePath();
        anno_name = pathClassCombo.getSelectionModel().getSelectedItem();
        doSplit = cbSplitROIs.isSelected();
        doRemove = cbRemoveAnnos.isSelected();
        threshold = thresholdSlider.getValue();
        resolution = Integer.parseInt(resolutionCombo.getSelectionModel().getSelectedItem());
        selectedImages = listSelectionView.getTargetItems().stream().collect(Collectors.toList());
        return true;

    } // end createAndShowDialog

    /**
     * calls the corresponding public method, with the variables defined in the dialog
     */
    private void predictImages() {
        predictImages(modelPath, threshold, resolution, selectedImages, anno_name, doSplit, doRemove);
    }

    /**
     * Saves the images to be predicted as tif files into the temp folder.
     * Calls the predict CLI, which saves the predictions into the predictions folder.
     * @param model_path: String path to the model h5 file
     * @param thresh: Double threshold for prediction
     * @param res: Integer resolution to perform the prediction on, e.g. 1, 2, 3...
     * @param images: List of ProjectImageEntry that need to be predicted
     */
    public void predictImages(
            String model_path,
            Double thresh,
            Integer res,
            List<ProjectImageEntry<BufferedImage>> images,
            String annotationClassName,
            boolean splitObject,
            boolean removeExistingAnnotations) {

        // create OPs object
        OpInEx opInEx = new OpInEx(qupath);

        // Predict using the builder
        var builder = EfficientV2UNet
                .builder()
                .doPredict(true)
                .setModelPath(model_path)
                .setTempDir(opInEx.getTemp_dir())
                .setPredictOutputDirectory(opInEx.getPrediction_dir())
                .setResolution(res)
                .setThreshold(thresh)
                .setUseLessMemory(true)
                .setAnnotationClassName(annotationClassName)
                .doSplitObject(splitObject)
                .doRemoveExistingAnnotations(removeExistingAnnotations)
                .build();

        // Create task for predicting (with custom class below)
        PredictTask worker = new PredictTask(builder, images, annotationClassName, splitObject, removeExistingAnnotations, opInEx);

        ProgressDialog progress = new ProgressDialog(worker);
        progress.setWidth(600);
        progress.initOwner(qupath.getStage());
        progress.setTitle("Predicting...");
        progress.getDialogPane().setHeaderText("Predicting...");
        progress.getDialogPane().setGraphic(null);
        progress.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);
        progress.getDialogPane().lookupButton(ButtonType.CANCEL).addEventFilter(ActionEvent.ACTION, e -> {
            if (Dialogs.showYesNoDialog("Cancel prediction?", "Are you sure you want to cancel the prediction?\nBecause it probably won't work...")) {
                progress.setHeaderText("Cancelling...");
                progress.getDialogPane().lookupButton(ButtonType.CANCEL).setDisable(true);
                worker.cancel(true);
            }
            e.consume();
        });

        // create & run task
        runningTask.set(qupath.getThreadPoolManager().getSingleThreadExecutor(this).submit(worker));
        progress.show();
    }


    /**
     * This function reads the model metadata,
     * and displays information in the UI if found.
     */
    private void readModelMetadata() {
        // set the info label for the model name
        infoModelName.setText("Model: " + modelFilePath.getName());
        String strInfoModel = "-> Model parameters:\t\t\t\t";
        String strInfoModelBest = "-> Best checkpoint model parameters:\t";

        // file if name happens to be the same as selected model
        File file_json = new File(modelFilePath.getParent(), modelFilePath.getName().replace(".h5", ".json"));
        // search for json files in folder
        File dir = new File(modelFilePath.getParent());
        Optional<File> file_found = Arrays.stream(dir.listFiles()).filter(f -> f.getName().endsWith(".json")).findFirst();

        // Check if same name file as json is present
        String[] metadata;
        if (file_json.exists()) {
            metadata = read_json(file_json);
            strInfoModel += "Threshold = " + metadata[0] + "; Resolution = " + metadata[1];
            strInfoModelBest += "Threshold = " + metadata[2] + "; Resolution = " + metadata[3];
        } else if (file_found.isPresent()) {
            // otherwise try to find any json
            metadata = read_json(file_found.get());
            strInfoModel += "Threshold = " + metadata[0] + "; Resolution = " + metadata[1];
            strInfoModelBest += "Threshold = " + metadata[2] + "; Resolution = " + metadata[3];
        } else {
            // else no json-file found
            strInfoModel += "No metadata json-file found";
            strInfoModelBest += "No metadata json-file found";
        }
        infoModel.setText(strInfoModel);
        infoModelBest.setText(strInfoModelBest);
    }

    /**
     * Reads a json file, to find the best metrics metadata
     * @param json_file
     * @return String[] with 4 values [normal_thresh, normal_res, best_thresh, best_res]
     *      also if the (wrong) json file does not contain the needed info,
     *      the function returns an array with "No metadata found"-values
     */
    private String[] read_json(File json_file) {
        // String array for read metrics [normal_thresh, normal_res, best_thresh, best_res]
        String[] resultArray = {"No metadata found", "", "No metadata found", ""};
        try {
            // Read file
            Gson gson = new Gson();
            JsonReader reader = new JsonReader(new FileReader(json_file));
            // convert the json file into a map
            Map<String, Object> stringObjectMap = gson.fromJson(reader, new TypeToken<Map<String, Object>>() {}.getType());

            // check if map contains the key 'test_metrics'
            if (!stringObjectMap.containsKey("test_metrics")) {
                logger.info("No test_metrics found in json file: " + json_file.getAbsolutePath());
            }
            else {
                // find the values - get the test_metrics dictionary
                Map<String, Map<String, Map<String, Object>>> test_metrics = (Map<String, Map<String, Map<String, Object>>>) stringObjectMap.get("test_metrics");
                test_metrics.keySet().forEach(k -> {
                    // for 'normal' and 'best-ckp' model
                    if (k.endsWith("best-ckp.h5")) {
                        // best-ckp model
                        String res = test_metrics.get(k).get("best_binary_iou_parameters").get("best_resolution").toString();
                        res = res.substring(res.length() - 1);
                        resultArray[3] = res;
                        resultArray[2] = test_metrics.get(k).get("best_binary_iou_parameters").get("best_threshold").toString();
                    } else if (k.endsWith(".h5")) {
                        // 'normal' model
                        String res = test_metrics.get(k).get("best_binary_iou_parameters").get("best_resolution").toString();
                        res = res.substring(res.length() - 1);
                        resultArray[1] = res;
                        resultArray[0] = test_metrics.get(k).get("best_binary_iou_parameters").get("best_threshold").toString();
                    }
                    else logger.trace("found key for model name without .h5-ending: " + k);
                });
            }

        } catch (FileNotFoundException e) {
            logger.error("Could not find JSON file: " + e.getLocalizedMessage());
        } catch (Exception e) {
            logger.error("Error during reading of the JSON file: " + e.getLocalizedMessage());
        }
        return resultArray;
    }

    /**
     * Task class for predicting images.
     *
     */
   class PredictTask extends Task<Void> {
        private EfficientV2UNet builder;
        private List<ProjectImageEntry<BufferedImage>> imagesToPredict;
        private String annotationClassName;
        private boolean splitAnnotations;
        private boolean removeExistingAnnotations;
        private OpInEx ops;
        private boolean quietCancel = false;

        /**
         * Constructor
         * @param builder: EfficientV2UNet builder
         * @param imagesToPredict: List of ProjectImageEntry of images to predict
         * @param annotationClassName: String for the annotation class
         * @param splitAnnotations: boolean specifying whether to split the new objects
         * @param removeExistingAnnotations: boolean specifying whether to remove existing annotations
         * @param ops: OpInEx object
         */
        public PredictTask(EfficientV2UNet builder, List<ProjectImageEntry<BufferedImage>> imagesToPredict,
                           String annotationClassName, boolean splitAnnotations, boolean removeExistingAnnotations, OpInEx ops) {
            this.builder = builder;
            this.imagesToPredict = imagesToPredict;
            this.annotationClassName = annotationClassName;
            this.splitAnnotations = splitAnnotations;
            this.removeExistingAnnotations = removeExistingAnnotations;
            this.ops = ops;
        }

       /**
        * Called when task is started
        * @return null
        */
        @Override
       protected Void call() {
            long startTime = System.currentTimeMillis();
            int count = 0;
            // Export the images that need to be predicted
            updateProgress(count, 4);
            count++;
            updateMessage("Exporting images...");
            ArrayList<File> tempFiles = ops.exportTempImages(imagesToPredict);
            logger.info("Exported temp images.");

            // start the prediction
            updateProgress(count, 4);
            count++;
            updateMessage("Predicting images...");
            builder.doPredict();
            logger.info("Predicted images.");

            // load the masks
            updateProgress(count, 4);
            count++;
            updateMessage("Loading predictions.");
            Map<Integer, String> label_name_map = Map.ofEntries(Map.entry(1, annotationClassName)); // map of label id to annotatin class name
            ops.batch_load_maskFiles(ops.getPredictionFiles(), imagesToPredict, splitAnnotations, removeExistingAnnotations, label_name_map);
            logger.info("Loaded predictions.");
            // delete the temp files
            updateProgress(count, 4);
            count++;
            updateMessage("Deleting temp files...");
            ops.deleteTempFiles();
            ops.deletePredictionFiles(ops.getPredictionFiles());
            logger.info("Deleted temp files.");

            long endTime = System.currentTimeMillis();
            logger.info("Predict took " + (endTime - startTime) / 1000 + " seconds.");
            return null;
        }

   }

} // end (main) class
