package io.github.biapyx.fiji;

import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

import io.github.biapyx.fiji.gui.MainPanel;

/**
 * Renders the plugin panels offscreen (works with -Djava.awt.headless=true) to check the layout
 * without a display. Usage: ScreenshotHarness <output dir> [page ...]
 */
public class ScreenshotHarness {

	public static void main(String[] args) throws Exception {
		File out = new File(args[0]);
		out.mkdirs();
		final MainPanel[] p = new MainPanel[1];
		SwingUtilities.invokeAndWait(() -> {
			p[0] = new MainPanel();
			p[0].refreshEnvInfo();
		});
		Thread.sleep(Long.getLong("wait", 15000));
		String[] pages = args.length > 1 ? java.util.Arrays.copyOfRange(args, 1, args.length)
				: new String[] { MainPanel.HOME, MainPanel.WIZARD, MainPanel.RUN };
		for (String page : pages) {
			SwingUtilities.invokeAndWait(() -> {
				p[0].showPage(page);
				save(p[0], new File(out, page.replace(' ', '_').toLowerCase() + ".png"));
			});
		}
		System.exit(0);
	}

	public static void save(Component c, File f) {
		c.setSize(950, Integer.getInteger("shot.height", 650));
		// Twice: headless components never run the revalidations queued by the GUI (e.g. after showing a question),
		// which in a window lay out again the texts whose height depends on their final width
		layout(c);
		// The layouts cache the sizes of the first pass, computed before the texts knew their widths
		invalidateAll(c);
		layout(c);
		BufferedImage img = new BufferedImage(950, Integer.getInteger("shot.height", 650), BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		c.printAll(g);
		g.dispose();
		try {
			ImageIO.write(img, "png", f);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private static void invalidateAll(Component c) {
		c.invalidate();
		if (c instanceof Container) for (Component ch : ((Container) c).getComponents()) invalidateAll(ch);
	}

	private static void layout(Component c) {
		if (c instanceof Container) {
			Container ct = (Container) c;
			ct.doLayout();
			for (Component ch : ct.getComponents()) layout(ch);
		}
	}
}
