package io.github.biapyx.fiji.gui;

import java.awt.Color;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.swing.ImageIcon;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import ij.ImagePlus;
import ij.ImageStack;
import ij.io.Opener;

/**
 * How to show the training prediction samples, as BiaPy describes it in "train_pred_samples/samples.json"
 * (the same description BiaPy's notebook display uses): the panels of the input, ground truth and prediction
 * images (which channels, shown as gray, mean, labels or RGB) and the rows that pair them.
 */
final class TrainingPreview {

	static final class Panel {
		String title = "";
		int[] channels = { 0 };
		String mode = "gray";
		double[] range;
	}

	static final class Row {
		String label = "";
		int input = -1, gt = -1, pred = -1;
	}

	static final class Sample {
		String file;
		String image;
		Integer gtClass;
	}

	final List<Panel> input = new ArrayList<>(), gt = new ArrayList<>(), pred = new ArrayList<>();
	final List<Row> rows = new ArrayList<>();
	final List<Sample> samples = new ArrayList<>();
	final List<String> classNames = new ArrayList<>();
	/**
	 * Title and explanation (or null) of the columns ("input", "gt") that show something else than their default,
	 * e.g. the masked input of Noise2Void in the ground truth's column.
	 */
	final Map<String, String[]> columns = new HashMap<>();
	boolean classification;
	Color[] labelColors = DEFAULT_COLORS;
	/**
	 * Contrast of the gray panels stretched to the values each image has (as Fiji's "Auto"), instead of its whole
	 * range: probabilities that are still low (e.g. contours at 0.3) show as clearly as the ones near 1.
	 */
	volatile boolean autoContrast = true;
	/** Share of the values saturated at each end by the auto contrast (Fiji's "Auto" saturates 0.35%). */
	private static final double SATURATED = 0.0035;
	/** Values used to compute the auto contrast, at most (a regular subsample of larger images). */
	private static final int MAX_VALUES = 1 << 18;

	private static final Color[] DEFAULT_COLORS = colors("#000000", "#1f77b4", "#ff7f0e", "#2ca02c", "#d62728",
			"#9467bd", "#8c564b", "#e377c2", "#7f7f7f", "#bcbd22", "#17becf", "#aec7e8", "#ffbb78", "#98df8a",
			"#ff9896", "#c5b0d5", "#c49c94", "#f7b6d2", "#dbdb8d", "#9edae5");

	/** Parses samples.json; without "rows" (BiaPy before the panels) it falls back to {@link #legacy}. */
	static TrainingPreview parse(JsonObject o) {
		List<String> files = new ArrayList<>();
		TrainingPreview p = new TrainingPreview();
		for (JsonElement e : o.getAsJsonArray("samples")) {
			JsonObject s = e.getAsJsonObject();
			Sample sample = new Sample();
			sample.file = s.get("file").getAsString();
			sample.image = str(s, "image");
			if (s.has("gt_class") && !s.get("gt_class").isJsonNull()) sample.gtClass = s.get("gt_class").getAsInt();
			p.samples.add(sample);
			files.add(sample.file);
		}
		if (!o.has("rows")) {
			TrainingPreview l = legacy(files);
			for (int i = 0; i < l.samples.size(); i++) l.samples.get(i).image = p.samples.get(i).image;
			return l;
		}
		JsonObject panels = o.getAsJsonObject("panels");
		readPanels(panels.getAsJsonArray("input"), p.input);
		readPanels(panels.getAsJsonArray("gt"), p.gt);
		readPanels(panels.getAsJsonArray("pred"), p.pred);
		for (JsonElement e : o.getAsJsonArray("rows")) {
			JsonObject r = e.getAsJsonObject();
			Row row = new Row();
			row.label = str(r, "label") == null ? "" : str(r, "label");
			row.input = index(r, "input");
			row.gt = index(r, "gt");
			row.pred = index(r, "pred");
			p.rows.add(row);
		}
		if (o.has("label_colors")) {
			JsonArray a = o.getAsJsonArray("label_colors");
			String[] hex = new String[a.size()];
			for (int i = 0; i < hex.length; i++) hex[i] = a.get(i).getAsString();
			if (hex.length > 1) p.labelColors = colors(hex);
		}
		if (o.has("class_names")) for (JsonElement e : o.getAsJsonArray("class_names")) p.classNames.add(e.getAsString());
		if (o.has("columns") && o.get("columns").isJsonObject())
			for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("columns").entrySet()) {
				JsonObject c = e.getValue().getAsJsonObject();
				if (str(c, "title") != null) p.columns.put(e.getKey(), new String[] { str(c, "title"), str(c, "info") });
			}
		p.classification = "CLASSIFICATION".equals(str(o, "workflow"));
		return p;
	}

	/** Older BiaPy: the first channel of the input, ground truth and prediction, with their own contrast. */
	static TrainingPreview legacy(List<String> files) {
		TrainingPreview p = new TrainingPreview();
		p.input.add(new Panel());
		p.gt.add(new Panel());
		p.pred.add(new Panel());
		Row r = new Row();
		r.input = r.gt = r.pred = 0;
		p.rows.add(r);
		for (String f : files) {
			Sample s = new Sample();
			s.file = f;
			p.samples.add(s);
		}
		return p;
	}

	/** Title of a column ("input", "gt"): BiaPy's if it gives one, otherwise the default. */
	String columnTitle(String key, String def) {
		String[] c = columns.get(key);
		return c != null ? c[0] : def;
	}

	/** Explanation of what a column shows, or null. */
	String columnInfo(String key) {
		String[] c = columns.get(key);
		return c != null ? c[1] : null;
	}

	String className(int c) {
		return c >= 0 && c < classNames.size() ? classNames.get(c) : String.valueOf(c);
	}

	/**
	 * Renders the panels of a .tif written by BiaPy (middle slice if 3D) as icons fitting a tile of the given
	 * size, or null if it cannot be read yet (e.g. being written).
	 */
	List<ImageIcon> render(File f, List<Panel> panels, int tile) {
		if (panels.isEmpty()) return Collections.emptyList();
		if (!f.isFile()) return null;
		try {
			ImagePlus imp = open(f);
			if (imp == null) return null;
			List<ImageIcon> icons = new ArrayList<>();
			for (Panel p : panels) {
				String[] shown = { null };
				ImageIcon icon = scale(render(imp, p, shown), tile, "labels".equals(p.mode));
				icon.setDescription(shown[0]);
				icons.add(icon);
			}
			return icons;
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * The image of a file. TIFFs are opened as virtual stacks, which read from disk only the slices drawn: a test image
	 * and its prediction can take several GB (e.g. a 3D volume), and Fiji would load them whole for a single slice.
	 */
	private static ImagePlus open(File f) {
		String name = f.getName().toLowerCase();
		if (name.endsWith(".tif") || name.endsWith(".tiff")) {
			try {
				ImagePlus imp = ij.IJ.openVirtual(f.getAbsolutePath());
				if (imp != null && imp.getStackSize() > 0) return imp;
			} catch (Exception e) {
				// e.g. being written: tried again below
			}
		}
		return new Opener().openImage(f.getAbsolutePath());
	}

	/** @param shown set to a description of the values shown from black to white (gray panels) */
	private BufferedImage render(ImagePlus imp, Panel p, String[] shown) {
		int w = imp.getWidth(), h = imp.getHeight(), z = Math.max(1, (imp.getNSlices() + 1) / 2);
		ImageStack stack = imp.getStack();
		List<float[]> chs = new ArrayList<>();
		for (int c : p.channels) {
			if (c >= imp.getNChannels()) continue;
			chs.add((float[]) stack.getProcessor(imp.getStackIndex(c + 1, z, 1)).convertToFloat().getPixels());
		}
		if (chs.isEmpty()) chs.add(new float[w * h]);

		if ("labels".equals(p.mode)) {
			BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
			for (int i = 0; i < w * h; i++) {
				int label = 0;
				if (chs.size() == 1) label = Math.round(chs.get(0)[i]);
				else for (int c = 1; c < chs.size(); c++) if (chs.get(c)[i] > chs.get(label)[i]) label = c;
				int k = label <= 0 ? 0 : (label - 1) % (labelColors.length - 1) + 1;
				out.setRGB(i % w, i / w, labelColors[k].getRGB());
			}
			return out;
		}
		if ("rgb".equals(p.mode)) {
			BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
			int[][] bytes = new int[3][];
			for (int c = 0; c < 3; c++) bytes[c] = toBytes(chs.get(Math.min(c, chs.size() - 1)), displayRange(chs.get(Math.min(c, chs.size() - 1)), p.range));
			for (int i = 0; i < w * h; i++) out.setRGB(i % w, i / w, (bytes[0][i] << 16) | (bytes[1][i] << 8) | bytes[2][i]);
			return out;
		}
		float[] v = chs.get(0);
		if ("mean".equals(p.mode) && chs.size() > 1) {
			v = new float[w * h];
			for (float[] c : chs) for (int i = 0; i < v.length; i++) v[i] += c[i] / chs.size();
		}
		double[] r = displayRange(v, p.range);
		shown[0] = String.format("Shown from %s (black) to %s (white)%s", number(r[0]), number(r[1]),
				p.range == null ? "" : String.format("; values go from %s to %s", number(p.range[0]), number(p.range[1])));
		int[] b = toBytes(v, r);
		BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
		for (int i = 0; i < w * h; i++) out.getRaster().setSample(i % w, i / w, 0, b[i]);
		return out;
	}

	/**
	 * Values shown as black and white: the panel's range (e.g. 0-1 for probabilities) or else the image's own, and
	 * with auto contrast the image's values within it, with a few saturated. A nearly flat image (e.g. a prediction
	 * before training) keeps the panel's range: stretching it would show its noise as structure.
	 */
	private double[] displayRange(float[] v, double[] range) {
		double[] own = percentiles(v, 0, 1);
		double[] full = range != null ? range : own;
		if (!autoContrast) return full;
		double[] r = percentiles(v, SATURATED, 1 - SATURATED);
		double lo = Math.max(full[0], r[0]), hi = Math.min(full[1], r[1]);
		if (hi <= lo) return own[1] > own[0] ? own : full; // e.g. a mask with a few foreground pixels
		if (range != null && hi - lo < 0.25 * (range[1] - range[0])) return full;
		return new double[] { lo, hi };
	}

	/** The given quantiles of the values (of a regular subsample of them in large images). */
	private static double[] percentiles(float[] v, double low, double high) {
		int step = Math.max(1, v.length / MAX_VALUES);
		float[] s = new float[(v.length + step - 1) / step];
		int n = 0;
		for (int i = 0; i < v.length; i += step) if (!Float.isNaN(v[i])) s[n++] = v[i];
		if (n == 0) return new double[] { 0, 0 };
		Arrays.sort(s, 0, n);
		return new double[] { s[(int) Math.floor(low * (n - 1))], s[(int) Math.ceil(high * (n - 1))] };
	}

	private static String number(double x) {
		return Math.abs(x) >= 100 || x == Math.rint(x) ? String.format("%.0f", x) : String.format("%.2f", x);
	}

	/** 0-255 values, black at range[0] and white at range[1]. */
	private static int[] toBytes(float[] v, double[] range) {
		double min = range[0], max = range[1];
		double scale = max > min ? 255 / (max - min) : 0;
		int[] out = new int[v.length];
		for (int i = 0; i < v.length; i++) out[i] = (int) Math.max(0, Math.min(255, Math.round((v[i] - min) * scale)));
		return out;
	}

	private static ImageIcon scale(BufferedImage bi, int tile, boolean nearest) {
		double s = Math.min((double) tile / bi.getWidth(), (double) tile / bi.getHeight());
		Image scaled = bi.getScaledInstance(Math.max(1, (int) (bi.getWidth() * s)), Math.max(1, (int) (bi.getHeight() * s)),
				nearest ? Image.SCALE_REPLICATE : Image.SCALE_SMOOTH);
		return new ImageIcon(scaled);
	}

	private static void readPanels(JsonArray a, List<Panel> out) {
		if (a == null) return;
		for (JsonElement e : a) {
			JsonObject o = e.getAsJsonObject();
			Panel p = new Panel();
			p.title = str(o, "title") == null ? "" : str(o, "title");
			p.mode = str(o, "mode") == null ? "gray" : str(o, "mode");
			JsonArray chs = o.getAsJsonArray("channels");
			p.channels = new int[chs.size()];
			for (int i = 0; i < p.channels.length; i++) p.channels[i] = chs.get(i).getAsInt();
			if (o.has("range") && o.get("range").isJsonArray()) {
				JsonArray r = o.getAsJsonArray("range");
				p.range = new double[] { r.get(0).getAsDouble(), r.get(1).getAsDouble() };
			}
			out.add(p);
		}
	}

	private static String str(JsonObject o, String key) {
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
	}

	private static int index(JsonObject o, String key) {
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsInt() : -1;
	}

	private static Color[] colors(String... hex) {
		Color[] c = new Color[hex.length];
		for (int i = 0; i < hex.length; i++) c[i] = Color.decode(hex[i]);
		return c;
	}
}
