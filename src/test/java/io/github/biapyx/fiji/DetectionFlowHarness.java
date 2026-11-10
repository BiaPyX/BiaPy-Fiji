package io.github.biapyx.fiji;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import io.github.biapyx.fiji.gui.MainPanel;

/**
 * Drives a 3D detection Wizard session headlessly (train only) with images without voxel size in their metadata: the
 * voxel size question appears after the raw images are checked, and the point size questions before the training
 * length. Fails if the configuration does not get the voxel size and radii typed. Takes screenshots.
 * Usage: DetectionFlowHarness <output dir> <raw 3D images dir (TIF, no calibration)> <CSV points dir> <yaml dir>
 */
public class DetectionFlowHarness {

	public static void main(String[] args) throws Exception {
		WizardFlowHarness.out = new File(args[0]);
		String raw = args[1], points = args[2], yamlDir = args[3];
		SwingUtilities.invokeAndWait(() -> {
			WizardFlowHarness.p = new MainPanel();
			WizardFlowHarness.p.refreshEnvInfo();
			WizardFlowHarness.p.showPage(MainPanel.WIZARD);
		});
		Thread.sleep(10000);
		WizardFlowHarness.edt(() -> ((JTextField) WizardFlowHarness.find(c -> c instanceof JTextField
				&& ((JTextField) c).getText().equals(System.getProperty("user.home")))).setText(yamlDir));
		WizardFlowHarness.edt(() -> ((JTextField) WizardFlowHarness.find(c -> c instanceof JTextField
				&& ((JTextField) c).getText().equals("my_workflow"))).setText("fiji_detection_test"));
		WizardFlowHarness.click("Start");
		WizardFlowHarness.answer("3D images (volumes or stacks of slices)");
		WizardFlowHarness.answer("Locate objects using points, for example, to identify the centres of nuclei (object detection)");
		WizardFlowHarness.answer("Train a new AI model using my images");
		WizardFlowHarness.path(raw);
		WizardFlowHarness.snap("voxel_size");
		JTextField[] fields = WizardFlowHarness.findAll(JTextField.class);
		WizardFlowHarness.edt(() -> {
			fields[0].setText("2");
			fields[1].setText("0.5");
			fields[2].setText("abc");
		});
		WizardFlowHarness.click("Continue >");
		if (WizardFlowHarness.find(c -> c == fields[0]) == null) throw new IllegalStateException("Left with a wrong voxel size");
		WizardFlowHarness.edt(() -> fields[2].setText("0,5"));
		WizardFlowHarness.click("Continue >");
		WizardFlowHarness.path(points);
		WizardFlowHarness.answer("No, I want to build a model from scratch");
		// Automatic radius drawn on the training points, then a manual one (Z left empty: scaled by the voxel size)
		WizardFlowHarness.click("Show the points");
		WizardFlowHarness.waitIdle();
		WizardFlowHarness.snap("point_size_auto");
		WizardFlowHarness.click("Manual:");
		JTextField[] radius = WizardFlowHarness.findAll(JTextField.class);
		WizardFlowHarness.edt(() -> radius[0].setText("3"));
		WizardFlowHarness.click("Show the points");
		WizardFlowHarness.waitIdle();
		WizardFlowHarness.snap("point_size_manual");
		if (WizardFlowHarness.find(c -> c instanceof javax.swing.text.JTextComponent
				&& ((javax.swing.text.JTextComponent) c).getText().equals("Radius: 3 pixels, 1 slice along Z.")) == null)
			throw new IllegalStateException("The points were not drawn with the radius typed");
		WizardFlowHarness.click("< Back");
		WizardFlowHarness.click("Continue >");
		if (WizardFlowHarness.find(c -> c instanceof javax.swing.text.JTextComponent
				&& ((javax.swing.text.JTextComponent) c).getText().equals("Radius: 3 pixels, 1 slice along Z.")) == null)
			throw new IllegalStateException("The points drawn were not kept after leaving the question");
		WizardFlowHarness.snap("point_size_back");
		WizardFlowHarness.click("Continue >");
		WizardFlowHarness.answer("Quick: 2% of the training steps, for a first try");
		WizardFlowHarness.snap("summary");
		WizardFlowHarness.click("Create configuration file");
		WizardFlowHarness.waitIdle();
		Thread.sleep(2000);
		String yaml = new String(Files.readAllBytes(new File(yamlDir, "fiji_detection_test.yaml").toPath()), StandardCharsets.UTF_8);
		if (!yaml.contains("Voxel spacing: 2x0.5x0.5") || !yaml.contains("radius 1x3x3"))
			throw new IllegalStateException("The configuration does not use the answers:\n" + yaml);
		System.out.println("ok: voxel size and point radius in the configuration");
		System.exit(0);
	}
}
