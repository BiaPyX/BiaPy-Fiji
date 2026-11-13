package io.github.biapyx.fiji.gui;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import javax.swing.SwingUtilities;

import com.google.gson.JsonObject;

import ij.IJ;
import io.github.biapyx.fiji.BiaPyFiji;
import io.github.biapyx.fiji.env.PythonBridge;
import io.github.biapyx.fiji.env.PythonEnv;

/**
 * The state of BiaPy's Python environment, shared by the BiaPy windows ("Apply entire workflow" and "Apply model"):
 * installing or checking it in one updates both.
 */
public final class EnvMonitor {

	private EnvMonitor() {}

	private static volatile JsonObject envInfo;
	private static volatile String envState = "checking";
	private static volatile int envCheck = 0;
	private static final List<Consumer<JsonObject>> LISTENERS = new CopyOnWriteArrayList<>();
	/** What the listeners were last told, or null before the first check. */
	private static volatile JsonObject last;

	/** Information about the Python environment (BiaPy/torch versions and GPUs), or null if unknown. */
	public static JsonObject info() {
		return envInfo;
	}

	/** Last known state of the Python environment: "checking", "ok", "broken" or "missing". */
	public static String state() {
		return envState;
	}

	/**
	 * Called (on the EDT) with the environment information and its "_state" each time it changes, and now with the
	 * last one known (a window opened after the check gets it too).
	 */
	public static void addListener(Consumer<JsonObject> l) {
		LISTENERS.add(l);
		JsonObject now = last;
		if (now == null) return;
		if (SwingUtilities.isEventDispatchThread()) l.accept(now.deepCopy());
		else SwingUtilities.invokeLater(() -> l.accept(now.deepCopy()));
	}

	/** Checks the environment unless it was already checked (or is being checked). */
	public static void checkOnce() {
		if (envCheck == 0) refresh();
	}

	/** Queries the Python environment in the background and notifies the listeners (null = not available). */
	public static void refresh() {
		PythonBridge.shutdown(); // the environment may have changed: the wizard's process starts again
		envInfo = null;
		notifyEnv(null, "checking");
		final int check = ++envCheck;
		new Thread(() -> {
			if (!PythonEnv.pythonExists()) {
				publishEnv(check, null, "missing");
				return;
			}
			// 1) Quick: package versions and GPUs without importing anything heavy (~0.2 s)
			JsonObject info;
			try {
				info = new PythonBridge().call("env_quick", null, null);
			} catch (Exception e) {
				IJ.log("[BiaPy] Could not query the Python environment: " + e.getMessage());
				publishEnv(check, null, "broken");
				return;
			}
			// The GPUs of the machine, whatever BiaPy version the environment has
			info.add("gpus", io.github.biapyx.fiji.env.GpuStatus.list());
			if (info.get("biapy_version") == null || info.get("biapy_version").isJsonNull()) {
				publishEnv(check, info, "broken");
				return;
			}
			info.addProperty("_verifying", true);
			publishEnv(check, info, "ok");
			// Import BiaPy in the background for the wizard, before the user gets to the data questions
			if (BiaPyFiji.wizardSupported(info)) PythonBridge.warmUp();

			// 2) Background: import PyTorch to confirm it works and can use the GPU (a few seconds)
			JsonObject verified = info.deepCopy();
			verified.remove("_verifying");
			String state = "ok";
			try {
				JsonObject v = new PythonBridge().call("env_verify", null, null);
				for (String k : v.keySet()) verified.add(k, v.get(k));
				if (v.has("torch_error")) state = "broken";
				else if (verified.getAsJsonArray("gpus").size() > 0 && !v.get("cuda_available").getAsBoolean())
					verified.addProperty("gpu_warning", "PyTorch cannot use the GPU (it may be a CPU-only build): "
							+ "BiaPy would run on CPU. Reinstall BiaPy to get the GPU version.");
			} catch (Exception e) {
				verified.addProperty("torch_error", e.getMessage());
				state = "broken";
			}
			publishEnv(check, verified, state);
		}, "BiaPy-env-check").start();
	}

	/** Publishes a check result unless a newer check has started meanwhile. */
	private static void publishEnv(int check, JsonObject info, String state) {
		if (check != envCheck) return;
		envInfo = "ok".equals(state) ? info : null;
		SwingUtilities.invokeLater(() -> {
			if (check == envCheck) notifyEnv(info, state);
		});
	}

	private static void notifyEnv(JsonObject info, String state) {
		envState = state;
		JsonObject payload = info == null ? new JsonObject() : info.deepCopy();
		payload.addProperty("_state", state);
		last = payload;
		for (Consumer<JsonObject> l : LISTENERS) l.accept(payload.deepCopy());
	}
}
