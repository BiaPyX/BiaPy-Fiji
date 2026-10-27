package io.github.biapyx.fiji;

import java.io.File;

import javax.swing.SwingUtilities;

import io.github.biapyx.fiji.env.PythonEnv;
import io.github.biapyx.fiji.gui.MainPanel;

/**
 * Runs the real installation from the HOME tab headlessly, taking screenshots while it progresses.
 * Usage (with -Dij.dir=<fake Fiji dir>): InstallHarness <screenshot dir>
 */
public class InstallHarness {

	public static void main(String[] args) throws Exception {
		File out = new File(args[0]);
		out.mkdirs();
		System.out.println("Install root: " + PythonEnv.getManagedRoot());
		SwingUtilities.invokeAndWait(() -> {
			WizardFlowHarness.p = new MainPanel();
			WizardFlowHarness.p.refreshEnvInfo();
		});
		Thread.sleep(3000);
		WizardFlowHarness.click("Install BiaPy");
		long t0 = System.currentTimeMillis();
		int i = 0;
		while (System.currentTimeMillis() - t0 < 60 * 60 * 1000) {
			Thread.sleep(20000);
			final int n = i++;
			SwingUtilities.invokeAndWait(() -> ScreenshotHarness.save(WizardFlowHarness.p, new File(out, String.format("install_%03d.png", n))));
			final boolean[] busy = { false };
			SwingUtilities.invokeAndWait(() -> busy[0] = WizardFlowHarness.find(c -> c instanceof javax.swing.AbstractButton
					&& "Cancel installation".equals(((javax.swing.AbstractButton) c).getText())) != null);
			if (!busy[0]) break;
		}
		Thread.sleep(30000); // let the env check finish
		SwingUtilities.invokeAndWait(() -> ScreenshotHarness.save(WizardFlowHarness.p, new File(out, "install_final.png")));
		System.out.println("ENV: " + WizardFlowHarness.p.getEnvInfo());
		System.exit(0);
	}
}
