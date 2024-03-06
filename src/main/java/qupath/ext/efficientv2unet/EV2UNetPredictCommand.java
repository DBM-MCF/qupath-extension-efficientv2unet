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
        System.out.println("-----> Started predict dialog");
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
        //validPathClasses.forEach(c -> System.out.println("valid ones: " +c));

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
                readModelMetadata();
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
        GridPaneUtils.addGridRow(optionsPane, row++, 0, "Select the resolution for the inference (2 = 1/2 original resolution, 3 = 1/3 original resolution)",
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
        System.out.println("-----> Finished predict dialog");
        String path = modelFilePath.getAbsolutePath();
        String anno_name = pathClassCombo.getSelectionModel().getSelectedItem();
        boolean doSplit = cbSplitROIs.isSelected();
        boolean doRemove = cbRemoveAnnos.isSelected();
        ObservableList<ProjectImageEntry<BufferedImage>> imageSelectionList = listSelectionView.getTargetItems();


        // FIXME prints
        System.out.println(path);
        System.out.println(anno_name);
        System.out.println("doSplit: " + doSplit);
        System.out.println("doRemove: " + doRemove);
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


        //new VirtualEnvironmentRunner() // this works now (had to restart the IDE)
        System.out.println("-----DONE----");

        // FIXME - QUESTION: should I export the images to predict to another folder (making sure they are not pyramidal)?


    } // end createAndShowDialog

    /**
     * This function reads the model metadata,
     * and displays information in the UI if found.
     */
    private void readModelMetadata() {
        // set the info label for the model name
        infoModelName.setText("Model: " + modelFilePath.getName());
        String strInfoModel = "\tModel parameters:\t\t\t";
        String strInfoModelBest = "\tBest checkpoint parameters:\t";

        // file if name happens to be the same as selected model
        File file_json = new File(modelFilePath.getParent(), modelFilePath.getName().replace(".h5", ".json"));
        // search for json files in folder
        File dir = new File(modelFilePath.getParent());
        Optional<File> file_found = Arrays.stream(dir.listFiles()).filter(f -> f.getName().endsWith(".json")).findFirst();

        // Check if same name file as json is present
        if (file_json.exists()) {
            System.out.println("found the json file immediately by replacing h5 with json");
            read_json(file_json);
        }
        else if (file_found.isPresent()) {
            // otherwise try to find any json
            System.out.println("found another json file");
            read_json(file_found.get()); // //FIXME this is found, i have to continue here.
        }
        else {
            System.out.println("no json file found");
            strInfoModel += "No metadata found";
            strInfoModelBest += "No metadata found";
        }

        infoModel.setText(strInfoModel);
        infoModelBest.setText(strInfoModelBest);

    }

    private void read_json(File json_file) {
        try {
            // Read file
            Gson gson = new Gson();
            JsonReader reader = new JsonReader(new FileReader(json_file));

            // convert the json file into a map
            Map<String, Object> stringObjectMap = gson.fromJson(reader, new TypeToken<Map<String, Object>>() {}.getType());
            stringObjectMap.keySet().forEach(k -> System.out.println(k + " : " + stringObjectMap.get(k)));
            // check if map contains the key 'test_metrics'
            if (!stringObjectMap.containsKey("test_metrics")) {
                System.out.println("found no test_metrics");
            }
            else System.out.println("found test_metrics");

        } catch (FileNotFoundException e) {
            logger.error("Could not find JSON file: " + e.getLocalizedMessage());
        }/* catch (IOException e) {
            logger.error("JSON IO exception: " + e.getLocalizedMessage());
        }/* catch (ParseException e) {
            logger.error("JSON parse error: " + e.getLocalizedMessage());
        }*/
    }


    // FIXME, do I really need this?
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


} // end class
