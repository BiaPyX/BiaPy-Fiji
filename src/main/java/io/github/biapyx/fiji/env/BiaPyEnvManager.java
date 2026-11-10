package io.github.biapyx.fiji.env;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Installs BiaPy in a Python environment created with micromamba inside the Fiji folder (micromamba itself is
 * downloaded by {@link MicromambaInstaller}), in the same layout as SAMJ's (JDLL): {@code <root>/envs/biapy}.
 */
public class BiaPyEnvManager {

	/** Python of the environment (the BiaPy versions offered must support it). */
	static final String PYTHON_VERSION = "3.11";
	/** CUDA wheels used on Windows, where PyPI only ships CPU builds of PyTorch. */
	private static final String TORCH_CUDA_INDEX = "https://download.pytorch.org/whl/cu128";

	/** Receives the installation progress. Fractions are in [0, 1], or -1 when unknown. */
	public interface Listener {
		/** A new step starts (index starts at 0). */
		void step(int index, int total, String name);

		/** Progress of the task at hand (e.g. the file being downloaded). */
		void task(double fraction, String detail);

		/** How much of the current step is done, to advance the overall bar. */
		void stepProgress(double fraction);

		/** Raw output, for the details view. */
		void log(String line);
	}

	// pip with "--progress-bar raw" prints "Progress <done> of <total>" while downloading
	private static final Pattern PIP_PROGRESS = Pattern.compile("^Progress (\\d+) of (\\d+)");
	private static final Pattern PIP_ACTION = Pattern.compile("^(Collecting|Downloading|Installing collected packages|Successfully installed|Building wheel|Using cached)\\b.*");

	private final Listener listener;
	private volatile ProcessRunner currentRunner;
	private volatile Thread installThread;

	public BiaPyEnvManager(Listener listener) {
		this.listener = listener;
	}

	/** Network failure while downloading packages (the installation can be resumed later). */
	public static class NetworkException extends Exception {
		private static final long serialVersionUID = 1L;

		public NetworkException(String msg) {
			super(msg);
		}
	}

	/** True when the Fiji-managed environment already has a Python (e.g. from an interrupted installation). */
	public static boolean managedEnvExists() {
		return PythonEnv.pythonInEnv(PythonEnv.getManagedEnvDir()).isFile();
	}

	/**
	 * Installs micromamba (if needed), creates the environment and installs BiaPy in it.
	 *
	 * @param fresh recreate the Python environment from scratch; otherwise an existing one is reused, so an
	 *            interrupted installation continues where it stopped (pip skips what is already installed)
	 * @param version BiaPy version to install, e.g. "3.7.1"
	 */
	public void install(boolean fresh, String version) throws Exception {
		installThread = Thread.currentThread();
		boolean cudaTorch = Platform.isWindows() && hasNvidiaGpu();
		List<String> steps = new ArrayList<>(Arrays.asList("Installing micromamba", "Creating the Python environment"));
		if (cudaTorch) steps.add("Installing PyTorch with GPU support");
		steps.add("Installing BiaPy and its dependencies");
		int total = steps.size();
		int s = 0;

		File root = PythonEnv.getManagedRoot();
		listener.log("Installation folder: " + root.getAbsolutePath());

		listener.step(s, total, steps.get(s));
		if (!MicromambaInstaller.executable(root).isFile()) {
			MicromambaInstaller.install(root, (f, msg) -> {
				listener.task(f, msg);
				if (f >= 0) listener.stepProgress(f);
			}, listener::log);
		}
		File micromamba = MicromambaInstaller.executable(root);
		if (runMicromamba(root, micromamba, line -> {}, "--version") != 0)
			throw new IllegalStateException("micromamba was downloaded but cannot be executed: " + micromamba);
		listener.task(1, "Micromamba ready");
		checkCancelled();

		listener.step(++s, total, steps.get(s));
		listener.task(-1, "Downloading Python " + PYTHON_VERSION + " . . .");
		final double[] envProgress = { 0 };
		File python = PythonEnv.pythonInEnv(PythonEnv.getManagedEnvDir());
		if (fresh || !python.isFile()) {
			int code = runMicromamba(root, micromamba, line -> {
				listener.log(line);
				String l = line.trim();
				if (!l.isEmpty() && l.length() < 120) listener.task(-1, l);
				envProgress[0] += (0.95 - envProgress[0]) * 0.03;
				listener.stepProgress(envProgress[0]);
			}, "create", "-p", PythonEnv.getManagedEnvDir().getAbsolutePath(), "-c", "conda-forge",
					"python=" + PYTHON_VERSION, "pip", "--yes");
			checkCancelled();
			if (code != 0) throw new IllegalStateException("micromamba could not create the Python environment (exit code "
					+ code + "). See the details.");
		} else {
			listener.log("Reusing the existing Python environment: " + PythonEnv.getManagedEnvDir());
		}
		if (!python.isFile()) throw new IllegalStateException("Python was not created at " + python);
		listener.task(1, "Python environment created");
		checkCancelled();

		if (cudaTorch) {
			listener.step(++s, total, steps.get(s));
			pip(python, "torch", "torchvision", "--index-url", TORCH_CUDA_INDEX);
		}

		listener.step(++s, total, steps.get(s));
		installBiaPy(python, version);
		listener.log("BiaPy installed correctly.");
	}

	/** pip install of a version of the list (see {@link BiaPyVersions#requirement}). */
	private void installBiaPy(File python, String version) throws Exception {
		String requirement = BiaPyVersions.requirement(version);
		pip(python, requirement);
		// The development version has the number of the release it leads to: pip does not replace an installation
		// with the same number (from the development version to that release, or the other way), so BiaPy itself
		// (not its dependencies) is installed again
		pip(python, "--force-reinstall", "--no-deps", requirement);
		fixPydantic(python);
		listener.task(1, BiaPyVersions.describe(version) + " installed");
	}

	/**
	 * Prints PYDANTIC_TOO_NEW if pydantic is 2.14+ and bioimageio.spec older than 0.5.14.3: that one fails to load
	 * any BioImage Model Zoo model with it, and the BiaPy releases (up to 3.7.1 at least) don't cap pydantic.
	 * The same check is in the bridge's env_verify, to warn about environments installed before.
	 */
	private static final String PYDANTIC_CHECK = String.join("\n",
			"import importlib.metadata as m",
			"def v(p):",
			"    try:",
			"        return tuple(int(x) for x in m.version(p).split('.')[:4] if x.isdigit())",
			"    except Exception:",
			"        return None",
			"s, p = v('bioimageio.spec'), v('pydantic')",
			"if s and p and s < (0, 5, 14, 3) and p >= (2, 14):",
			"    print('PYDANTIC_TOO_NEW')");

	/** Installs an older pydantic if the one pip picked breaks bioimageio.spec (see {@link #PYDANTIC_CHECK}). */
	private void fixPydantic(File python) throws Exception {
		final boolean[] tooNew = { false };
		ProcessRunner check = new ProcessRunner(Arrays.asList(python.getAbsolutePath(), "-c", PYDANTIC_CHECK),
				PythonEnv.environment(), null);
		check.run(line -> tooNew[0] |= "PYDANTIC_TOO_NEW".equals(line.trim()));
		if (!tooNew[0]) return;
		listener.log("pydantic 2.14 or newer breaks the BioImage Model Zoo library of this BiaPy version: installing an older one");
		pip(python, "pydantic<2.14");
	}

	/** Installs another BiaPy version in the existing Fiji-managed environment (pip updates what changes). */
	public void switchVersion(String version) throws Exception {
		installThread = Thread.currentThread();
		File python = PythonEnv.pythonInEnv(PythonEnv.getManagedEnvDir());
		if (!python.isFile()) throw new IllegalStateException("The BiaPy environment does not exist: install BiaPy first.");
		listener.log("Installing " + BiaPyVersions.describe(version) + " in " + PythonEnv.getManagedEnvDir());
		listener.step(0, 1, "Installing " + BiaPyVersions.describe(version));
		installBiaPy(python, version);
		listener.log(BiaPyVersions.describe(version) + " installed correctly.");
	}

	private static final int PIP_ATTEMPTS = 3;
	// Lines of pip output that mean the network failed (and a retry may help)
	private static final Pattern NETWORK_ERROR = Pattern.compile(
			"connection-failed|NameResolutionError|ConnectionResetError|ConnectTimeout|ReadTimeout|Read timed out"
					+ "|Max retries exceeded|SSLError|ProxyError|Temporary failure in name resolution|getaddrinfo failed");

	/** Runs pip, retrying the whole command when the network fails (already downloaded files are cached). */
	private void pip(File python, String... packages) throws Exception {
		for (int attempt = 1;; attempt++) {
			boolean networkError = pipOnce(python, packages);
			if (!networkError) return;
			if (attempt == PIP_ATTEMPTS)
				throw new NetworkException("The connection to the Python package servers failed several times.\n"
						+ "Check your internet connection, and whether a firewall, antivirus or proxy is blocking\n"
						+ "pypi.org / files.pythonhosted.org" + (packages[0].startsWith("torch") ? " / download.pytorch.org" : "")
						+ (String.join(" ", packages).contains("github.com") ? " / github.com" : "")
						+ ".\n\nClick 'Install BiaPy' again to continue where it stopped.");
			listener.log("Connection problem, retrying (attempt " + (attempt + 1) + " of " + PIP_ATTEMPTS + ") . . .");
			listener.task(-1, "Connection problem, retrying in 10 s (attempt " + (attempt + 1) + " of " + PIP_ATTEMPTS + ")");
			Thread.sleep(10_000);
			checkCancelled();
		}
	}

	/** @return true if pip failed because of the network */
	private boolean pipOnce(File python, String... packages) throws Exception {
		// Long timeout for slow connections; few retries per request because a failing server (e.g. one that
		// does not resolve) would otherwise be retried for every package. The whole step is retried in pip().
		List<String> cmd = new ArrayList<>(Arrays.asList(python.getAbsolutePath(), "-u", "-m", "pip", "install",
				"--progress-bar", "raw", "--disable-pip-version-check", "--retries", "3", "--timeout", "60"));
		cmd.addAll(Arrays.asList(packages));
		final boolean[] networkError = { false };
		currentRunner = new ProcessRunner(cmd, pipEnvironment(), null);
		final String[] action = { "Resolving dependencies . . ." };
		final double[] stepDone = { 0 };
		listener.task(-1, action[0]);
		int code = currentRunner.run(line -> {
			Matcher m = PIP_PROGRESS.matcher(line);
			if (m.find()) {
				double total = Double.parseDouble(m.group(2));
				if (total > 0) listener.task(Double.parseDouble(m.group(1)) / total, action[0]);
				return;
			}
			listener.log(line);
			if (NETWORK_ERROR.matcher(line).find()) networkError[0] = true;
			String l = line.trim();
			if (PIP_ACTION.matcher(l).matches()) {
				action[0] = abbreviate(l);
				listener.task(-1, action[0]);
				// The number of packages is unknown beforehand: downloads approach 75% of the step,
				// installing the packages takes it to 90%
				if (l.startsWith("Installing collected packages")) stepDone[0] = Math.max(stepDone[0], 0.75);
				else if (l.startsWith("Successfully installed")) stepDone[0] = 1;
				else stepDone[0] += (0.75 - stepDone[0]) * 0.04;
				listener.stepProgress(stepDone[0]);
			}
		});
		if (currentRunner.isCancelled()) throw new InterruptedException("Installation canceled");
		if (code == 0) return false;
		if (networkError[0]) return true;
		throw new IllegalStateException("pip install failed with exit code " + code + ". See the details.");
	}

	/**
	 * Environment for pip that ignores any pip configuration of the computer. Other tools (e.g. NVIDIA's)
	 * leave extra package servers such as pypi.ngc.nvidia.com in system-wide pip.ini files or PIP_* variables,
	 * and pip queried them for every package. "--isolated" is not enough: it still reads system-wide files.
	 */
	static java.util.Map<String, String> pipEnvironment() {
		java.util.Map<String, String> env = PythonEnv.environment();
		env.put("-PIP_", ""); // remove every inherited PIP_* variable...
		env.put("PIP_CONFIG_FILE", Platform.isWindows() ? "nul" : "/dev/null"); // ...and all config files
		return env;
	}

	/** Runs micromamba from the installation folder (cancellable, see {@link #cancel()}) and returns its exit code. */
	private int runMicromamba(File root, File micromamba, java.util.function.Consumer<String> lines, String... args)
			throws Exception {
		List<String> cmd = new ArrayList<>();
		cmd.add(micromamba.getAbsolutePath());
		cmd.addAll(Arrays.asList(args));
		ProcessRunner runner = new ProcessRunner(cmd, null, root);
		currentRunner = runner;
		try {
			return runner.run(lines);
		} finally {
			currentRunner = null;
		}
	}

	public void cancel() {
		Thread t = installThread;
		if (t != null) t.interrupt();
		ProcessRunner r = currentRunner;
		if (r != null) r.cancel();
	}

	private void checkCancelled() throws InterruptedException {
		if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Installation canceled");
	}

	private static String abbreviate(String s) {
		return s.length() > 100 ? s.substring(0, 97) + "..." : s;
	}

	private static boolean hasNvidiaGpu() {
		return GpuStatus.available();
	}
}
