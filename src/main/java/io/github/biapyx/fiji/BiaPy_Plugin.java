package io.github.biapyx.fiji;

import javax.swing.SwingUtilities;

import ij.plugin.PlugIn;
import io.github.biapyx.fiji.gui.MainWindow;

/** Entry point: {@code Plugins > BiaPy > Apply entire workflow}. Only one BiaPy window exists per Fiji session. */
public class BiaPy_Plugin implements PlugIn {

	private static MainWindow window;

	@Override
	public void run(String arg) {
		SwingUtilities.invokeLater(() -> {
			if (window == null) window = new MainWindow();
			window.setVisible(true);
			window.toFront();
		});
	}

	/** Launches the plugin inside a fresh ImageJ instance, for development. */
	public static void main(String[] args) {
		new ij.ImageJ();
		new BiaPy_Plugin().run("");
	}
}
