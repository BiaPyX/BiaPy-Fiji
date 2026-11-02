package io.github.biapyx.fiji.env;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Kills a process together with every process it started (e.g. PyTorch's data loader workers or the processes of a
 * distributed run), which would otherwise keep running and holding the GPU. Fiji-Stable runs on Java 8, which has no
 * {@code ProcessHandle}: there the process tree is found with {@code ps} (Linux, macOS) or killed with
 * {@code taskkill /T} (Windows).
 */
public final class Processes {

	private Processes() {}

	public static void killTree(Process p) {
		if (!killDescendantsJava9(p)) {
			Long pid = pid(p);
			if (pid != null) {
				if (Platform.isWindows()) run("taskkill", "/PID", String.valueOf(pid), "/T", "/F");
				else for (long child : descendantsUnix(pid)) run("kill", "-9", String.valueOf(child));
			}
		}
		p.destroyForcibly();
	}

	/** Java 9+: kills the descendants with ProcessHandle. False on Java 8. */
	private static boolean killDescendantsJava9(Process p) {
		try {
			Method descendants = Process.class.getMethod("descendants");
			Method destroy = Class.forName("java.lang.ProcessHandle").getMethod("destroyForcibly");
			try (Stream<?> handles = (Stream<?>) descendants.invoke(p)) {
				handles.forEach(h -> {
					try {
						destroy.invoke(h);
					} catch (Exception e) {
						// Already gone
					}
				});
			}
			return true;
		} catch (NoSuchMethodException | ClassNotFoundException e) {
			return false;
		} catch (Exception e) {
			return true;
		}
	}

	/** Process id on Java 8 (the "pid" field on Linux and macOS, the process handle on Windows), or null. */
	private static Long pid(Process p) {
		try {
			if (Platform.isWindows()) {
				Field handle = p.getClass().getDeclaredField("handle");
				handle.setAccessible(true);
				return (long) Kernel32.INSTANCE.GetProcessId(new com.sun.jna.Pointer(handle.getLong(p)));
			}
			Field pid = p.getClass().getDeclaredField("pid");
			pid.setAccessible(true);
			return (long) pid.getInt(p);
		} catch (Throwable e) {
			return null;
		}
	}

	private interface Kernel32 extends com.sun.jna.Library {
		Kernel32 INSTANCE = com.sun.jna.Native.load("kernel32", Kernel32.class);

		int GetProcessId(com.sun.jna.Pointer process);
	}

	/** Every process started by {@code pid}, its children first (from {@code ps}). */
	private static List<Long> descendantsUnix(long pid) {
		Map<Long, List<Long>> children = new HashMap<>();
		try {
			Process ps = new ProcessBuilder("ps", "-A", "-o", "pid=", "-o", "ppid=").redirectErrorStream(true).start();
			try (BufferedReader r = new BufferedReader(new InputStreamReader(ps.getInputStream(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = r.readLine()) != null) {
					String[] f = line.trim().split("\\s+");
					if (f.length == 2) children.computeIfAbsent(Long.parseLong(f[1]), k -> new ArrayList<>()).add(Long.parseLong(f[0]));
				}
			}
			ps.waitFor();
		} catch (Exception e) {
			return new ArrayList<>();
		}
		List<Long> out = new ArrayList<>();
		Deque<Long> todo = new ArrayDeque<>(children.getOrDefault(pid, new ArrayList<>()));
		while (!todo.isEmpty()) {
			long c = todo.poll();
			out.add(c);
			todo.addAll(children.getOrDefault(c, new ArrayList<>()));
		}
		return out;
	}

	private static void run(String... cmd) {
		try {
			new ProcessBuilder(cmd).redirectErrorStream(true).start().waitFor();
		} catch (Exception e) {
			// Best effort
		}
	}
}
