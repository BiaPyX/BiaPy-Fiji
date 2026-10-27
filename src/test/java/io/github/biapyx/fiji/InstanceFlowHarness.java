package io.github.biapyx.fiji;

import java.io.File;

import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import io.github.biapyx.fiji.gui.MainPanel;

/**
 * Drives an instance segmentation Wizard session headlessly (2D, train only) up to the instance representation
 * question, whose options depend on the shape of the instances of the training masks, and creates the
 * configuration with the recommended representation. Takes screenshots along the way.
 * Usage: InstanceFlowHarness <output dir> <raw images dir> <instance masks dir> <yaml dir>
 */
public class InstanceFlowHarness {

	public static void main(String[] args) throws Exception {
		WizardFlowHarness.out = new File(args[0]);
		String raw = args[1], masks = args[2], yamlDir = args[3];
		SwingUtilities.invokeAndWait(() -> {
			WizardFlowHarness.p = new MainPanel();
			WizardFlowHarness.p.refreshEnvInfo();
			WizardFlowHarness.p.showPage(MainPanel.WIZARD);
		});
		Thread.sleep(10000);
		WizardFlowHarness.edt(() -> ((JTextField) WizardFlowHarness.find(c -> c instanceof JTextField
				&& ((JTextField) c).getText().equals(System.getProperty("user.home")))).setText(yamlDir));
		WizardFlowHarness.edt(() -> ((JTextField) WizardFlowHarness.find(c -> c instanceof JTextField
				&& ((JTextField) c).getText().equals("my_workflow"))).setText("fiji_instance_test"));
		WizardFlowHarness.click("Start");
		WizardFlowHarness.click("No");
		WizardFlowHarness.click("Continue >");
		WizardFlowHarness.answer("Generate masks for each object in the image");
		WizardFlowHarness.answer("No, I want to build a model from scratch");
		WizardFlowHarness.answer("Train a model");
		WizardFlowHarness.path(raw);
		WizardFlowHarness.path(masks);
		WizardFlowHarness.snap("representation");
		WizardFlowHarness.click("Go to help");
		WizardFlowHarness.snap("representation_help");
		// Pick the recommended representation (the first card)
		javax.swing.JRadioButton[] options = WizardFlowHarness.findAll(javax.swing.JRadioButton.class);
		String first = options[0].getText();
		WizardFlowHarness.click(first);
		WizardFlowHarness.snap("representation_selected");
		WizardFlowHarness.click("Continue >");
		WizardFlowHarness.snap("summary");
		WizardFlowHarness.click("Create configuration file");
		WizardFlowHarness.waitIdle();
		Thread.sleep(2000);
		System.exit(0);
	}
}
