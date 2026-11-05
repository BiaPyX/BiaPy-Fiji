package io.github.biapyx.fiji;

import java.awt.Component;
import java.awt.Container;
import java.io.File;
import java.util.function.Predicate;

import javax.swing.AbstractButton;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import io.github.biapyx.fiji.gui.MainPanel;

/**
 * Drives a full Wizard session headlessly (semantic segmentation 2D, train + test) and takes screenshots.
 * Usage: WizardFlowHarness <output dir> <dataset dir with train/x train/y test/x test/y> <yaml dir>
 */
public class WizardFlowHarness {

	static MainPanel p;
	static File out;
	static int shot = 0;

	public static void main(String[] args) throws Exception {
		out = new File(args[0]);
		String data = args[1];
		String yamlDir = args[2];
		SwingUtilities.invokeAndWait(() -> {
			p = new MainPanel();
			p.refreshEnvInfo();
			p.showPage(MainPanel.WIZARD);
		});
		Thread.sleep(10000);
		edt(() -> ((JTextField) find(c -> c instanceof JTextField && ((JTextField) c).getText().equals(System.getProperty("user.home")))).setText(yamlDir));
		edt(() -> ((JTextField) find(c -> c instanceof JTextField && ((JTextField) c).getText().equals("my_workflow"))).setText("fiji_wizard_test"));
		snap("start");
		click("Start");
		snap("q1");
		click("No");
		click("Continue >");
		snap("q2_workflow");
		click("Go to help");
		snap("q2_help");
		answer("Generate masks of different objects/regions within the image");
		answer("Train and test the model");
		path(data + "/train/x");
		snap("train_raw_checked");
		path(data + "/train/y");
		path(data + "/test/x");
		answer("Yes");
		path(data + "/test/y");
		answer("No, I want to build a model from scratch");
		answer("Quick: to check that everything works or to try BiaPy");
		snap("summary");
		click("Create configuration file");
		waitIdle();
		Thread.sleep(2000);
		snap("run");
		System.exit(0);
	}

	static void answer(String text) throws Exception {
		click(text);
		click("Continue >");
	}

	/** Sets the folder, checks that the question can't be left before its data is checked, and checks it. */
	static void path(String folder) throws Exception {
		edt(() -> ((JTextField) find(c -> c instanceof JTextField && ((JTextField) c).isEditable())).setText(folder));
		JTextField field = (JTextField) find(c -> c instanceof JTextField && ((JTextField) c).isEditable());
		click("Continue >");
		if (find(c -> c == field) == null) throw new IllegalStateException("Left the question of " + folder + " unchecked");
		snap("unchecked_" + new File(folder).getName());
		click("Check data");
		waitIdle();
		click("Continue >");
	}

	/**
	 * Waits until the Continue button stays enabled (bridge calls finished; a data check is followed by its
	 * preview, so a single idle moment is not enough).
	 */
	static void waitIdle() throws Exception {
		Thread.sleep(500);
		int idle = 0;
		for (int i = 0; i < 600 && idle < 3; i++) {
			final boolean[] busy = { false };
			edt(() -> {
				Component b = find(c -> c instanceof AbstractButton && "Continue >".equals(((AbstractButton) c).getText()));
				busy[0] = b != null && !b.isEnabled();
			});
			idle = busy[0] ? 0 : idle + 1;
			Thread.sleep(700);
		}
	}

	static void click(String text) throws Exception {
		edt(() -> {
			AbstractButton b = (AbstractButton) find(c -> c instanceof AbstractButton && text.equals(((AbstractButton) c).getText()));
			if (b == null) throw new IllegalStateException("Button not found: " + text);
			b.doClick(0);
		});
		Thread.sleep(300);
	}

	static void snap(String name) throws Exception {
		edt(() -> ScreenshotHarness.save(p, new File(out, String.format("flow_%02d_%s.png", shot++, name))));
	}

	static void edt(Runnable r) throws Exception {
		SwingUtilities.invokeAndWait(r);
	}

	static Component find(Predicate<Component> pred) {
		return find(p, pred);
	}

	/** All visible components of a type, in layout order. */
	@SuppressWarnings("unchecked")
	static <T> T[] findAll(Class<T> type) {
		java.util.List<T> out = new java.util.ArrayList<>();
		collect(p, type, out);
		return out.toArray((T[]) java.lang.reflect.Array.newInstance(type, 0));
	}

	@SuppressWarnings("unchecked")
	private static <T> void collect(Container root, Class<T> type, java.util.List<T> out) {
		for (Component c : root.getComponents()) {
			if (!c.isVisible()) continue;
			if (type.isInstance(c)) out.add((T) c);
			if (c instanceof Container) collect((Container) c, type, out);
		}
	}

	/** Depth-first search through visible components only. */
	static Component find(Container root, Predicate<Component> pred) {
		for (Component c : root.getComponents()) {
			if (!c.isVisible()) continue;
			if (pred.test(c)) return c;
			if (c instanceof Container) {
				Component r = find((Container) c, pred);
				if (r != null) return r;
			}
		}
		return null;
	}
}
