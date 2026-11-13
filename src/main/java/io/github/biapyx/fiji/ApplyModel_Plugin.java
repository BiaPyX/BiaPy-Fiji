package io.github.biapyx.fiji;

import ij.plugin.PlugIn;
import io.github.biapyx.fiji.gui.ApplyModelWindow;

/** Entry point: {@code Plugins > BiaPy > Apply model}, to an image open in Fiji. */
public class ApplyModel_Plugin implements PlugIn {

	@Override
	public void run(String arg) {
		ApplyModelWindow.open();
	}

	/** Launches it inside a fresh ImageJ instance, for development. */
	public static void main(String[] args) {
		new ij.ImageJ();
		new ApplyModel_Plugin().run("");
	}
}
