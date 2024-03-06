package qupath.ext.efficientv2unet;

/**
 * Setup class for the preferences
 */
public class EV2UnetSetup {
    private static final EV2UnetSetup instance = new EV2UnetSetup();
    private String ev2UnetPythonPath = null;

    public static EV2UnetSetup getInstance() {
        return instance;
    }
    public void setEv2unetPythonPath(String path) {
        this.ev2UnetPythonPath = path;
    }

    public String getEv2unetPythonPath() {
        return ev2UnetPythonPath;
    }

}
