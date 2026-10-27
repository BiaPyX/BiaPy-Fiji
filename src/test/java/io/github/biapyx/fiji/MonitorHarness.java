package io.github.biapyx.fiji;

import java.io.File;

import javax.swing.SwingUtilities;

import io.github.biapyx.fiji.gui.TrainingMonitor;

/** Renders the training monitor of an existing (finished) job folder: out dir, train_pred_samples dir [, png name]. */
public class MonitorHarness {

	static void layoutAll(java.awt.Component c) {
		if (c instanceof java.awt.Container) {
			((java.awt.Container) c).doLayout();
			for (java.awt.Component ch : ((java.awt.Container) c).getComponents()) layoutAll(ch);
		}
	}

	public static void main(String[] args) throws Exception {
		File out = new File(args[0]);
		// start time 0: accept all files; no "before" logs because the snapshot is taken in the constructor,
		// so point it to a job whose logs dir is read after construction
		TrainingMonitor[] m = new TrainingMonitor[1];
		SwingUtilities.invokeAndWait(() -> m[0] = new TrainingMonitor(new File("/nonexistent"), "x", true, true, false));
		java.lang.reflect.Field f = TrainingMonitor.class.getDeclaredField("previewDir");
		f.setAccessible(true);
		f.set(m[0], new File(args[1]));
		SwingUtilities.invokeAndWait(() -> {
			WizardFlowHarness.p = null;
			javax.swing.JLabel l = (javax.swing.JLabel) WizardFlowHarness.find(m[0], c -> c instanceof javax.swing.JLabel
					&& "Show training predictions".equals(((javax.swing.JLabel) c).getText()));
			l.dispatchEvent(new java.awt.event.MouseEvent(l, java.awt.event.MouseEvent.MOUSE_PRESSED, 0, 0, 1, 1, 1, false));
		});
		m[0].refresh();
		Thread.sleep(1500);
		String name = args.length > 2 ? args[2] : "monitor_previews.png";
		SwingUtilities.invokeAndWait(() -> {
			m[0].setSize(720, 470);
			layoutAll(m[0]);
			int h = Math.max(470, m[0].getPreferredSize().height);
			m[0].setSize(720, h);
			layoutAll(m[0]);
			java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(720, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
			java.awt.Graphics2D g = img.createGraphics();
			g.setColor(java.awt.Color.WHITE);
			g.fillRect(0, 0, 720, h);
			layoutAll(m[0]);
			m[0].printAll(g);
			g.dispose();
			try {
				javax.imageio.ImageIO.write(img, "png", new File(out, name));
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		});
		System.exit(0);
	}
}
