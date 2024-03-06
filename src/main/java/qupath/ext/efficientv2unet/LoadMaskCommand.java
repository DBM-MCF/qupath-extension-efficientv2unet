package qupath.ext.efficientv2unet;

import ij.IJ;
import ij.ImagePlus;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.paint.Color;
import javafx.scene.text.TextAlignment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.fx.dialogs.Dialogs;
import qupath.fx.dialogs.FileChoosers;
import qupath.fx.utils.FXUtils;
import qupath.fx.utils.GridPaneUtils;
import qupath.imagej.tools.PixelImageIJ;
import qupath.lib.analysis.images.ContourTracing;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.tools.GuiTools;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.RoiTools;
import qupath.lib.roi.interfaces.ROI;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public class LoadMaskCommand implements Runnable{

    private QuPathGUI qupath;
    private static final Logger logger = LoggerFactory.getLogger(LoadMaskCommand.class);
    private String title = "Load a Mask";
    private Project<BufferedImage> project;
    private String type; // Annotation, Detection, etc. FIXME not implemented yet
    private File filePath = null;
    private ImagePlus img_mask;

    // GUI
    private Dialog<ButtonType> dialog;
    private ComboBox<String> pathClassCombo;
    private TextField filePathField = new TextField();
    private ComboBox<String> imageSelectionCombo;
    private ButtonType btnLoad = new ButtonType("Load mask", ButtonBar.ButtonData.OK_DONE);
    private CheckBox cbRemoveAnnos;
    private CheckBox cbSplitAnnos;


    /**
     * Constructor
     * @param qupath
     */
    public LoadMaskCommand(final QuPathGUI qupath) {
        this.qupath = qupath;
    }

    /**
     * Main run method
     */
    @Override
    public void run() {
        loadMaskDialog();
    }

    /**
     * Create a dialog to load a mask image
     */
    private void loadMaskDialog() {
        // load the current project
        project = qupath.getProject();
        if (project == null) {
            GuiTools.showNoProjectError(title);
            return;
        }

        // Pane
        BorderPane mainPane = new BorderPane();
        GridPane optionsPane = new GridPane();
        optionsPane.setHgap(5.0);
        optionsPane.setVgap(5.0);
        Label warningLabel = new Label("Selected image is not a binary image (only 0's and 1's)!");
        Label warningImageOpen = new Label("This image is open. Consider closing it!");


        // Options pane         ------------------------------------------------
        int row = 0;
        // File choosing
        Label filePathLabel = new Label("Choose an image file:");
        Button btnChooseFile = new Button("Choose");
        btnChooseFile.setOnAction(e -> {
            filePath = FileChoosers.promptForFile(title, FileChoosers.createExtensionFilter("File types", ".tif", ".png"));
            if (filePath == null) {
                logger.error("File is null!");
            }
            else {
                filePathField.setText(filePath.getAbsolutePath());
                try {
                     img_mask = IJ.openImage(filePath.getAbsolutePath());
                    double maxintensity = img_mask.getProcessor().getStatistics().max;
                    if (maxintensity > 1) {
                        warningLabel.setVisible(true);
                        dialog.getDialogPane().lookupButton(btnLoad).setDisable(true);
                    }
                    else {
                        warningLabel.setVisible(false);
                        dialog.getDialogPane().lookupButton(btnLoad).setDisable(false);
                    }
                } catch (Exception ex) {
                    logger.error("could not open image", ex);
                    throw new RuntimeException("could not open image", ex);
                }
            }

        });
        filePathLabel.setLabelFor(filePathField);
        GridPaneUtils.addGridRow(optionsPane, row++, 0, "Select the mask image file", filePathLabel, filePathField, btnChooseFile);
        filePathField.setMaxWidth(Double.MAX_VALUE);
        btnChooseFile.setMaxWidth(Double.MAX_VALUE);

        // Create a warning label to show if needed
        warningLabel.setTextFill(Color.RED);
        warningLabel.setMaxWidth(Double.MAX_VALUE);
        warningLabel.setMinHeight(Label.USE_PREF_SIZE);
        warningLabel.setTextAlignment(TextAlignment.CENTER);
        warningLabel.setAlignment(Pos.CENTER);
        warningLabel.setVisible(false);
        GridPaneUtils.setHGrowPriority(Priority.ALWAYS, warningLabel);
        GridPaneUtils.setFillWidth(Boolean.TRUE, warningLabel);
        GridPaneUtils.addGridRow(optionsPane, row++, 0, "Not a mask!", warningLabel, warningLabel, warningLabel);


        // ClassPath selection
        Label classPathLabel = new Label("Select a class for the mask");
        // Create combobox with available PathClasses
        ArrayList<String> pathClasses = new ArrayList<>();
        project.getPathClasses().forEach(c -> pathClasses.add(c.getName()));
        pathClasses.remove(null); // remove default (null) PathClass
        // populate combobox
        pathClassCombo = new ComboBox<>();
        pathClassCombo.getItems().setAll(pathClasses);
        pathClassCombo.getSelectionModel().selectFirst();
        GridPaneUtils.addGridRow(optionsPane, row++, 0, "Select to which class the mask should be added", classPathLabel, pathClassCombo);


        // Image selection (make it simple as a combobox)
        Label imageSelectionLabel = new Label("Select the image:");
        ArrayList<String> imageNameList = new ArrayList<>();
        project.getImageList().forEach(e -> imageNameList.add(e.getImageName()));
        imageSelectionCombo = new ComboBox<>();
        imageSelectionCombo.getItems().setAll(imageNameList);
        imageSelectionCombo.getSelectionModel().selectFirst();
        // add warning to close image first if it is opened
        imageSelectionCombo.setOnAction(e -> {
            var currentImages = qupath.getAllViewers().stream().map(v -> {
                        var imageData = v.getImageData();
                        return imageData == null ? null : qupath.getProject().getEntry(imageData);
                    })
                    .filter(d -> d != null)
                    .collect(Collectors.toSet());
            if (currentImages != null) {
                ProjectImageEntry<BufferedImage> target = project.getImageList().stream().filter(l -> l.getImageName().equals(imageSelectionCombo.getSelectionModel().getSelectedItem())).findFirst().orElse(null);
                if (currentImages.contains(target)) warningImageOpen.setVisible(true);
                else warningImageOpen.setVisible(false);
            }
        });

        GridPaneUtils.addGridRow(optionsPane, row++, 0, "Select the image to which the mask should be added as annotation",
                imageSelectionLabel, imageSelectionCombo);

        // Create warning if the image is currently open
        warningImageOpen.setTextFill(Color.RED);
        warningImageOpen.setMaxWidth(Double.MAX_VALUE);
        warningImageOpen.setMinHeight(Label.USE_PREF_SIZE);
        warningImageOpen.setTextAlignment(TextAlignment.CENTER);
        warningImageOpen.setAlignment(Pos.CENTER);
        warningImageOpen.setVisible(false);
        GridPaneUtils.setHGrowPriority(Priority.ALWAYS, warningImageOpen);
        GridPaneUtils.setFillWidth(Boolean.TRUE, warningImageOpen);
        GridPaneUtils.addGridRow(optionsPane, row++, 0, "Image is open. Consider closing it!", warningImageOpen, warningImageOpen, warningImageOpen);

        // Add checkbox for splitting annotations and checkbox for deleting existing annotations
        cbSplitAnnos = new CheckBox("Split Annotations");
        cbSplitAnnos.setTooltip(new Tooltip("Split the mask object into individual objects."));
        cbSplitAnnos.setSelected(false);
        GridPaneUtils.addGridRow(optionsPane, row++, 0,"Split the mask object into individual objects", cbSplitAnnos, cbSplitAnnos, cbSplitAnnos);

        cbRemoveAnnos = new CheckBox("Remove existing Objects");
        cbRemoveAnnos.setTooltip(new Tooltip("Remove all existing Objects before adding the ones from the mask"));
        cbRemoveAnnos.setSelected(false);
        GridPaneUtils.addGridRow(optionsPane, row++, 0,"Remove all existing Objects before adding the ones from the mask", cbRemoveAnnos, cbRemoveAnnos, cbRemoveAnnos);

        // Create and show the dialog       ------------------------------------
        FXUtils.getContentsOfType(optionsPane, Label.class, true).forEach(e -> e.setMinWidth(160));
        dialog = Dialogs.builder()
                .title(title)
                .buttons(btnLoad, ButtonType.CANCEL)
                .content(mainPane)
                .build();
        dialog.getDialogPane().setPrefSize(600, 200);

        mainPane.setCenter(optionsPane);
        dialog.getDialogPane().lookupButton(btnLoad).setDisable(true); // disable button at start


        Optional<ButtonType> result = dialog.showAndWait();

        //  ---------------------       Do actions      ------------------------
        // If dialog is cancelled
        if (!result.isPresent() || result.get() != btnLoad || result.get() == ButtonType.CANCEL) {
            return;
        }

        // get the dialog selections
        String anno_class = pathClassCombo.getSelectionModel().getSelectedItem();
        String image_entry_name = imageSelectionCombo.getSelectionModel().getSelectedItem();
        String file_path = filePathField.getText();
        boolean doSplit = cbSplitAnnos.isSelected();
        boolean doRemove = cbRemoveAnnos.isSelected();

        // Load and save the mask to the image of interest
        maskToImage(anno_class, image_entry_name, doSplit, doRemove);

    } // end dialog creation


    /**
     * function that will add annotations to the image
     * optional: splitting annotations into individual objects
     * optional: removing ALL existing  objects
     * @param anno_class: String of name of the PathClass
     * @param image_entry: String name of the image entry
     * @param split: boolean if the annotations should be split into individual objects
     * @param remove: boolean if to remove all existing annotations before adding new ones
     */
    private void maskToImage(String anno_class, String image_entry, boolean split, boolean remove) {
        // get the image of interest in the project
        ProjectImageEntry<BufferedImage> img = project.getImageList().stream().filter(e -> e.getImageName().equals(image_entry)).findFirst().orElse(null);
        if (img == null) throw new RuntimeException("Could not find image entry!");

        ImageData<BufferedImage> img_data = null;
        try {
            img_data = img.readImageData();
        } catch (Exception ex) {
            logger.error("Could not read image data: " +  ex.getLocalizedMessage());
        }

        // Remove existing annotations
        if (remove) {
            // remove all (incl. non-Annotations)
            img_data.getHierarchy().clearAll();
            // remove only annotations, keeping the children of them
            //img_data.getHierarchy().removeObjects(img_data.getHierarchy().getAnnotationObjects(), true);
        }

        // check the image size and make sure that they have both the same size
        if (img_data.getServer().getHeight() != img_mask.getHeight() || img_data.getServer().getWidth() != img_mask.getWidth()) {
            throw new RuntimeException("Selected image and mask have different sizes!");
        }

        // create annotations form the mask
        RegionRequest request = null;
        try {
            request = RegionRequest.createAllRequests(img.readImageData().getServer(), 1).get(0);
        } catch (Exception ex) {
            logger.error("could not read image data: " +  ex.getLocalizedMessage());
        }
        // get the annotations from the mask (all are combined into single annotation)
        List<PathObject> annotations = ContourTracing.createAnnotations(new PixelImageIJ(img_mask.getProcessor()), request, 1, 1);

        if (split) {
            List<ROI> splitROIs = RoiTools.splitROI(annotations.get(0).getROI());
            // Split the annotations into individual objects
            List<PathObject> split_annos = new ArrayList<>();
            splitROIs.forEach(r -> split_annos.add(PathObjects.createAnnotationObject(r, PathClass.getInstance(anno_class))));
            // save the annotations to the image entry
            img_data.getHierarchy().addObjects(split_annos);
        }
        else {
            img_data.getHierarchy().addObject(
                    PathObjects.createAnnotationObject(annotations.get(0).getROI(), PathClass.getInstance(anno_class))
                    );
        }

        try {
            img.saveImageData(img_data);
        } catch (IOException e) {
            logger.error("Could not save image data: " +  e.getLocalizedMessage());
        }

    }

}
