package io.github.biapyx.fiji;

import java.awt.event.MouseEvent;
import java.io.File;
import java.util.List;

import javax.swing.JLabel;
import javax.swing.SwingUtilities;

import io.github.biapyx.fiji.env.PythonEnv;
import io.github.biapyx.fiji.gui.MainPanel;

/** Real BiaPy run with the training predictions section open. Usage: StripHarness <shots> <config> <out dir> <gpu> */
public class StripHarness {

	public static void main(String[] args) throws Exception {
		WizardFlowHarness.out = new File(args[0]);
		List<String> cmd = PythonEnv.pythonCommand();
		cmd.addAll(java.util.Arrays.asList("-c", "import sys, biapy; sys.argv[0] = 'biapy'; biapy.main()", "--config", args[1],
				"--result_dir", args[2], "--name", "strip_test", "--run_id", "1", "--gpu", args[3]));
		SwingUtilities.invokeAndWait(() -> {
			WizardFlowHarness.p = new MainPanel();
			WizardFlowHarness.p.startJob("strip_test", new File(args[1]), new File(args[2], "strip_test"), "GPU " + args[3], cmd);
		});
		SwingUtilities.invokeAndWait(() -> {
			JLabel l = (JLabel) WizardFlowHarness.find(c -> c instanceof JLabel && "Show training predictions".equals(((JLabel) c).getText()));
			l.dispatchEvent(new MouseEvent(l, MouseEvent.MOUSE_PRESSED, 0, 0, 1, 1, 1, false));
		});
		Thread.sleep(Long.getLong("mid", 150000));
		WizardFlowHarness.snap("strip_mid");
		while (WizardFlowHarness.p.anyJobRunning()) Thread.sleep(3000);
		Thread.sleep(4000);
		WizardFlowHarness.snap("strip_end");
		System.exit(0);
	}
}
