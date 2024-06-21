package qupath.ext.efficientv2unet;

import qupath.ext.biop.cmd.VirtualEnvironmentRunner;

/**
 * Setup class for the preferences
 */
public class EV2UnetSetup {
    private static final EV2UnetSetup instance = new EV2UnetSetup();
    private String ev2UnetPythonPath = null;
    private VirtualEnvironmentRunner.EnvType envtype;
    private String condaPath = null;

    public static EV2UnetSetup getInstance() {
        return instance;
    }
    public void setEv2unetPythonPath(String path) {
        this.ev2UnetPythonPath = path;
    }

    public void setEnvtype(String t) {
        if (t == "EXE") this.envtype = VirtualEnvironmentRunner.EnvType.EXE;
        else if (t == "CONDA") this.envtype = VirtualEnvironmentRunner.EnvType.CONDA;
        else if (t == "VENV") this.envtype = VirtualEnvironmentRunner.EnvType.VENV;
        else throw new IllegalArgumentException("Unknown env type: " + t);
    }
    public void setEnvtype(VirtualEnvironmentRunner.EnvType t) {
        this.envtype = t;
    }
    public VirtualEnvironmentRunner.EnvType getEnvtype() {
        return envtype;
    }

    public String getEv2unetPythonPath() {
        return ev2UnetPythonPath;
    }

    public void setCondaPath(String condaPath) { this.condaPath = condaPath; }

    public String getCondaPath() { return condaPath; }

}
