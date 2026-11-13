package io.github.biapyx.fiji.gui;

import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.imageio.ImageIO;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;

/**
 * Emoji as images. Java fonts lack many emoji (e.g. 🦨), so they are downloaded from Twemoji
 * (https://github.com/jdecked/twemoji, graphics licensed under CC-BY 4.0) and cached in memory.
 */
public final class EmojiIcons {

	private static final String[] SOURCES = { "https://cdn.jsdelivr.net/gh/jdecked/twemoji@15.1.0/assets/72x72/",
			"https://cdn.jsdelivr.net/gh/twitter/twemoji@14.0.2/assets/72x72/" };
	private static final Map<String, ImageIcon> CACHE = new ConcurrentHashMap<>();
	private static final ExecutorService LOADER = Executors.newFixedThreadPool(4, r -> {
		Thread t = new Thread(r, "BiaPy-emoji-loader");
		t.setDaemon(true);
		return t;
	});

	private static final java.io.File DIR = new java.io.File(io.github.biapyx.fiji.env.PythonEnv.getManagedRoot(), "cache/emoji");

	private EmojiIcons() {}

	/** Shows the emoji as an image of the given size in the label, when it arrives. */
	public static void load(String emoji, int size, JLabel target) {
		if (emoji == null || emoji.trim().isEmpty()) return;
		String key = fileName(emoji) + "@" + size;
		ImageIcon cached = CACHE.get(key);
		if (cached != null) {
			target.setIcon(cached);
			return;
		}
		LOADER.submit(() -> {
			// Kept on disk, so the next sessions don't download them again
			java.io.File file = new java.io.File(DIR, fileName(emoji) + ".png");
			for (String base : SOURCES) {
				try {
					BufferedImage img = file.isFile() ? ImageIO.read(file) : null;
					if (img == null) {
						URLConnection conn = new URL(base + fileName(emoji) + ".png").openConnection();
						conn.setConnectTimeout(10000);
						conn.setReadTimeout(20000);
						try (InputStream in = conn.getInputStream()) {
							img = ImageIO.read(in);
						}
						if (img == null) continue;
						if (DIR.isDirectory() || DIR.mkdirs()) ImageIO.write(img, "png", file);
					}
					ImageIcon icon = new ImageIcon(img.getScaledInstance(size, size, Image.SCALE_SMOOTH));
					CACHE.put(key, icon);
					SwingUtilities.invokeLater(() -> target.setIcon(icon));
					return;
				} catch (Exception e) {
					// Try the next source
				}
			}
		});
	}

	/**
	 * Twemoji file name: code points in hex joined by "-". The variation selector U+FE0F is left out
	 * unless the emoji is a sequence joined with U+200D, as Twemoji names its files.
	 */
	static String fileName(String emoji) {
		List<Integer> cps = new ArrayList<>();
		emoji.trim().codePoints().forEach(cps::add);
		if (!cps.contains(0x200D)) cps.removeIf(cp -> cp == 0xFE0F);
		StringBuilder sb = new StringBuilder();
		for (int cp : cps) {
			if (sb.length() > 0) sb.append('-');
			sb.append(Integer.toHexString(cp));
		}
		return sb.toString();
	}
}
