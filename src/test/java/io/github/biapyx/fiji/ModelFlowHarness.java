package io.github.biapyx.fiji;

import java.io.File;

import javax.swing.SwingUtilities;

import io.github.biapyx.fiji.gui.MainPanel;

/** Wizard question 5 (BioImage Model Zoo search) and a summary with unanswered questions. */
public class ModelFlowHarness {

	public static void main(String[] args) throws Exception {
		WizardFlowHarness.out = new File(args[0]);
		SwingUtilities.invokeAndWait(() -> {
			WizardFlowHarness.p = new MainPanel();
			WizardFlowHarness.p.refreshEnvInfo();
			WizardFlowHarness.p.showPage(MainPanel.WIZARD);
		});
		for (int i = 0; i < 120 && "checking".equals(WizardFlowHarness.p.getEnvState()); i++) Thread.sleep(1000);
		WizardFlowHarness.click("Start");
		WizardFlowHarness.answer("No");
		WizardFlowHarness.answer("Generate masks of different objects/regions within the image");
		WizardFlowHarness.answer("Yes, I want to check if there is a pretrained model I can use");
		WizardFlowHarness.click("Check models");
		Thread.sleep(4000);
		WizardFlowHarness.snap("models_searching");
		WizardFlowHarness.waitIdle();
		Thread.sleep(8000); // covers
		WizardFlowHarness.snap("models_found");
		WizardFlowHarness.click("Select");
		Thread.sleep(500);
		WizardFlowHarness.snap("model_selected");
		WizardFlowHarness.click("Continue >");
		WizardFlowHarness.click("< Back");
		Thread.sleep(1000);
		WizardFlowHarness.snap("models_kept");
		// Jump to the summary leaving questions unanswered
		for (int i = 0; i < 15; i++) {
			final boolean[] more = { false };
			SwingUtilities.invokeAndWait(() -> more[0] = WizardFlowHarness.find(c -> c instanceof javax.swing.AbstractButton
					&& "Continue >".equals(((javax.swing.AbstractButton) c).getText())) != null);
			if (!more[0]) break;
			WizardFlowHarness.click("Continue >");
		}
		WizardFlowHarness.snap("summary_unanswered");
		System.exit(0);
	}
}
