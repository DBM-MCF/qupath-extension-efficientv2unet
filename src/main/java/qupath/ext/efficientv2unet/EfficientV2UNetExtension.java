package qupath.ext.efficientv2unet;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.StringProperty;
import org.controlsfx.control.PropertySheet;
import org.controlsfx.tools.Platform;
import qupath.ext.biop.cellpose.CellposeExtension;
import qupath.ext.biop.cmd.VirtualEnvironmentRunner;
import qupath.fx.dialogs.Dialogs;
import qupath.fx.prefs.controlsfx.PropertyItemBuilder;
import qupath.lib.common.GeneralTools;
import qupath.lib.common.Version;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.GitHubProject;
import qupath.lib.gui.extensions.QuPathExtension;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.gui.tools.MenuTools;
import org.controlsfx.control.action.Action;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.Arrays;
import java.util.LinkedHashMap;


/**
 * Install EfficientV2UNet as an extension.
 * <p>
 *
 * @author Loïc Sauteur - DBM University of Basel
 */
public class EfficientV2UNetExtension implements QuPathExtension, GitHubProject {

    private static final Logger logger = LoggerFactory.getLogger(EfficientV2UNetExtension.class);

    private static final LinkedHashMap<String, String> SCRIPTS = new LinkedHashMap<>() {{
        put("EV2UNet predict script template", "scripts/EfficientV2UNet_predict_template.groovy");
        put("EV2UNet training script template", "scripts/EfficientV2UNet_train_template.groovy");
    }};


    @Override
    public void installExtension(QuPathGUI qupath) {
        // The Efficient V2 UNet extension requires the cellpose extension >= 0.9.3
        String cellpose_version_str = GeneralTools.getPackageVersion(CellposeExtension.class);

        // I cannot check if cellpose is installed at all, since I need to load it here. Hence, QuPath Extension manager will throw an error
        if (cellpose_version_str == null) {
            Dialogs.showErrorMessage(getName(),
                    "The Efficient V2 UNet extension requires cellpose extension 0.9.2 or higher.\n" +
                            "You have a unknown version of the cellpose.\n\n" +
                            "Please make sure that both extension jars are copied to the QuPath extension folder.");
            return;
        }
        Version cellpose_version = Version.parse(cellpose_version_str);
        if ((cellpose_version.getMajor() == 0 && cellpose_version.getMinor() < 9) || (cellpose_version.getMinor() == 9 && cellpose_version.getPatch() < 3)) {
            Dialogs.showErrorMessage(getName(),
                    "The Efficient V2 UNet extension requires cellpose extension 0.9.3 or higher. You have version " + cellpose_version  +
                            ".\n\nPlease make sure that both extension jars are copied to the QuPath extension folder.");
            return;
        }

        // Add Menu entries
        MenuTools.addMenuItems(
                qupath.getMenu("Extensions>Efficient V2 UNet", true),
                new Action("Predict images", e -> new EV2UNetPredictCommand(qupath).run())
        );

        MenuTools.addMenuItems(
                qupath.getMenu("Extensions>Efficient V2 UNet", true),
                new Action("Load a mask image", e -> new LoadMaskCommand(qupath).run())
        );

        MenuTools.addMenuItems(
                qupath.getMenu("Extensions>Efficient V2 UNet", true),
                new Action("Train a UNet", e -> new EV2UNetTrainCommand(qupath).run())
        );

        // Create preference entry for the python environment   ---------------
        // Get a copy of the options
        EV2UnetSetup options = EV2UnetSetup.getInstance();

        // Create the option properties
        StringProperty ev2unetPythonPath = PathPrefs.createPersistentPreference("Path to EfficientV2UNet Python", "");
        ObjectProperty<VirtualEnvironmentRunner.EnvType> envType = PathPrefs.createPersistentPreference("Env type", VirtualEnvironmentRunner.EnvType.EXE, VirtualEnvironmentRunner.EnvType.class);
        // TODO for cellpose-extension: conda-return branch
        //StringProperty condaPath = PathPrefs.createPersistentPreference("condaPath", "");

        // Set the class options to the current QuPath values
        options.setEv2unetPythonPath(ev2unetPythonPath.get());
        options.setEnvtype(envType.get());
        // TODO for cellpose-extension: conda-return branch
        //options.setCondaPath(condaPath.get());

        // Ensure Platform (WIN/UNIX) dependent preference description
        PropertyItemBuilder.PropertyType propType = PropertyItemBuilder.PropertyType.FILE;
        String name;
        String description;
        if (Platform.getCurrent() == Platform.WINDOWS) {
            name = "EfficientV2UNet environment 'python.exe' location";
            description = "The full path to your EfficientV2UNet environment, including the 'python.exe'\n" +
                    "E.g.: <C:\\Users\\YourName\\.conda\\envs\\EnvName\\python.exe>";
        }
        else {
            name = "EfficientV2UNet environment python file location";
            description = "The full path to your EfficientV2UNet environment python file.\n" +
                    "E.g.: </Users/YourName/opt/anaconda3/envs/EnvName/bin/python>";
        }
        // Add description to the preference
        PropertySheet.Item ev2unetPathItem = new PropertyItemBuilder<>(ev2unetPythonPath, String.class)
                .propertyType(propType)
                .name(name)
                .category("EfficientV2UNet")
                .description(description)
                .build();

        PropertySheet.Item envTypeItem = new PropertyItemBuilder<>(envType, VirtualEnvironmentRunner.EnvType.class)
                .propertyType(PropertyItemBuilder.PropertyType.CHOICE)
                .name("EfficientV2UNet environment type")
                .category("EfficientV2UNet")
                .description("The type of the EfficientV2UNet environment")
                .choices(Arrays.asList(VirtualEnvironmentRunner.EnvType.values()))
                .build();

        // TODO for cellpose-extension: conda-return branch
        /*
        PropertySheet.Item condaPathItem = new PropertyItemBuilder<>(condaPath, String.class)
                .propertyType(PropertyItemBuilder.PropertyType.GENERAL)
                .name("'Conda' script location (optional)")
                .category("EfficientV2UNet")
                .description("The full path to you conda/mamba command, in case you want the extension to use the 'conda activate' command.\ne.g 'C:\\ProgramData\\Miniconda3\\condabin\\mamba.bat'")
                .build();
         */

        // Listen for changes in QuPath settings
        ev2unetPythonPath.addListener((v, o, n) -> options.setEv2unetPythonPath(n));
        envType.addListener((v, o, n) -> {
            // As 'activate conda' does not work on OSX, we use Python Executable instead, which works just fine also with CONDA envs
            // TODO check if Oli's fix allows this to work on Mac
            if (n.equals(VirtualEnvironmentRunner.EnvType.CONDA) && Platform.getCurrent() == Platform.OSX) {
                logger.warn("CONDA does not work properly on OSX. Using Python Executable instead.");
                Dialogs.showWarningNotification("Please use Python Executable", "CONDA does not work properly on OSX. Using Python Executable instead.");
                n = VirtualEnvironmentRunner.EnvType.EXE;
                envTypeItem.setValue(n);
                envType.setValue(n);
            }
            options.setEnvtype(n);
        });
        // TODO for cellpose-extension: conda-return branch
        //condaPath.addListener((v, o, n) -> options.setCondaPath(n));

        // Add and populate the permanent Preference
        QuPathGUI.getInstance().getPreferencePane().getPropertySheet().getItems().add(ev2unetPathItem);
        QuPathGUI.getInstance().getPreferencePane().getPropertySheet().getItems().add(envTypeItem);
        // TODO for cellpose-extension: conda-return branch
        //QuPathGUI.getInstance().getPreferencePane().getPropertySheet().getItems().add(condaPathItem);

        // Add template scripts to the Menu
        SCRIPTS.entrySet().forEach(entry -> {
            String script_name = entry.getValue();
            String script_cmd = entry.getKey();
            try (InputStream stream = EfficientV2UNetExtension.class.getClassLoader().getResourceAsStream(script_name)) {
                String script = new String(stream.readAllBytes(), "UTF-8");
                if (script != null) {
                    MenuTools.addMenuItems(
                            qupath.getMenu("Extensions>Efficient V2 UNet>Script templates", true),
                            new Action(script_cmd, e -> openScript(qupath, script)));
                }
            } catch (Exception e) {
                logger.error(e.getLocalizedMessage(), e);
            }
        });


    }

    @Override
    public String getName() {
        //return QuPathResources.getString("Extension.EfficientV2UNet");
        return "Efficient V2 UNet Extension";
    }

    @Override
    public String getDescription() {
        //return QuPathResources.getString("Extension.EfficientV2UNet.description");
        return "A QuPath extension to run EfficientV2UNet in a virtual environment within QuPath";
    }

    @Override
    public Version getQuPathVersion() {
        // return the QuPath version for which this extension was written
        //return Version.parse("0.5.0");
        return QuPathExtension.super.getQuPathVersion();
    }

    @Override
    public GitHubRepo getRepository() {
        return GitHubRepo.create(getName(), "DBM-MCF", "qupath-extension-efficientv2unet");
    }

    private static void openScript(QuPathGUI qupath, String script) {
        var editor = qupath.getScriptEditor();
        if (editor == null) {
            logger.error("No script editor is available!");
            return;
        }
        qupath.getScriptEditor().showScript("Efficient V2 UNet", script);
    }

}
