/*-
 * Copyright 2020-2021 QuPath developers,  University of Edinburgh
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package qupath.ext.efficientv2unet;

import javafx.beans.property.StringProperty;
import org.controlsfx.control.PropertySheet;
import org.controlsfx.tools.Platform;
import qupath.fx.prefs.controlsfx.PropertyItemBuilder;
import qupath.lib.common.Version;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.GitHubProject;
import qupath.lib.gui.extensions.QuPathExtension;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.gui.tools.MenuTools;
import org.controlsfx.control.action.Action;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;



/**
 * FIXME
 * Install EfficientV2UNet as an extension.
 * <p>
 *
 * @author Loïc Sauteur
 */
public class EfficientV2UNetExtension implements QuPathExtension, GitHubProject {

    private static final Logger logger = LoggerFactory.getLogger(EfficientV2UNetExtension.class);

    private static final LinkedHashMap<String, String> SCRIPTS = new LinkedHashMap<>() {{
        put("EV2UNet test script", "scripts/Efficient_V2_UNet_test_script_template.groovy");
    }};

    @Override
    public void installExtension(QuPathGUI qupath) {

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
                new Action("Test venv", e -> new EV2UNetTrainCommand(qupath).runEnv())
        );

        MenuTools.addMenuItems(
                qupath.getMenu("Extensions>Efficient V2 UNet", true),
                new Action("Test dialog", e -> new EV2UNetTrainCommand(qupath).run())
        );

        // Create preference entry for the python environment   ---------------
        // Get a copy of the options
        EV2UnetSetup options = EV2UnetSetup.getInstance();

        // Create the option properties
        StringProperty ev2unetPythonPath = PathPrefs.createPersistentPreference("Path to EfficientV2UNet Python", "");

        // Set the class options to the current QuPath values
        options.setEv2unetPythonPath(ev2unetPythonPath.get());

        // Listen for changes in QuPath settins
        ev2unetPythonPath.addListener((v, o, n) -> options.setEv2unetPythonPath(n));

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
            name = "EfficientV2UNet environment python folder location";
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

        // Add and populate the permanent Preference
        QuPathGUI.getInstance().getPreferencePane().getPropertySheet().getItems().add(ev2unetPathItem);

        /*
        // I guess this only works once it is really installed...
        SCRIPTS.entrySet().forEach(entry -> {
            String script_name = entry.getValue();
            String script_cmd = entry.getKey();
            try (InputStream stream = EfficientV2UNetExtension.class.getClassLoader().getResourceAsStream(script_name)) {
                String script = new String(stream.readAllBytes(), "UTF-8");
                if (script != null) {
                    MenuTools.addMenuItems(
                            qupath.getMenu("Extensions>Efficient V2 UNet", true),
                            new Action(script_cmd, e -> openScript(qupath, script_name))
                    );
                }
                else {

                }
            } catch (Exception e) {
                System.out.println("resource stream = " + script_name);
                System.out.println("There seems to be a problem...");
                System.out.println(e.toString());
                logger.error(e.getLocalizedMessage(), e);
            }
        });

        */
    }

    @Override
    public String getName() {
        return "Test my extension name";
    } // FIXME

    @Override
    public String getDescription() {
        return "Magic happens hopefully";
    } // FIXME

    @Override
    public Version getQuPathVersion() {
        return QuPathExtension.super.getQuPathVersion();
    }

    @Override
    public GitHubRepo getRepository() {
        // FIXME
        return GitHubRepo.create(getName(), "dbm", "qupath-extension-efficient_v2_unet");
    }

    private static void openScript(QuPathGUI qupath, String script) {
        // FIXME
        var editor = qupath.getScriptEditor();
        if (editor == null) {
            logger.error("No script editor is available!");
            return;
        }
        qupath.getScriptEditor().showScript("Test ... Test", script);
    }

}
