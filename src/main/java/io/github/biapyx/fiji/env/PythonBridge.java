package io.github.biapyx.fiji.env;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.github.biapyx.fiji.BiaPyFiji;

/**
 * Runs commands of {@code biapy_fiji_bridge.py} in the BiaPy Python environment. The script is shipped inside
 * the jar and extracted next to the environment; the wizard's logic is BiaPy's own ({@code biapy.wizard}).
 * <p>
 * The wizard's commands go to a Python process kept alive between them ({@code biapy_fiji_bridge.py serve}),
 * started with {@link #warmUp()} as soon as the environment is known so it imports BiaPy and PyTorch (several
 * seconds) while the user answers. The commands that check the environment itself run in a process of their own.
 */
public class PythonBridge {

	private static final String RESULT_PREFIX = "BIAPY_FIJI_RESULT:";
	private static final String RES = "/biapy/python/";
	private static final String[] FILES = { "biapy_fiji_bridge.py", "biapy_compat.py", "biapy_run.py" };
	/** Commands run by the server: the ones of the wizard, which need BiaPy imported. */
	private static final Set<String> SERVED = new HashSet<>(
			Arrays.asList("check_data", "preview_data", "find_models", "build_config", "read_config", "citations",
					"estimate_resources", "workflow_info"));

	private static final Gson GSON = new Gson();
	/** The server runs one command at a time (not Server.class, which cancel() needs meanwhile). */
	private static final Object SERVER_CALLS = new Object();
	private static File extractedScript;

	private volatile ProcessRunner runner;
	private volatile boolean usingServer;

	/** Error raised when the Python side reports a failure. */
	public static class BridgeException extends Exception {
		private static final long serialVersionUID = 1L;

		public BridgeException(String msg) {
			super(msg);
		}
	}

	/**
	 * Runs a bridge command and returns its "result" object.
	 *
	 * @param command one of the commands defined in the bridge script
	 * @param args arguments (may be {@code null})
	 * @param log receives every log line printed by Python (may be {@code null})
	 */
	public JsonObject call(String command, JsonObject args, Consumer<String> log) throws Exception {
		if (!PythonEnv.pythonExists())
			throw new BridgeException("BiaPy Python environment not found. Install it from the HOME tab.");
		String resultLine = SERVED.contains(command) ? callServer(command, args, log) : callProcess(command, args, log);
		JsonObject res = JsonParser.parseString(resultLine).getAsJsonObject();
		if (!res.get("ok").getAsBoolean()) throw new BridgeException(res.get("error").getAsString());
		JsonElement r = res.get("result");
		return r == null || r.isJsonNull() ? new JsonObject() : r.getAsJsonObject();
	}

	/** Runs the command in a Python process of its own. */
	private String callProcess(String command, JsonObject args, Consumer<String> log) throws Exception {
		List<String> cmd = PythonEnv.pythonCommand();
		cmd.add(extractScripts().getAbsolutePath());
		cmd.add(command);
		File argsFile = null;
		if (args != null) {
			argsFile = File.createTempFile("biapy_fiji_", ".json");
			Files.write(argsFile.toPath(), GSON.toJson(args).getBytes(StandardCharsets.UTF_8));
			cmd.add("--json");
			cmd.add(argsFile.getAbsolutePath());
		}
		final String[] resultLine = { null };
		try {
			runner = new ProcessRunner(cmd, PythonEnv.environment(), null);
			int code = runner.run(line -> {
				if (line.startsWith(RESULT_PREFIX)) resultLine[0] = line.substring(RESULT_PREFIX.length());
				else if (log != null) log.accept(line);
			});
			if (runner.isCancelled()) throw new InterruptedException("Canceled");
			if (resultLine[0] == null)
				throw new BridgeException("Python process ended (exit code " + code + ") without returning a result.");
		} finally {
			if (argsFile != null) argsFile.delete();
		}
		return resultLine[0];
	}

	/** Runs the command in the server, starting it if needed. One command at a time. */
	private String callServer(String command, JsonObject args, Consumer<String> log) throws Exception {
		synchronized (SERVER_CALLS) {
			Server s = Server.get();
			usingServer = true;
			try {
				return s.call(command, args, log);
			} finally {
				usingServer = false;
			}
		}
	}

	/** Stops the running command. A command in the server stops the server, which restarts with the next one. */
	public void cancel() {
		ProcessRunner r = runner;
		if (r != null) r.cancel();
		if (usingServer) Server.stop(true);
	}

	/** Starts the server in the background (if it is not running), so the wizard's first command is fast. */
	public static void warmUp() {
		if (!PythonEnv.pythonExists()) return;
		Thread t = new Thread(() -> {
			try {
				Server.get();
			} catch (Exception e) {
				// Reported by the first command that needs it
			}
		}, "BiaPy-python-warm-up");
		t.setDaemon(true);
		t.start();
	}

	/** Stops the server, e.g. before the environment changes (it starts again with the next command). */
	public static void shutdown() {
		Server.stop(false);
	}

	/** A Python file shipped with the plugin (e.g. "biapy_run.py"), extracted next to the bridge. */
	public static File script(String name) throws IOException {
		return new File(extractScripts().getParentFile(), name);
	}

	/** Copies the Python sources out of the jar (once per Fiji session). */
	private static synchronized File extractScripts() throws IOException {
		if (extractedScript != null && extractedScript.isFile()) return extractedScript;
		File dir = new File(PythonEnv.getManagedRoot(), "bridge-" + BiaPyFiji.PLUGIN_VERSION);
		for (String f : FILES) {
			File out = new File(dir, f);
			out.getParentFile().mkdirs();
			try (InputStream in = PythonBridge.class.getResourceAsStream(RES + f)) {
				if (in == null) throw new IOException("Resource not found in the plugin jar: " + RES + f);
				Files.copy(in, out.toPath(), StandardCopyOption.REPLACE_EXISTING);
			}
		}
		extractedScript = new File(dir, FILES[0]);
		return extractedScript;
	}

	/**
	 * The {@code serve} process: requests are written to its stdin, one JSON per line, and its output lines are
	 * queued by a reader thread until the result line of the request.
	 */
	private static final class Server {

		/** Queued when the process ends (no output line can be null). */
		private static final String EOF = new String("EOF");
		private static Server current;
		private static boolean hookAdded;

		private final Process process;
		private final OutputStream stdin;
		private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
		private volatile boolean killed;

		private Server(Process process) {
			this.process = process;
			this.stdin = process.getOutputStream();
			Thread reader = new Thread(this::read, "BiaPy-python-server-output");
			reader.setDaemon(true);
			reader.start();
		}

		static synchronized Server get() throws IOException {
			if (current != null && current.process.isAlive()) return current;
			List<String> cmd = PythonEnv.pythonCommand();
			cmd.add(extractScripts().getAbsolutePath());
			cmd.add("serve");
			ProcessBuilder pb = new ProcessBuilder(cmd);
			pb.redirectErrorStream(true);
			PythonEnv.applyEnvironment(pb.environment(), PythonEnv.environment());
			current = new Server(pb.start());
			if (!hookAdded) {
				Runtime.getRuntime().addShutdownHook(new Thread(() -> stop(false)));
				hookAdded = true;
			}
			return current;
		}

		static synchronized void stop(boolean cancelled) {
			Server s = current;
			current = null;
			if (s == null) return;
			s.killed = cancelled;
			Processes.killTree(s.process);
		}

		String call(String command, JsonObject args, Consumer<String> log) throws Exception {
			lines.clear(); // output of the warm-up or of a previous command
			JsonObject request = new JsonObject();
			request.addProperty("command", command);
			request.add("args", args == null ? new JsonObject() : args);
			try {
				stdin.write((GSON.toJson(request) + "\n").getBytes(StandardCharsets.UTF_8));
				stdin.flush();
			} catch (IOException e) {
				throw new BridgeException("The Python process has stopped: " + e.getMessage());
			}
			while (true) {
				String line = lines.take();
				if (line == EOF) {
					if (killed) throw new InterruptedException("Canceled");
					throw new BridgeException("Python process ended (exit code " + process.waitFor()
							+ ") without returning a result.");
				}
				if (line.startsWith(RESULT_PREFIX)) return line.substring(RESULT_PREFIX.length());
				if (log != null) log.accept(line);
			}
		}

		/** Same line splitting as {@link ProcessRunner} (tqdm-style carriage returns are lines too). */
		private void read() {
			try (BufferedReader reader = new BufferedReader(
					new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
				StringBuilder sb = new StringBuilder();
				int c;
				while ((c = reader.read()) != -1) {
					if (c == '\n' || c == '\r') {
						if (sb.length() > 0) lines.add(sb.toString());
						sb.setLength(0);
					} else {
						sb.append((char) c);
					}
				}
				if (sb.length() > 0) lines.add(sb.toString());
			} catch (IOException e) {
				// The process was killed
			}
			lines.add(EOF);
		}
	}
}
