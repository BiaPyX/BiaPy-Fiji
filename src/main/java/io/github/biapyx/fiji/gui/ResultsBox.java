package io.github.biapyx.fiji.gui;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The metrics of a finished job, read from the files BiaPy writes: the training log ("<job>_training_log.json", one
 * JSON line per epoch) and the test metrics ("test_results_metrics.csv", one row per test image).
 */
final class ResultsBox {

	private ResultsBox() {}

	/** Training and validation values of one quantity at the epoch summarized. */
	static final class TrainRow {
		final String name;
		final Double train, val;

		TrainRow(String name, Double train, Double val) {
			this.name = name;
			this.train = train;
			this.val = val;
		}
	}

	/** The epoch summarized (1-based), whether it was chosen by the validation loss, and its values. */
	static final class Training {
		final int epoch, epochs;
		final boolean bestOnVal;
		final List<TrainRow> rows = new ArrayList<>();

		Training(int epoch, int epochs, boolean bestOnVal) {
			this.epoch = epoch;
			this.epochs = epochs;
			this.bestOnVal = bestOnVal;
		}
	}

	/**
	 * The epoch whose model BiaPy keeps and tests with (the lowest validation loss) or, without validation, the last
	 * one; null if the log can't be read. The learning rate is left out.
	 */
	static Training training(File log) {
		List<JsonObject> epochs = new ArrayList<>();
		try {
			for (String line : Files.readAllLines(log.toPath(), StandardCharsets.UTF_8))
				if (!line.trim().isEmpty()) epochs.add(JsonParser.parseString(line).getAsJsonObject());
		} catch (Exception e) {
			return null;
		}
		if (epochs.isEmpty()) return null;
		int best = epochs.size() - 1;
		boolean onVal = false;
		for (int i = 0; i < epochs.size(); i++) {
			Double v = number(epochs.get(i).get("test_loss"));
			if (v != null && (!onVal || v < number(epochs.get(best).get("test_loss")))) {
				best = i;
				onVal = true;
			}
		}
		JsonObject e = epochs.get(best);
		Training t = new Training(best + 1, epochs.size(), onVal);
		for (String key : e.keySet()) {
			if (!key.startsWith("train_") || key.equals("train_lr")) continue;
			String name = key.substring("train_".length());
			t.rows.add(new TrainRow(name.equals("loss") ? "Loss" : name, number(e.get(key)), number(e.get("test_" + name))));
		}
		return t;
	}

	private static Double number(JsonElement e) {
		try {
			return e == null || !e.isJsonPrimitive() ? null : e.getAsDouble();
		} catch (NumberFormatException ex) {
			return null;
		}
	}

	/** Instance matching columns, e.g. "0.5 TH (post)f1": IoU threshold, post-processed or not, and metric. */
	private static final Pattern MATCHING = Pattern.compile("^([0-9.]+) TH (\\(post\\))?(.+)$");
	/** Counts, summed rather than averaged by BiaPy: not shown. */
	private static final java.util.Set<String> COUNTS = new java.util.HashSet<>(
			java.util.Arrays.asList("tp", "fp", "fn", "n_true", "n_pred"));
	private static final String[] MATCHING_SHOWN = { "precision", "recall", "f1", "panoptic_quality" };

	/**
	 * The main test metrics, averaged over the test images as BiaPy does: for instance segmentation the matching of
	 * the instances at IoU 0.5 (post-processed, when there is post-processing: the final result); otherwise every
	 * metric but the counts. Empty if the file can't be read.
	 */
	static Map<String, Double> test(File csv) {
		Map<String, Double> out = new LinkedHashMap<>();
		List<String[]> rows = new ArrayList<>();
		try {
			for (String line : Files.readAllLines(csv.toPath(), StandardCharsets.UTF_8))
				if (!line.trim().isEmpty()) rows.add(splitCsv(line));
		} catch (Exception e) {
			return out;
		}
		if (rows.size() < 2) return out;
		String[] header = rows.get(0);
		Map<String, Double> means = new LinkedHashMap<>();
		for (int c = 0; c < header.length; c++) {
			double sum = 0;
			int n = 0;
			for (String[] r : rows.subList(1, rows.size())) {
				try {
					double v = Double.parseDouble(c < r.length ? r[c] : "");
					if (!Double.isNaN(v)) {
						sum += v;
						n++;
					}
				} catch (NumberFormatException e) {
					// e.g. the file name, or empty
				}
			}
			if (n > 0) means.put(header[c], sum / n);
		}
		String threshold = null;
		boolean post = false;
		for (String k : means.keySet()) {
			Matcher m = MATCHING.matcher(k);
			if (!m.matches()) continue;
			if (threshold == null || m.group(1).equals("0.5")) threshold = m.group(1);
			post |= m.group(2) != null;
		}
		if (threshold != null) {
			for (String metric : MATCHING_SHOWN) {
				Double v = means.get(threshold + " TH " + (post ? "(post)" : "") + metric);
				if (v != null) out.put(label(metric) + " (IoU \u2265 " + threshold + ")", v);
			}
			for (Map.Entry<String, Double> e : means.entrySet())
				if (e.getKey().contains("class")) out.put(label(e.getKey()), e.getValue());
			return out;
		}
		for (Map.Entry<String, Double> e : means.entrySet()) {
			String base = e.getKey().split(" ")[0];
			if (!COUNTS.contains(base)) out.put(label(e.getKey()), e.getValue());
		}
		return out;
	}

	private static final Map<String, String> NAMES = new LinkedHashMap<>();
	static {
		NAMES.put("iou", "IoU");
		NAMES.put("dice", "Dice");
		NAMES.put("f1", "F1");
		NAMES.put("precision", "Precision");
		NAMES.put("recall", "Recall");
		NAMES.put("accuracy", "Accuracy");
		NAMES.put("mae", "MAE");
		NAMES.put("mse", "MSE");
		NAMES.put("ssim", "SSIM");
		NAMES.put("psnr", "PSNR");
		NAMES.put("panoptic_quality", "Panoptic quality");
		NAMES.put("class", "Class");
	}

	/** "class iou" -> "Class IoU": BiaPy writes the names in lower case. */
	static String label(String name) {
		String[] words = name.split(" ", -1);
		for (int i = 0; i < words.length; i++) words[i] = NAMES.getOrDefault(words[i], words[i]);
		return String.join(" ", words);
	}

	/** One CSV line as pandas writes it (fields with commas are quoted). */
	static String[] splitCsv(String line) {
		List<String> out = new ArrayList<>();
		StringBuilder cur = new StringBuilder();
		boolean quoted = false;
		for (int i = 0; i < line.length(); i++) {
			char ch = line.charAt(i);
			if (quoted) {
				if (ch != '"') cur.append(ch);
				else if (i + 1 < line.length() && line.charAt(i + 1) == '"') cur.append(line.charAt(++i));
				else quoted = false;
			} else if (ch == '"') {
				quoted = true;
			} else if (ch == ',') {
				out.add(cur.toString());
				cur.setLength(0);
			} else {
				cur.append(ch);
			}
		}
		out.add(cur.toString());
		return out.toArray(new String[0]);
	}

	private static String fmt(Double v) {
		if (v == null) return "\u2013";
		return Math.abs(v) >= 1000 ? String.format("%.0f", v) : String.format("%.3f", v);
	}

	/** The training values as HTML (a label's, without the html tags), or null if there are none. */
	static String trainingHtml(Training t) {
		if (t == null || t.rows.isEmpty()) return null;
		StringBuilder b = new StringBuilder("<font color='#888888'>")
				.append(t.bestOnVal ? "Epoch " + t.epoch + " of " + t.epochs + ": lowest validation loss, the model kept"
						: "Last epoch (" + t.epoch + ")")
				.append("</font><table cellspacing='0' cellpadding='4'><tr><td></td><td align='right'><b>Training</b></td>")
				.append(t.bestOnVal ? "<td align='right'><b>Validation</b></td>" : "").append("</tr>");
		for (TrainRow r : t.rows) {
			b.append("<tr><td>").append(r.name).append("&nbsp;&nbsp;</td><td align='right'>").append(fmt(r.train)).append("</td>");
			if (t.bestOnVal) b.append("<td align='right'>").append(fmt(r.val)).append("</td>");
			b.append("</tr>");
		}
		return b.append("</table>").toString();
	}

	/** The test metrics as HTML (a label's, without the html tags), or null if there are none. */
	static String testHtml(Map<String, Double> test, int images) {
		if (test.isEmpty()) return null;
		StringBuilder b = new StringBuilder("<font color='#888888'>Compared with the ground truth: mean of the ")
				.append(images > 0 ? images + " " : "").append("test images</font><table cellspacing='0' cellpadding='4'>");
		for (Map.Entry<String, Double> e : test.entrySet())
			b.append("<tr><td>").append(e.getKey()).append("&nbsp;&nbsp;</td><td align='right'><b>").append(fmt(e.getValue()))
					.append("</b></td></tr>");
		return b.append("</table>").toString();
	}
}
