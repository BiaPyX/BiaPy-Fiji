package io.github.biapyx.fiji;

import java.awt.event.MouseEvent;
import java.io.File;
import java.util.Arrays;

import javax.swing.JLabel;
import javax.swing.SwingUtilities;

import io.github.biapyx.fiji.gui.MainPanel;

/** A fake run (a shell printing lines) to check the run tab with its output shown. */
public class OutputBoxHarness {

	public static void main(String[] args) throws Exception {
		WizardFlowHarness.out = new File(args[0]);
		SwingUtilities.invokeAndWait(() -> {
			WizardFlowHarness.p = new MainPanel();
			WizardFlowHarness.p.startJob("fake_run", new File(args[1]), new File(args[0], "fake_run"), "CPU",
					Arrays.asList("sh", "-c", "for i in 1 2 3 4 5 6 7 8; do echo \"[10:00:0$i.000] line $i of the output\"; echo; done; exit 1"));
		});
		Thread.sleep(3000);
		SwingUtilities.invokeAndWait(() -> {
			JLabel l = (JLabel) WizardFlowHarness.find(c -> c instanceof JLabel && "Hide BiaPy output".equals(((JLabel) c).getText()));
			System.out.println("Output opened automatically after failure: " + (l != null));
		});
		WizardFlowHarness.snap("output_after_failure");
		SwingUtilities.invokeAndWait(() -> {
			JLabel l = (JLabel) WizardFlowHarness.find(c -> c instanceof JLabel && "Hide BiaPy output".equals(((JLabel) c).getText()));
			l.dispatchEvent(new MouseEvent(l, MouseEvent.MOUSE_PRESSED, 0, 0, 1, 1, 1, false));
		});
		WizardFlowHarness.snap("output_hidden");
		System.exit(0);
	}
}
