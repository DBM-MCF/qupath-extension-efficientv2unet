package qupath.ext.efficientv2unet;

import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import org.controlsfx.control.ListSelectionView;
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
import java.util.*;
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
    private static final  Map<String, String> baseModelMap = Map.of("EfficientNetV2-B0", "b0", "EfficientNetV2-B1", "b1", "EfficientNetV2-B2", "b2", "EfficientNetV2-B3", "b3","EfficientNetV2-S", "s", "EfficientNetV2-M", "m","EfficientNetV2-L", "l");

    // Class variables
    private Project<BufferedImage> project;
    private ListSelectionView<ProjectImageEntry<BufferedImage>> listSelectionView;
    private List<ProjectImageEntry<BufferedImage>> previousImages = new ArrayList<>();

    // onw class variables
    List<ProjectImageEntry<BufferedImage>> availableImageList = new ArrayList<>();


    // GUI
    private Dialog<ButtonType> dialog = null;
    // own GUI elements
    private ComboBox<String> pathClassCropCombo;
    private ComboBox<String> pathClassFGCombo;
    private ButtonType btnTrain = new ButtonType("Export & Train", ButtonBar.ButtonData.OK_DONE);
    private ComboBox<String> baseModelCombo;
    private TextField epochsField;




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
        createAndShowDialog();
    }

    /**
     * Show a dialog to choose on which images to train on
     * based on QuPaths MeasurementExportCommand:
     * https://github.com/qupath/qupath/blob/main/qupath-gui-fx/src/main/java/qupath/lib/gui/commands/MeasurementExportCommand.java
     */
    private void createAndShowDialog() {
        // Get the project
        project = qupath.getProject();
        if (project == null) {
            GuiTools.showNoProjectError(title);
            return;
        }

        // get a list of annotation classes in the project
        ArrayList<String> allPathClassesList = new ArrayList<>();
        project.getPathClasses().forEach(c -> allPathClassesList.add(c.getName()));
        ArrayList<String> validPathClasses  = (ArrayList<String>) allPathClassesList.clone();
        validPathClasses.remove(null);
        //validPathClasses.forEach(c -> System.out.println("valid ones: " +c));

        // Build dialog
        BorderPane mainPane = new BorderPane();
        GridPane optionsPane = new GridPane();
        GridPane trainPane = new GridPane();
        optionsPane.setHgap(5.0);
        optionsPane.setVgap(5.0);
        BorderPane imageEntryPane = new BorderPane();

        // options pane     ----------------------------------------------------
        int row = 0;
        // First row, choosing the PathClass for image cropping
        Label cropChoiceLabel = new Label("Region for image cropping (optional)");
        pathClassCropCombo = new ComboBox<>();
        pathClassCropCombo.getItems().setAll(allPathClassesList);
        pathClassCropCombo.getSelectionModel().selectFirst();

        GridPaneUtils.addGridRow(optionsPane, row++, 0, "Select the kind of Annotation class used for image cropping.",
                cropChoiceLabel, cropChoiceLabel, cropChoiceLabel, pathClassCropCombo);

        // Second row, choosing the foreground path class
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
        Label baseModelLabel = new Label("Select an EfficientNetV2 base model");
        baseModelCombo = new ComboBox<>();
        baseModelCombo.getItems().setAll(baseModelMap.keySet().stream().sorted().toList());
        baseModelCombo.getSelectionModel().selectFirst();
        GridPaneUtils.addGridRow(trainPane, row++, 0, "Select an EfficientNetV2 base model to use as a training backbone.",
                baseModelLabel, baseModelLabel, baseModelLabel, baseModelCombo);

        Label numEpochsLabel = new Label("Number of epochs");
        epochsField = new TextField("50");
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

        Optional<ButtonType> result = dialog.showAndWait();

        //  ---------------------       Do actions      ------------------------
        // If dialog is cancelled
        if (!result.isPresent() || result.get() != btnTrain || result.get() == ButtonType.CANCEL) {
            return;
        }

        // Get the dialog selections
        String cropSelection = pathClassCropCombo.getSelectionModel().getSelectedItem();
        String fgSelection = pathClassFGCombo.getSelectionModel().getSelectedItem();
        String modelChoice = baseModelMap.get(baseModelCombo.getSelectionModel().getSelectedItem());
        int epochs;
        try {
            epochs = Integer.parseInt(epochsField.getText());
        } catch (Exception ex) {
            throw new RuntimeException("Could not parse " + epochsField.getText() + " to an integer.", ex);
        }

        // FIXME these prints...

        System.out.println("Model choice: " + modelChoice);
        System.out.println("Epochs: " + epochs);

        // get the list of selected images
        List<ProjectImageEntry<BufferedImage>> selectedImages = listSelectionView.getTargetItems().stream().collect(Collectors.toList());
        if (selectedImages.isEmpty()) {
            logger.warn("No images were selected for exporting and training.");
            return;
        }

        OpInEx op = new OpInEx(qupath);
        op.export_images(selectedImages, cropSelection, fgSelection);

        // TODO: start the training

    } // end createAndShowDialog


    // FIXME, this method should probably be private (once it is not in the menu anymore...)
    public void runEnv() {
        VirtualEnvironmentRunner venv;
        EV2UnetSetup ev2UnetSetup = EV2UnetSetup.getInstance();

        if (ev2UnetSetup.getEv2unetPythonPath().isEmpty()) {
            throw new IllegalStateException("The EfficientV2UNet python path is empty. Please set it in Edit > Preferences.");
        }

        // create the venv
        venv = new VirtualEnvironmentRunner(ev2UnetSetup.getEv2unetPythonPath(), VirtualEnvironmentRunner.EnvType.EXE, this.getClass().getSimpleName());


        // create command, example: start napari
        String runCommand;
        List<String> args = new ArrayList<>(Arrays.asList("-W", "ignore", "-m"));
        args.add("napari");

        venv.setArguments(args);
        String[] theLog = new String[0];
        try {
            //theLog = venv.runCommand(); // this was modified in version 0.9.2
            venv.runCommand(); // now this function has no return
        } catch (Exception ex) {
            System.out.println("Exception in runEnv: " + ex.getLocalizedMessage());
        }
        logger.info("python command finished");

    }


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

} // end class