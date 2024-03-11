package qupath.ext.efficientv2unet;



import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.collections.ObservableList;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import org.controlsfx.control.ListSelectionView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.biop.cmd.VirtualEnvironmentRunner;
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
import java.io.IOException;
import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;

// TODO:
//      - add threshold
//      - add resolution
//      - make a builder?
//      - add text filed that populates once model is selected, showing optimal
//          threshold and resolution for best and normal model


public class EV2UNetPredictCommand implements Runnable{
    private QuPathGUI qupath;
    private String title = "Predict an image with Efficient V2 UNet";
    private static final Logger logger = LoggerFactory.getLogger(EV2UNetPredictCommand.class);
    private Project<BufferedImage> project;
    private ListSelectionView<ProjectImageEntry<BufferedImage>> listSelectionView;
    private List<ProjectImageEntry<BufferedImage>> availableImageList;
    private List<ProjectImageEntry<BufferedImage>> previousImages = new ArrayList<>();

    // GUI components
    private ComboBox<String> pathClassCombo;
    private TextField modelFilePathField = new TextField();
    private File modelFilePath = null;
    private Dialog<ButtonType> dialog;
    private ButtonType btnPredict = new ButtonType("Predict", ButtonBar.ButtonData.OK_DONE);
    private Label infoModelName = new Label();
    private Label infoModel = new Label();
    private Label infoModelBest = new Label();



    /**
     * Constructor
     * @param qupath
     */
    public EV2UNetPredictCommand(final QuPathGUI qupath) {
        this.qupath = qupath;
    }

    @Override
    public void run() {
        createAndShowDialog();
    }

    public void createAndShowDialog() {
        // get the project
        project = qupath.getProject();
        if (project == null) {
            logger.error("No project selected");
            GuiTools.showNoProjectError("Please open a project first");
            return;
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
        cbRemoveAnnos.setTooltip(new Tooltip("Remove all existing Objects before adding the predicted objects"));
        cbRemoveAnnos.setSelected(false);
        GridPaneUtils.addGridRow(optionsPane, row++, 0, "Remove all existing Objects before adding the predicted objects",
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
        listSelectionView = ProjectDialogs.createImageChoicePane(qupath, availableImageList, previousImages, null);


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
            return;
        }

        // Get the dialog selections
        String modelPath = modelFilePath.getAbsolutePath();
        String anno_name = pathClassCombo.getSelectionModel().getSelectedItem();
        boolean doSplit = cbSplitROIs.isSelected();
        boolean doRemove = cbRemoveAnnos.isSelected();
        double threshold = thresholdSlider.getValue();
        int resolution = Integer.parseInt(resolutionCombo.getSelectionModel().getSelectedItem());
        List<ProjectImageEntry<BufferedImage>> imageSelectionList = listSelectionView.getTargetItems().stream().collect(Collectors.toList());


        // FIXME prints
        System.out.println(modelPath);
        System.out.println(anno_name);
        System.out.println("doSplit: " + doSplit);
        System.out.println("doRemove: " + doRemove);
        System.out.println("threshold: " + threshold);
        System.out.println("resolution: " + resolution);


        imageSelectionList.forEach(i -> {
            // get the image file path
            //Collection<URI> uri = null;
            List<URI> uri = null;
            try {
                uri = i.getURIs().stream().collect(Collectors.toList());
            } catch (Exception e) {
                logger.error("Error: could not get URI for image: " + i.getImageName() + " >> " + e.getLocalizedMessage());
            }
            if (uri.size() > 1) {
                throw new RuntimeException("Error: more than one URI found for image: " + i.getImageName());
            }

            // otherwise it should be fine. problem is that i have to loop over the URI collection, i cannot just get the first item
            System.out.println(uri.get(0).getPath());

        });
        // Start image prediction (one by one)
        predictImages(modelPath, threshold, resolution, imageSelectionList, anno_name, doSplit, doRemove);
        //new VirtualEnvironmentRunner() // this works now (had to restart the IDE)
        System.out.println("-----DONE----");

        // FIXME - QUESTION: should I export the images to predict to another folder (making sure they are not pyramidal)?


    } // end createAndShowDialog

    public void predictImages(
            String modelPath,
            Double threshold,
            Integer resolution,
            List<ProjectImageEntry<BufferedImage>> images,
            String anno_name,
            boolean doSplit,
            boolean doRemove) {

        // TODO procedure:
        /**
         * For each image, save as tif into a subfolder (with the original name?)
         *  - map the project entry to the path (or expected output path?)
         * run the predict cli on the folder
         * delete the temp files (raw images)
         * import the masks
         *
         * --> have a status bar, as this may take long?!
         *      - or just a window pop up that says it is working...
         *
         */

        OpInEx opInEx = new OpInEx(qupath);
        // Export the images to the temp folder
        ArrayList<File> temp_files = opInEx.exportTempImages(images);
        System.out.println("exported temp images");


        // Run the predict CLI
        VirtualEnvironmentRunner venv;
        EV2UnetSetup ev2UnetSetup = EV2UnetSetup.getInstance();

        if (ev2UnetSetup.getEv2unetPythonPath().isEmpty()) {
            throw new IllegalStateException("The EfficientV2UNet python path is empty. Please set it in Edit > Preferences.");
        }

        venv = new VirtualEnvironmentRunner(ev2UnetSetup.getEv2unetPythonPath(), VirtualEnvironmentRunner.EnvType.EXE, this.getClass().getSimpleName());
        List<String> args = new ArrayList<>(Arrays.asList("-W", "ignore", "-m", "efficient_v2_unet", "--predict"));
        args.add("--dir");
        args.add(opInEx.getTemp_dir());
        args.add("--model");
        args.add(modelPath);
        args.add("--resolution");
        args.add(resolution.toString());
        args.add("--threshold");
        args.add(threshold.toString());
        args.add("--savedir");
        args.add(opInEx.getPrediction_dir());
        args.add("--use_less_memory");

        venv.setArguments(args);
        try {
            venv.runCommand();
        } catch (Exception ex) {
            logger.error("Exception while running CLI command: ");
        }
        // FIXME instead of waitfor have a window pop up that says it is working...
        //     with maybe while (venv.getProcess().isAlive()) ??
        try {
            venv.getProcess().waitFor();
        } catch (InterruptedException e) {
            throw new RuntimeException("CLI execution/interrupt error: " + e);
        }
        // FIXME Get the log, not really necessary...
        List<String> log = venv.getProcessLog();
        System.out.println("Process log: ");
        for (String line : log) {
            if (line != "") {
                System.out.println("\t" + line);
            }
        }

        // Delete the temp files
        opInEx.deleteTempFiles();
        System.out.println("deleted temp files");

        /*
        // iterate over images
        images.forEach(i -> {
            String imageName = i.getImageName();
            List<URI> uri = null;
            try {
                uri = i.getURIs().stream().collect(Collectors.toList());
                if (uri.size() > 1) throw new RuntimeException("Error: image " + imageName + " has more than one URI! (currently not supported)");
            } catch (IOException ex) {
                logger.error("Error: could not get image path for image: " + imageName + " >> " + ex.getLocalizedMessage());
            }
            // process image if uri is not null
            if (uri != null) {
                String imagePath = uri.get(0).getPath();

            }
        });
        */
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
                System.out.println("found no test_metrics");
                logger.trace("No test_metrics found in json file: " + json_file.getAbsolutePath());
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



} // end class
