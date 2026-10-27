package io.github.biapyx.fiji.gui;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the loss and the metrics BiaPy reports during training measure, shown with the (i) of their charts. The
 * names are BiaPy's, e.g. "IoU", "L1 (Db channel)" or "F1 (0-points)".
 */
final class MetricInfo {

	private MetricInfo() {}

	private static final Map<String, String> METRICS = new LinkedHashMap<>();
	private static final Map<String, String> CHANNELS = new LinkedHashMap<>();

	static {
		METRICS.put("IoU", "Intersection over Union (Jaccard index) between the predicted and the true masks: the overlapping "
				+ "pixels divided by the pixels of both. From 0 (no overlap) to 1 (identical). <b>Higher is better.</b>");
		METRICS.put("Foreground IoU", METRICS.get("IoU"));
		METRICS.put("Dice", "Dice coefficient (F1 score of the pixels) between the predicted and the true masks: twice the "
				+ "overlap divided by the size of both. From 0 to 1. <b>Higher is better.</b>");
		METRICS.put("Accuracy", "Fraction of correctly classified pixels (or images, in classification). From 0 to 1. "
				+ "<b>Higher is better.</b> With a scarce foreground it is high even for poor predictions.");
		METRICS.put("Top 5 accuracy", "Fraction of images whose true class is among the five classes the model finds most "
				+ "likely. <b>Higher is better.</b>");
		METRICS.put("L1", "Mean absolute difference between the predicted and the true values (L1 distance). "
				+ "<b>Lower is better</b>; 0 is a perfect prediction.");
		METRICS.put("MAE", METRICS.get("L1"));
		METRICS.put("MSE", "Mean squared difference between the predicted and the true values: large errors weigh more than "
				+ "with L1/MAE. <b>Lower is better</b>; 0 is a perfect prediction.");
		METRICS.put("PSNR", "Peak signal-to-noise ratio, in decibels: how large the error is compared to the range of the "
				+ "image. <b>Higher is better</b>; values above 30 dB are usually good.");
		METRICS.put("SSIM", "Structural similarity: compares local brightness, contrast and structure of the predicted and "
				+ "the true images. Up to 1 (identical). <b>Higher is better.</b>");
		METRICS.put("PCC", "Pearson correlation coefficient between the predicted and the true values. From -1 to 1. "
				+ "<b>Higher is better.</b>");
		METRICS.put("LPIPS", "Learned perceptual similarity: distance between deep features of the predicted and the true "
				+ "images, close to how different they look to a person. <b>Lower is better.</b>");
		METRICS.put("FID", "Fréchet Inception Distance: how different the predicted images are, as a whole, from the true "
				+ "ones. <b>Lower is better.</b>");
		METRICS.put("IS", "Inception Score: how clear and varied the generated images are. <b>Higher is better.</b>");
		METRICS.put("Precision", "Fraction of the detected points that match a true point. <b>Higher is better.</b>");
		METRICS.put("Recall", "Fraction of the true points that are detected. <b>Higher is better.</b>");
		METRICS.put("F1", "Balance of precision and recall of the detected points (their harmonic mean). From 0 to 1. "
				+ "<b>Higher is better.</b>");

		CHANNELS.put("F", "the foreground (pixels of any instance)");
		CHANNELS.put("B", "the instances without their contour");
		CHANNELS.put("C", "the contours of the instances");
		CHANNELS.put("M", "the foreground mask");
		CHANNELS.put("P", "the central point or skeleton of each instance");
		CHANNELS.put("Db", "the distance of each pixel to the border of its instance");
		CHANNELS.put("Dc", "the distance of each pixel to the center of its instance");
		CHANNELS.put("Dn", "the distance to the neighbouring instances");
		CHANNELS.put("D", "the distance map of the instances");
		CHANNELS.put("R", "the distances from the center of each instance to its border along fixed rays (StarDist)");
		CHANNELS.put("Gv", "the vertical component of the flows towards the center of each instance (Cellpose)");
		CHANNELS.put("Gh", "the horizontal component of the flows towards the center of each instance (Cellpose)");
		CHANNELS.put("Gz", "the depth component of the flows towards the center of each instance (Cellpose)");
		CHANNELS.put("H", "the horizontal distance of each pixel to the center of its instance (HoVer-Net)");
		CHANNELS.put("V", "the vertical distance of each pixel to the center of its instance (HoVer-Net)");
		CHANNELS.put("A", "the affinities: whether neighbouring pixels belong to the same instance");
		CHANNELS.put("T", "the touching areas between instances");
		CHANNELS.put("class", "the class of each pixel");
	}

	private static final Pattern NAME = Pattern.compile("^(.*?)\\s*\\((.+)\\)\\s*$");

	/** HTML explanation of a metric, e.g. "IoU (C channel)", or null if unknown. */
	static String describe(String metric) {
		String base = metric, detail = null;
		Matcher m = NAME.matcher(metric);
		if (m.matches()) {
			base = m.group(1);
			detail = m.group(2);
		}
		String text = METRICS.get(base);
		if (text == null) return null;
		StringBuilder sb = new StringBuilder("<b>").append(escape(metric)).append("</b><br><br>").append(text);
		if (detail != null) {
			Matcher ch = Pattern.compile("^(\\w+) channels?$").matcher(detail);
			if (ch.matches() && CHANNELS.containsKey(ch.group(1)))
				sb.append("<br><br>Measured on the <b>").append(ch.group(1)).append("</b> channel the network predicts: ")
						.append(CHANNELS.get(ch.group(1))).append(".");
			else if (detail.endsWith("-points"))
				sb.append("<br><br>Points of class ").append(escape(detail.replace("-points", ""))).append(".");
		}
		sb.append("<br><br>Solid line: training patches. Dashed line: validation patches (not used to train, so they "
				+ "show whether the model generalizes).");
		return sb.toString();
	}

	/** HTML explanation of the loss, from the name BiaPy prints (e.g. "Dice + cross-entropy" or "BCE (F) + BCE (C)"). */
	static String describeLoss(String lossName) {
		StringBuilder sb = new StringBuilder("<b>Loss").append(lossName == null ? "" : ": " + escape(lossName)).append("</b><br><br>")
				.append("The value the training minimizes: how far the predictions are from the targets. "
						+ "<b>Lower is better.</b> It should go down; if the validation (dashed) line goes up while the "
						+ "training one keeps going down, the model is overfitting.");
		if (lossName != null) {
			sb.append("<br><br>Terms:<ul>");
			for (String term : lossName.split("\\s*\\+\\s*")) {
				Matcher m = NAME.matcher(term);
				String fn = m.matches() ? m.group(1) : term, ch = m.matches() ? m.group(2) : null;
				sb.append("<li><b>").append(escape(term)).append("</b>: ").append(lossTerm(fn));
				if (ch != null && CHANNELS.containsKey(ch)) sb.append(", on ").append(CHANNELS.get(ch));
				sb.append("</li>");
			}
			sb.append("</ul>");
		}
		return sb.toString();
	}

	private static String lossTerm(String fn) {
		switch (fn.toUpperCase().replaceAll("^[\\d.]+ X ", "")) {
		case "DICE":
			return "Dice loss (1 - Dice coefficient), good with small or unbalanced structures";
		case "CROSS-ENTROPY":
		case "CE":
			return "cross-entropy of the predicted class probabilities";
		case "BCE":
			return "binary cross-entropy of a 0-1 map";
		case "L1":
		case "MAE":
		case "MAE (L1)":
			return "mean absolute error";
		case "MSE":
		case "MSE (L2)":
			return "mean squared error";
		case "CONTRASTIVE":
			return "contrastive term that pulls together the features of the same class";
		default:
			return fn;
		}
	}

	private static String escape(String s) {
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
