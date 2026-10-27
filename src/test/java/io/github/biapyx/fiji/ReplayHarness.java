package io.github.biapyx.fiji;

import java.io.File;
import java.util.Arrays;

import javax.swing.SwingUtilities;

import io.github.biapyx.fiji.gui.MainPanel;

/** Replays a real BiaPy log as a fake run to check the run tab's progress box. */
public class ReplayHarness {

	public static void main(String[] args) throws Exception {
		WizardFlowHarness.out = new File(args[0]);
		SwingUtilities.invokeAndWait(() -> WizardFlowHarness.p = new MainPanel());
		SwingUtilities.invokeAndWait(() -> WizardFlowHarness.p.startJob("replay", new File(args[1]),
				new File(args[0], "replay"), "GPU 0", Arrays.asList("sh", "-c", "cat \"$0\"; sleep " + System.getProperty("hold", "0"), args[2])));
		Thread.sleep(5000);
		WizardFlowHarness.snap("replay_finished");
		System.exit(0);
	}
}
