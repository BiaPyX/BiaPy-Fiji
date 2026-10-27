package io.github.biapyx.fiji.env;

import java.util.Arrays;

/** Prints the pip configuration seen by the installer's pip (no network). Run with -Dbiapy.python=... */
public class PipEnvironmentCheck {

	public static void main(String[] args) throws Exception {
		ProcessRunner r = new ProcessRunner(
				Arrays.asList(PythonEnv.getPython().getAbsolutePath(), "-m", "pip", "config", "list"),
				BiaPyEnvManager.pipEnvironment(), null);
		System.out.println("pip config seen by the installer:");
		int code = r.run(l -> System.out.println("  " + l));
		System.out.println("(exit " + code + ")");
	}
}
