package io.github.biapyx.fiji.gui;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Base64;

import javax.imageio.ImageIO;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

/**
 * The training targets of a detection point radius drawn on a crop of the training images ("points_crop_png" and
 * "points_crop" of the data preview), as BiaPy paints them: every voxel within the ellipsoid of radii (z, y, x)
 * around each point.
 */
final class PointsPreview {

	private PointsPreview() {}

	static final Color TARGET = new Color(255, 40, 40, 120);

	static BufferedImage decode(String base64Png) {
		try {
			return ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(base64Png)));
		} catch (Exception e) {
			return null;
		}
	}

	/** Whether the offset (dz, dy, dx) from a point is in its target of radii r = (z, y, x); a radius 0 is flat. */
	static boolean inTarget(int dz, int dy, int dx, int[] r) {
		int[] d = { dz, dy, dx };
		double sum = 0;
		for (int i = 0; i < 3; i++) {
			if (r[i] == 0) {
				if (d[i] != 0) return false;
			} else {
				sum += (double) d[i] * d[i] / ((double) r[i] * r[i]);
			}
		}
		return sum <= 1;
	}

	/** Scale that makes the longest side of {@code img} {@code size} pixels. */
	private static double scale(BufferedImage img, int size) {
		return size / (double) Math.max(img.getWidth(), img.getHeight());
	}

	/**
	 * The crop enlarged to {@code size} pixels (longest side) with the targets of {@code points} ([dz, y, x] each, dz
	 * the distance to the crop's slice) for radii (z, y, x) drawn over it.
	 */
	static BufferedImage draw(BufferedImage crop, JsonArray points, int[] radius, int size) {
		int w = crop.getWidth(), h = crop.getHeight();
		boolean[][] mask = new boolean[h][w];
		for (JsonElement e : points) {
			JsonArray p = e.getAsJsonArray();
			int dz = p.get(0).getAsInt(), y = p.get(1).getAsInt(), x = p.get(2).getAsInt();
			for (int yy = Math.max(0, y - radius[1]); yy <= Math.min(h - 1, y + radius[1]); yy++)
				for (int xx = Math.max(0, x - radius[2]); xx <= Math.min(w - 1, x + radius[2]); xx++)
					if (inTarget(dz, yy - y, xx - x, radius)) mask[yy][xx] = true;
		}
		BufferedImage out = enlarge(crop, size);
		double f = scale(crop, size);
		Graphics2D g = out.createGraphics();
		g.setColor(TARGET);
		for (int y = 0; y < h; y++)
			for (int x = 0; x < w; x++)
				if (mask[y][x]) {
					int x0 = (int) Math.round(x * f), y0 = (int) Math.round(y * f);
					g.fillRect(x0, y0, (int) Math.round((x + 1) * f) - x0, (int) Math.round((y + 1) * f) - y0);
				}
		g.dispose();
		return out;
	}

	/** Enlarged to {@code size} pixels (longest side) without smoothing, so that each pixel stays visible. */
	static BufferedImage enlarge(BufferedImage img, int size) {
		double f = scale(img, size);
		BufferedImage out = new BufferedImage(Math.max(1, (int) Math.round(img.getWidth() * f)),
				Math.max(1, (int) Math.round(img.getHeight() * f)), BufferedImage.TYPE_INT_RGB);
		Graphics2D g = out.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
		g.drawImage(img, 0, 0, out.getWidth(), out.getHeight(), null);
		g.dispose();
		return out;
	}
}
