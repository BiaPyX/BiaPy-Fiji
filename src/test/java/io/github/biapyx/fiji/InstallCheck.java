package io.github.biapyx.fiji;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import io.github.biapyx.fiji.env.BiaPyEnvManager;
import io.github.biapyx.fiji.env.BiaPyVersions;
import io.github.biapyx.fiji.env.Platform;
import io.github.biapyx.fiji.env.ProcessRunner;
import io.github.biapyx.fiji.env.PythonBridge;
import io.github.biapyx.fiji.env.PythonEnv;
import io.github.biapyx.fiji.gui.RunPanel;

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
			"print('torch', torch.__version__, (torch.ones(2) + 1).tolist())",
			"from torch.utils.data import DataLoader, TensorDataset",
			"torch.multiprocessing.set_sharing_strategy('file_system')  # macOS's only one: workers use torch_shm_manager",
			"dl = DataLoader(TensorDataset(torch.arange(4.)), batch_size=2, num_workers=1, multiprocessing_context='spawn')",
			"assert len(list(dl)) == 2");

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
		try {
			checkJob(problems);
		} catch (Exception e) {
			problems.add("The short job failed: " + e);
		}

		PythonBridge.shutdown();
		if (problems.isEmpty()) System.out.println("INSTALLATION OK");
		else problems.forEach(p -> System.out.println("PROBLEM: " + p));
		System.exit(problems.isEmpty() ? 0 : 1);
	}

	/**
	 * Trains (1 epoch, data-loader workers) and tests a tiny 2D instance segmentation job on the CPU, started as the
	 * "Run Workflow" page does, then applies its checkpoint to an image as "Apply model" does.
	 */
	private static void checkJob(List<String> problems) throws Exception {
		File dir = Files.createTempDirectory("biapy-install-check").toFile();
		File script = new File(dir, "install_check_job.py");
		try (InputStream in = InstallCheck.class.getResourceAsStream("/install_check_job.py")) {
			Files.copy(in, script.toPath());
		}
		File data = new File(dir, "data");
		String python = PythonEnv.getPython().getAbsolutePath();
		if (new ProcessBuilder(python, script.getAbsolutePath(), data.getAbsolutePath()).inheritIO().start().waitFor() != 0) {
			problems.add("Writing the data of the short job failed");
			return;
		}

		System.out.println("== Short job: train 1 epoch + test");
		File out = new File(dir, "results");
		List<String> cmd = RunPanel.jobCommand(new File(data, "job.yaml"), out, "check", new ArrayList<>(), false);
		boolean[] finished = { false };
		int code = new ProcessRunner(cmd, PythonEnv.environment(), dir).run(line -> {
			System.out.println(line);
			if (line.contains("FINISHED JOB")) finished[0] = true;
		});
		List<Path> files;
		try (Stream<Path> s = Files.walk(out.toPath())) {
			files = s.collect(Collectors.toList());
		}
		long instances = files.stream().filter(p -> p.getParent().getFileName().toString().equals("per_image_instances")).count();
		if (code != 0 || !finished[0] || instances != 2) {
			problems.add("The short job failed (exit code " + code + ", finished " + finished[0] + ", " + instances
					+ " of 2 test images segmented)");
			return;
		}

		System.out.println("== Apply model");
		Path checkpoint = files.stream().filter(p -> p.toString().endsWith(".pth")).findFirst().orElse(null);
		if (checkpoint == null) {
			problems.add("The short job saved no checkpoint");
			return;
		}
		PythonBridge bridge = new PythonBridge();
		JsonObject args = new JsonObject();
		args.addProperty("model", checkpoint.toString());
		bridge.call("describe_model", args, System.out::println);
		args.addProperty("out_dir", new File(dir, "apply").getAbsolutePath());
		args.addProperty("gpu", "");
		args.addProperty("keep", false);
		bridge.call("load_model", args, System.out::println);
		args.addProperty("image", new File(data, "image.npy").getAbsolutePath());
		JsonArray outputs = new JsonArray();
		outputs.add("final");
		args.add("outputs", outputs);
		JsonObject applied = bridge.call("apply_model", args, System.out::println);
		System.out.println("apply_model: " + applied);
		if (!applied.has("final") || !new File(applied.getAsJsonObject("final").get("npy").getAsString()).isFile())
			problems.add("Apply model gave no instances: " + applied);
	}
}
