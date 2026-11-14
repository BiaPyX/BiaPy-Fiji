package io.github.biapyx.fiji;

import ij.Macro;
import ij.plugin.PlugIn;
import ij.plugin.frame.Recorder;
import io.github.biapyx.fiji.gui.ApplyModelWindow;
import io.github.biapyx.fiji.gui.ModelApplier;

/**
 * Entry point: {@code Plugins > BiaPy > Apply model}, to an image open in Fiji. From a macro or script with options it
 * applies the model without the window (see {@link ModelApplier}).
 */
public class ApplyModel_Plugin implements PlugIn {

	@Override
	public void run(String arg) {
		String options = Macro.getOptions();
		if (options != null) {
			ModelApplier.runMacro(options);
			return;
		}
		// Opening the window is not recorded: applying a model in it is
		Recorder.disableCommandRecording();
		ApplyModelWindow.open();
	}

	/** Launches it inside a fresh ImageJ instance, for development. */
	public static void main(String[] args) {
		new ij.ImageJ();
		new ApplyModel_Plugin().run("");
	}
}
