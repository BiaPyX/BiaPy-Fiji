package io.github.biapyx.fiji.gui;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;

import javax.imageio.ImageIO;
import javax.swing.JEditorPane;
import javax.swing.SwingUtilities;

/** Renders the workflow confirmation dialog's content to PNGs. Usage: ConfirmDialogRender <dir> WORKFLOW:answer ... */
public class ConfirmDialogRender {

	public static void main(String[] args) throws Exception {
		for (int i = 1; i < args.length; i++) {
			String[] wa = args[i].split(":", 2);
			SwingUtilities.invokeAndWait(() -> {
				String html;
				if (wa[0].equals("HELP")) {
					// Wizard help of question number wa[1] (1-based), as WizardPanel shows it
					try {
						java.lang.reflect.Method rm = WizardPanel.class.getDeclaredMethod("resolveImages", String.class);
						rm.setAccessible(true);
						io.github.biapyx.fiji.wizard.WizardModel wm = new io.github.biapyx.fiji.wizard.WizardModel();
						html = "<html><body style='font-family:sans-serif; font-size:12px; margin:6px'>"
								+ rm.invoke(null, wm.getQuestion(Integer.parseInt(wa[1]) - 1).help) + "</body></html>";
					} catch (Exception e) {
						throw new RuntimeException(e);
					}
				} else {
					html = WorkflowConfirmDialog.html(wa[0], wa[1]);
				}
				JEditorPane p = UiUtils.htmlPane(html);
				p.setOpaque(true);
				p.setBackground(Color.WHITE);
				p.setSize(650, Short.MAX_VALUE);
				int h = p.getPreferredSize().height;
				p.setSize(650, h);
				BufferedImage img = new BufferedImage(650, h, BufferedImage.TYPE_INT_RGB);
				Graphics2D g = img.createGraphics();
				g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
				p.printAll(g);
				g.dispose();
				try {
					ImageIO.write(img, "png", new File(args[0], "confirm_" + wa[0] + (wa[0].equals("HELP") ? wa[1] : "") + ".png"));
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
			});
		}
		System.exit(0);
	}
}
