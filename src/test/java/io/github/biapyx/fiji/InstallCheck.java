package io.github.biapyx.fiji;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonObject;

import io.github.biapyx.fiji.env.BiaPyEnvManager;
import io.github.biapyx.fiji.env.BiaPyVersions;
import io.github.biapyx.fiji.env.Platform;
import io.github.biapyx.fiji.env.PythonBridge;
import io.github.biapyx.fiji.env.PythonEnv;

/**
 * Installs BiaPy from scratch with the plugin's installer and checks that the environment works, as the HOME tab
 * does. Run by CI on each OS (.github/workflows/install_check.yml).
 * Usage (with -Dij.dir=<fake Fiji folder>): InstallCheck [BiaPy version, default: the plugin's]
 * Exits with 0 only if everything works.
 */
public class InstallCheck {

	private static final String SMOKE = String.join("\n",
			"import numba, torch, torchvision, cv2, skimage",
			"from biapy import BiaPy",
			"assert numba.njit(lambda x: x + 1)(1) == 2",
			"print('torch', torch.__version__, (torch.ones(2) + 1).tolist())");

	public static void main(String[] args) throws Exception {
		String version = args.length > 0 && !args[0].isEmpty() ? args[0] : BiaPyFiji.BIAPY_VERSION;
		System.out.println("Installing " + BiaPyVersions.describe(version) + " in " + PythonEnv.getManagedRoot()
				+ " (os.arch " + System.getProperty("os.arch") + ", Rosetta " + Platform.isRosetta() + ")");
		new BiaPyEnvManager(new BiaPyEnvManager.Listener() {
			@Override
			public void step(int index, int total, String name) {
				System.out.println("== Step " + (index + 1) + "/" + total + ": " + name);
			}

			@Override
			public void task(double fraction, String detail) {}

			@Override
			public void stepProgress(double fraction) {}

			@Override
			public void log(String line) {
				System.out.println(line);
			}
		}).install(true, version);

		List<String> problems = new ArrayList<>();
		JsonObject quick = new PythonBridge().call("env_quick", null, System.out::println);
		JsonObject verify = new PythonBridge().call("env_verify", null, System.out::println);
		System.out.println("env_quick: " + quick + "\nenv_verify: " + verify);
		String installed = quick.has("biapy_version") && !quick.get("biapy_version").isJsonNull()
				? quick.get("biapy_version").getAsString() : null;
		if (!BiaPyVersions.DEV.equals(version) && !version.equals(installed))
			problems.add("BiaPy " + version + " was requested but " + installed + " is installed");
		String wizard = BiaPyFiji.wizardProblem(quick);
		if (wizard != null) problems.add(wizard);
		if (verify.has("torch_error")) problems.add("PyTorch doesn't work: " + verify.get("torch_error").getAsString());
		if (verify.has("bmz_warning")) problems.add(verify.get("bmz_warning").getAsString());
		try {
			// Starts the bridge server, which imports BiaPy and bioimageio like the wizard does
			System.out.println("cache_folders: " + new PythonBridge().call("cache_folders", null, System.out::println));
		} catch (Exception e) {
			problems.add("The bridge server failed: " + e.getMessage());
		}
		int code = new ProcessBuilder(PythonEnv.getPython().getAbsolutePath(), "-c", SMOKE).inheritIO().start().waitFor();
		if (code != 0) problems.add("Importing BiaPy and its main dependencies failed (exit code " + code + ")");

		PythonBridge.shutdown();
		if (problems.isEmpty()) System.out.println("INSTALLATION OK");
		else problems.forEach(p -> System.out.println("PROBLEM: " + p));
		System.exit(problems.isEmpty() ? 0 : 1);
	}
}
