package io.github.biapyx.fiji;

import java.io.File;

import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import io.github.biapyx.fiji.gui.MainPanel;

/**
 * Launches two BiaPy runs at once from the RUN WORKFLOW tab and screenshots their tabs.
 * Usage: RunFlowHarness <screenshot dir> <config.yaml> <results dir>
 */
public class RunFlowHarness {

	public static void main(String[] args) throws Exception {
		WizardFlowHarness.out = new File(args[0]);
		SwingUtilities.invokeAndWait(() -> {
			WizardFlowHarness.p = new MainPanel();
			WizardFlowHarness.p.refreshEnvInfo();
			WizardFlowHarness.p.showPage(MainPanel.RUN);
		});
		MainPanel p = WizardFlowHarness.p;
		for (int i = 0; i < 120 && p.getEnvInfo() == null; i++) Thread.sleep(1000);
		String[][] jobs = System.getProperty("gpus") != null ? new String[][] { { "fiji_run_b", "GPU " + System.getProperty("gpus") } }
				: System.getProperty("single") != null ? new String[][] { { "fiji_run_b", "GPU 3" } }
				: new String[][] { { "fiji_run_a", "GPU 2" }, { "fiji_run_b", "GPU 3" } };
		for (String[] job : jobs) {
			SwingUtilities.invokeAndWait(() -> {
				p.showPage(MainPanel.RUN);
				p.getRunPanel().setConfigFile(new File(args[1]), false);
				JTextField[] fields = WizardFlowHarness.findAll(JTextField.class);
				fields[0].setText(args[1]);
				fields[1].setText(args[2]);
				fields[2].setText(job[0]);
				// "GPU 2" or "GPU 2,3" (distributed)
				String[] ids = job[1].substring(4).split(",");
				int[] gpus = new int[ids.length];
				for (int i = 0; i < ids.length; i++) gpus[i] = Integer.parseInt(ids[i].trim());
				p.getRunPanel().selectDevice("GPU", gpus);
			});
			WizardFlowHarness.click("Run BiaPy");
			Thread.sleep(2000);
		}
		WizardFlowHarness.snap("run_form");
		Thread.sleep(60000);
		WizardFlowHarness.snap("run_b_training");
		Thread.sleep(100000);
		WizardFlowHarness.snap("run_b_epoch2");
		long t0 = System.currentTimeMillis();
		while (p.anyJobRunning() && System.currentTimeMillis() - t0 < 30 * 60 * 1000) Thread.sleep(3000);
		WizardFlowHarness.snap("run_b_finished");
		SwingUtilities.invokeAndWait(() -> {
			javax.swing.JLabel l = (javax.swing.JLabel) WizardFlowHarness.find(c -> c instanceof javax.swing.JLabel
					&& "Show BiaPy output".equals(((javax.swing.JLabel) c).getText()));
			l.dispatchEvent(new java.awt.event.MouseEvent(l, java.awt.event.MouseEvent.MOUSE_PRESSED, 0, 0, 1, 1, 1, false));
		});
		Thread.sleep(1000);
		WizardFlowHarness.snap("run_b_output_shown");
		System.exit(0);
	}
}
