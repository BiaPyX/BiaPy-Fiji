package io.github.biapyx.fiji.env;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Operating system the plugin runs on. */
public final class Platform {

	private static final String OS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
	private static Boolean rosetta;

	private Platform() {}

	public static boolean isWindows() {
		return OS.startsWith("windows");
	}

	public static boolean isMacOS() {
		return OS.startsWith("mac");
	}

	/**
	 * Whether this is an Intel Java running on Apple Silicon through Rosetta, where the arm64 builds (e.g. of
	 * micromamba) must be used anyway.
	 */
	public static synchronized boolean isRosetta() {
		if (rosetta == null) {
			rosetta = false;
			if (isMacOS() && !System.getProperty("os.arch", "").matches("aarch64|arm64")) {
				try {
					Process p = new ProcessBuilder("sysctl", "-in", "sysctl.proc_translated").redirectErrorStream(true).start();
					try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
						String line = r.readLine();
						rosetta = line != null && line.trim().equals("1");
					}
					p.waitFor();
				} catch (Exception e) {
					// Not translated, or sysctl not available
				}
			}
		}
		return rosetta;
	}
}
