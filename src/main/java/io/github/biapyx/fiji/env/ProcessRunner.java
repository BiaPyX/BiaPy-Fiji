package io.github.biapyx.fiji.env;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Runs an external process (normally the Python of the BiaPy environment), streaming every output line
 * (stdout and stderr merged) to a consumer. The process can be cancelled from another thread.
 */
public class ProcessRunner {

	private final List<String> command;
	private final Map<String, String> extraEnv;
	private final File workingDir;
	private volatile Process process;
	private volatile boolean cancelled = false;

	public ProcessRunner(List<String> command, Map<String, String> extraEnv, File workingDir) {
		this.command = command;
		this.extraEnv = extraEnv;
		this.workingDir = workingDir;
	}

	/**
	 * Starts the process and blocks until it finishes.
	 *
	 * @param lineConsumer receives each output line; tqdm-style carriage returns are split into lines too
	 * @return the exit code of the process
	 */
	public int run(Consumer<String> lineConsumer) throws IOException, InterruptedException {
		ProcessBuilder pb = new ProcessBuilder(command);
		pb.redirectErrorStream(true);
		if (workingDir != null) pb.directory(workingDir);
		if (extraEnv != null) PythonEnv.applyEnvironment(pb.environment(), extraEnv);
		process = pb.start();
		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
			StringBuilder sb = new StringBuilder();
			int c;
			while ((c = reader.read()) != -1) {
				if (c == '\n' || c == '\r') {
					if (sb.length() > 0) lineConsumer.accept(sb.toString());
					sb.setLength(0);
				} else {
					sb.append((char) c);
				}
			}
			if (sb.length() > 0) lineConsumer.accept(sb.toString());
		}
		return process.waitFor();
	}

	/** Kills the process and every child it spawned (e.g. data-loader workers of PyTorch). */
	public void cancel() {
		cancelled = true;
		Process p = process;
		if (p == null) return;
		Processes.killTree(p);
	}

	public boolean isCancelled() {
		return cancelled;
	}
}
