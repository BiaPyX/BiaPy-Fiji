package io.github.biapyx.fiji.env;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;


import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * NVIDIA GPUs of the machine from nvidia-smi (numbered as BiaPy's --gpu), independently of the Python environment
 * and its BiaPy version, and whether they are already in use (the compute processes running on them, their memory
 * and utilization), to warn before running a job on a busy GPU.
 */
public class GpuStatus {

	/** Utilization (%) above which a GPU without compute processes listed is still taken as busy. */
	private static final int BUSY_UTILIZATION = 20;

	private GpuStatus() {}

	/**
	 * GPUs of the machine as {"index", "name", "memory_mb"} (empty if there are none or nvidia-smi can't be run),
	 * as the environment information carries them.
	 */
	public static JsonArray list() {
		JsonArray out = new JsonArray();
		List<String[]> rows = query("--query-gpu=index,name,memory.total");
		if (rows == null) return out;
		for (String[] g : rows) {
			if (g.length < 3) continue;
			try {
				JsonObject o = new JsonObject();
				o.addProperty("index", Integer.parseInt(g[0]));
				o.addProperty("name", g[1]);
				o.addProperty("memory_mb", parseInt(g[2]));
				out.add(o);
			} catch (NumberFormatException e) {
				// Not a GPU row (e.g. an error message)
			}
		}
		return out;
	}

	/** Whether nvidia-smi can be run (an NVIDIA driver is installed). */
	public static boolean available() {
		return query("--query-gpu=index") != null;
	}

	/**
	 * nvidia-smi: from the PATH, or where older NVIDIA drivers for Windows install it (newer ones put it in
	 * System32, which is in the PATH).
	 */
	static String executable() {
		if (Platform.isWindows()) {
			String pf = System.getenv().getOrDefault("ProgramFiles", "C:\\Program Files");
			java.io.File f = new java.io.File(pf, "NVIDIA Corporation\\NVSMI\\nvidia-smi.exe");
			if (f.isFile() && !onPath("nvidia-smi.exe")) return f.getAbsolutePath();
		}
		return "nvidia-smi";
	}

	private static boolean onPath(String exe) {
		String path = System.getenv("PATH");
		if (path == null) return false;
		for (String dir : path.split(java.io.File.pathSeparator))
			if (new java.io.File(dir, exe).isFile()) return true;
		return false;
	}

	/**
	 * Description of each given GPU that is in use (e.g. "GPU 1 (NVIDIA GeForce RTX 2080 Ti): 9.6 of 11.0 GB used,
	 * 1 process (python)"). Empty if none is; null if nvidia-smi could not be queried.
	 */
	public static List<String> busy(List<Integer> gpus) {
		List<String[]> info = query("--query-gpu=index,uuid,name,memory.used,memory.total,utilization.gpu");
		List<String[]> apps = query("--query-compute-apps=gpu_uuid,pid,process_name,used_memory");
		if (info == null) return null;
		Map<String, List<String>> processes = new HashMap<>();
		if (apps != null)
			for (String[] a : apps)
				if (a.length >= 3) processes.computeIfAbsent(a[0], k -> new ArrayList<>()).add(shortName(a[2]) + ", PID " + a[1]);
		List<String> out = new ArrayList<>();
		for (String[] g : info) {
			if (g.length < 6) continue;
			int index;
			try {
				index = Integer.parseInt(g[0]);
			} catch (NumberFormatException e) {
				continue;
			}
			if (!gpus.contains(index)) continue;
			List<String> procs = processes.getOrDefault(g[1], new ArrayList<>());
			int util = parseInt(g[5]);
			if (procs.isEmpty() && util < BUSY_UTILIZATION) continue;
			StringBuilder sb = new StringBuilder("GPU " + index + " (" + g[2] + "): ");
			sb.append(String.format("%.1f of %.1f GB used, %d%% busy", parseInt(g[3]) / 1024.0, parseInt(g[4]) / 1024.0, util));
			if (!procs.isEmpty())
				sb.append(", ").append(procs.size()).append(procs.size() == 1 ? " process (" : " processes (")
						.append(String.join("; ", procs)).append(")");
			out.add(sb.toString());
		}
		return out;
	}

	private static String shortName(String path) {
		String p = path.replace('\\', '/');
		return p.substring(p.lastIndexOf('/') + 1);
	}

	private static int parseInt(String s) {
		try {
			return (int) Math.round(Double.parseDouble(s.trim()));
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	/** Rows of an nvidia-smi CSV query, or null if it can't be run. */
	private static List<String[]> query(String what) {
		try {
			Process p = new ProcessBuilder(executable(), what, "--format=csv,noheader,nounits").redirectErrorStream(true)
					.start();
			List<String[]> rows = new ArrayList<>();
			try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = r.readLine()) != null) {
					if (line.trim().isEmpty()) continue;
					String[] parts = line.split(",");
					for (int i = 0; i < parts.length; i++) parts[i] = parts[i].trim();
					rows.add(parts);
				}
			}
			if (!p.waitFor(10, TimeUnit.SECONDS)) {
				p.destroyForcibly();
				return null;
			}
			return p.exitValue() == 0 ? rows : null;
		} catch (Exception e) {
			return null;
		}
	}
}
