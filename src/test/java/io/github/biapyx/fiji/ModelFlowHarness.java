package io.github.biapyx.fiji;

import java.io.File;

import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import io.github.biapyx.fiji.gui.MainPanel;

/**
 * Wizard question to choose a BioImage Model Zoo model (2D instance segmentation, test only): the search of all the
 * compatible models, the search by words, the selection, and a summary with unanswered questions.
 * Usage: ModelFlowHarness <output dir> <folder with single-channel 2D test images>
 */
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
		WizardFlowHarness.answer("Generate masks for each object in the image");
		WizardFlowHarness.answer("No");
		WizardFlowHarness.answer("Test a model");
		// Skipping the data through the list of questions: the model question can't be entered
		toc("Phases");
		toc("Model source");
		WizardFlowHarness.answer("Yes, I want to check if there is a pretrained model I can use");
		if (WizardFlowHarness.find(c -> c instanceof javax.swing.AbstractButton
				&& "Find models".equals(((javax.swing.AbstractButton) c).getText())) != null)
			throw new IllegalStateException("The model question was entered without checking the data");
		WizardFlowHarness.snap("model_question_refused");
		toc("Test data (raw)");
		WizardFlowHarness.path(args[1]);
		WizardFlowHarness.answer("No");
		WizardFlowHarness.click("Continue >"); // Model source, already answered
		WizardFlowHarness.click("Find models");
		Thread.sleep(4000);
		WizardFlowHarness.snap("models_searching");
		WizardFlowHarness.waitIdle();
		Thread.sleep(8000); // covers
		WizardFlowHarness.snap("models_found");
		WizardFlowHarness.edt(() -> searchField().setText("DINO cellpose"));
		Thread.sleep(1000);
		WizardFlowHarness.snap("models_dino_cellpose");
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
		WizardFlowHarness.snap("summary");
		System.exit(0);
	}

	/** Goes to a question through the list of questions on the left (by its name there). */
	static void toc(String name) throws Exception {
		WizardFlowHarness.edt(() -> {
			javax.swing.JTree tree = (javax.swing.JTree) WizardFlowHarness.find(c -> c instanceof javax.swing.JTree);
			for (int r = 0; r < tree.getRowCount(); r++) {
				Object node = ((javax.swing.tree.DefaultMutableTreeNode) tree.getPathForRow(r).getLastPathComponent()).getUserObject();
				if (node instanceof io.github.biapyx.fiji.wizard.WizardModel.Question
						&& name.equals(((io.github.biapyx.fiji.wizard.WizardModel.Question) node).toc)) {
					tree.setSelectionRow(r);
					return;
				}
			}
			throw new IllegalStateException("Question not in the list: " + name);
		});
		Thread.sleep(500);
	}

	/** The search words field: the only editable text field of the question (the model selected is not editable). */
	static JTextField searchField() {
		return (JTextField) WizardFlowHarness.find(c -> c instanceof JTextField && ((JTextField) c).isEditable());
	}
}
