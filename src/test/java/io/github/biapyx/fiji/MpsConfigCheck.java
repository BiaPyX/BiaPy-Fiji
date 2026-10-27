package io.github.biapyx.fiji;

import java.io.File;
import java.lang.reflect.Method;

import io.github.biapyx.fiji.gui.RunPanel;

/**
 * Writes the copy of a configuration used to run it on Apple Silicon's GPU (normally only done when "MPS" is the
 * device selected) to the given path, or says that none is needed.
 */
public class MpsConfigCheck {

	public static void main(String[] args) throws Exception {
		Method m = RunPanel.class.getDeclaredMethod("mpsConfig", File.class, String.class);
		m.setAccessible(true);
		File f = (File) m.invoke(null, new File(args[0]), "check");
		if (f == null) System.out.println("No copy needed");
		else java.nio.file.Files.copy(f.toPath(), new File(args[1]).toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
	}
}
