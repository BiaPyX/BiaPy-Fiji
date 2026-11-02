package io.github.biapyx.fiji.env;

import java.io.File;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ij.IJ;
import ij.Prefs;

/**
 * Locates the Python interpreter used to run BiaPy. By default it is the environment that the plugin
 * installs with micromamba inside the Fiji folder (same approach as SAMJ), but the user can also point
 * to an existing Python environment where BiaPy is already installed.
 */
public class PythonEnv {

	public static final String ENV_NAME = "biapy";
	private static final String PREF_CUSTOM_PYTHON = "biapy.custom_python";

	private PythonEnv() {}

	/** Folder where micromamba and the BiaPy environment are installed: {@code <Fiji>/biapy}. */
	public static File getManagedRoot() {
		String fiji = IJ.getDirectory("imagej");
		if (fiji == null) fiji = System.getProperty("user.dir");
		return new File(fiji, "biapy");
	}

	public static File getManagedEnvDir() {
		return Paths.get(getManagedRoot().getAbsolutePath(), "envs", ENV_NAME).toFile();
	}

	public static File pythonInEnv(File envDir) {
		return Platform.isWindows() ? new File(envDir, "python.exe") : Paths.get(envDir.getAbsolutePath(), "bin", "python").toFile();
	}

	/** The environment folder that contains the given python executable. */
	public static File envDirOf(File python) {
		File parent = python.getAbsoluteFile().getParentFile();
		if (!Platform.isWindows() && parent != null && parent.getName().equals("bin")) return parent.getParentFile();
		return parent;
	}

	/** Custom python set by the user, or {@code null} to use the managed environment. */
	public static String getCustomPython() {
		// -Dbiapy.python=/path/to/python overrides the preference (handy for development)
		String p = System.getProperty("biapy.python", Prefs.get(PREF_CUSTOM_PYTHON, ""));
		return p.isEmpty() ? null : p;
	}

	public static void setCustomPython(String python) {
		Prefs.set(PREF_CUSTOM_PYTHON, python == null ? "" : python);
		Prefs.savePreferences();
	}

	public static File getPython() {
		String custom = getCustomPython();
		return custom != null ? new File(custom) : pythonInEnv(getManagedEnvDir());
	}

	public static boolean pythonExists() {
		return getPython().isFile();
	}

	/** Command prefix to run python unbuffered: {@code python -u}. */
	public static List<String> pythonCommand() {
		List<String> cmd = new ArrayList<>();
		cmd.add(getPython().getAbsolutePath());
		cmd.add("-u");
		return cmd;
	}

	/**
	 * Environment variables needed to run the env's python without activating it: its native libraries
	 * must take precedence over the system ones (otherwise e.g. an old system libstdc++ breaks scipy).
	 */
	public static Map<String, String> environment() {
		File env = envDirOf(getPython());
		Map<String, String> vars = new HashMap<>();
		vars.put("PYTHONUNBUFFERED", "1");
		vars.put("PYTHONIOENCODING", "utf-8");
		vars.put("PYTHONNOUSERSITE", "1");
		if (Platform.isWindows()) {
			String p = String.join(File.pathSeparator, env.getAbsolutePath(),
					Paths.get(env.getAbsolutePath(), "Library", "mingw-w64", "bin").toString(),
					Paths.get(env.getAbsolutePath(), "Library", "usr", "bin").toString(),
					Paths.get(env.getAbsolutePath(), "Library", "bin").toString(),
					Paths.get(env.getAbsolutePath(), "Scripts").toString());
			vars.put("PATH+", p);
			// PyTorch for Windows is built without libuv, which torch.distributed.run (several GPUs) uses by default
			vars.put("USE_LIBUV", "0");
		} else {
			vars.put("PATH+", new File(env, "bin").getAbsolutePath());
			if (Platform.isMacOS()) {
				vars.put("DYLD_FALLBACK_LIBRARY_PATH+", new File(env, "lib").getAbsolutePath());
				// The few operations PyTorch has not implemented for Apple Silicon's GPU (MPS) run on the CPU
				// instead of stopping the job
				vars.put("PYTORCH_ENABLE_MPS_FALLBACK", "1");
			} else {
				vars.put("LD_LIBRARY_PATH+", new File(env, "lib").getAbsolutePath());
			}
		}
		return vars;
	}

	/**
	 * Applies {@link #environment()} to a process environment. Keys ending with "+" are prepended to the
	 * existing value, keys starting with "-" remove the inherited variables with that prefix; PYTHONPATH/PYTHONHOME are removed so Fiji's environment cannot leak into Python.
	 */
	static void applyEnvironment(Map<String, String> target, Map<String, String> vars) {
		target.keySet().removeIf(k -> k.equalsIgnoreCase("PYTHONPATH") || k.equalsIgnoreCase("PYTHONHOME"));
		// Keys starting with "-" remove every inherited variable with that prefix
		for (String key : vars.keySet()) {
			if (!key.startsWith("-")) continue;
			String prefix = key.substring(1).toUpperCase();
			target.keySet().removeIf(k -> k.toUpperCase().startsWith(prefix));
		}
		for (Map.Entry<String, String> e : vars.entrySet()) {
			String key = e.getKey();
			if (key.startsWith("-")) continue;
			if (!key.endsWith("+")) {
				target.put(key, e.getValue());
				continue;
			}
			key = key.substring(0, key.length() - 1);
			String existingKey = key;
			for (String k : target.keySet()) if (k.equalsIgnoreCase(key)) existingKey = k;
			String old = target.get(existingKey);
			target.put(existingKey, old == null || old.isEmpty() ? e.getValue() : e.getValue() + File.pathSeparator + old);
		}
	}
}
