package io.github.biapyx.fiji.gui;

import java.awt.Component;
import java.io.File;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.JEditorPane;
import javax.swing.SwingUtilities;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.github.biapyx.fiji.BiaPyFiji;
import io.github.biapyx.fiji.env.PythonBridge;

/**
 * "Workflow info" of a job: what the workflow does in plain words, what the job creates in its folder, how to look at
 * its results (for instance segmentation, what each channel of the raw prediction is and how the objects are built
 * from them, with the settings to change) and where to get help. The job's settings are read from BiaPy (bridge
 * command "workflow_info"), as BiaPy resolves them; without it (older BiaPy versions) the general text is shown.
 */
final class WorkflowInfoDialog {

	private WorkflowInfoDialog() {}

	/** What each workflow does, for users without deep learning background. */
	private static final Map<String, String> DESCRIPTIONS = new LinkedHashMap<>();
	static {
		DESCRIPTIONS.put("SEMANTIC_SEG", "Semantic segmentation labels every pixel of your images with a class, e.g. "
				+ "\"mitochondria\" or \"background\". The model learned it from your annotated examples and draws the same "
				+ "kind of mask on new images. Touching objects of the same class end up as one region: to tell them "
				+ "apart, use instance segmentation.");
		DESCRIPTIONS.put("INSTANCE_SEG", "Instance segmentation finds every single object of your images (e.g. each "
				+ "nucleus or cell) and gives each one its own number, even when they touch. The model does not draw the "
				+ "objects directly: it predicts a few helper images (for example where the objects are and where their "
				+ "borders are), and a last step, the <b>post-processing</b>, turns them into separate objects.");
		DESCRIPTIONS.put("DETECTION", "Detection finds where the objects are, marking each one with a point at its "
				+ "center (e.g. to count nuclei), without drawing its shape. The model predicts how likely each pixel is "
				+ "to be the center of an object, and the peaks of that image become the points.");
		DESCRIPTIONS.put("DENOISING", "Denoising removes the noise of your images while keeping their structures. With "
				+ "Noise2Void the model learns from the noisy images themselves, without clean examples.");
		DESCRIPTIONS.put("SUPER_RESOLUTION", "Super-resolution makes your images larger and sharper: the model predicts "
				+ "the detail a higher resolution image would have (e.g. twice or four times as many pixels per axis).");
		DESCRIPTIONS.put("SELF_SUPERVISED", "Self-supervised learning teaches a model what your images look like without "
				+ "any annotation, asking it to repair images that were degraded on purpose. Its result is a model to "
				+ "start other workflows from (a pretrained model), which then usually needs fewer annotated images.");
		DESCRIPTIONS.put("CLASSIFICATION", "Classification assigns a category to each whole image (e.g. the cell type or "
				+ "the condition), as learned from your images sorted by category.");
		DESCRIPTIONS.put("IMAGE_TO_IMAGE", "Image to image turns each image into another one of the same size, e.g. "
				+ "predicting a fluorescent staining from a bright-field image, as learned from your pairs of images.");
	}

	/** What each instance segmentation channel of the raw prediction shows. */
	private static final Map<String, String> CHANNELS = new LinkedHashMap<>();
	static {
		CHANNELS.put("F", "<b>Foreground</b>: how likely each pixel is to be inside an object (bright = object).");
		CHANNELS.put("B", "<b>Background</b>: how likely each pixel is to be outside the objects (bright = background).");
		CHANNELS.put("C", "<b>Contour</b>: how likely each pixel is to be on the border of an object; it separates "
				+ "touching objects.");
		CHANNELS.put("P", "<b>Central point</b>: how likely each pixel is to be the center of an object, where each "
				+ "object starts from.");
		CHANNELS.put("P_skeleton", "<b>Skeleton</b>: how likely each pixel is to be on the central line of an object, "
				+ "where each object starts from.");
		CHANNELS.put("Db", "<b>Distance to the border</b>: bright in the middle of each object, dark at its border.");
		CHANNELS.put("Dc", "<b>Distance to the center</b>: dark at the center of each object, brighter towards its "
				+ "border.");
		CHANNELS.put("Dn", "<b>Distance to the closest neighbor</b>: how close each pixel is to another object.");
		CHANNELS.put("D", "<b>Signed distance</b>: positive inside the objects, negative outside, zero at their "
				+ "border.");
		CHANNELS.put("T", "<b>Touching area</b>: where two objects touch each other.");
		CHANNELS.put("R", "<b>Radial distances</b> (StarDist): one image per direction, each with the distance from "
				+ "the pixel to the border of its object in that direction. They are not meant to be looked at one by one.");
		CHANNELS.put("Gv", "<b>Vertical flow</b> (Cellpose): the vertical direction towards the center of the object.");
		CHANNELS.put("Gh", "<b>Horizontal flow</b> (Cellpose): the horizontal direction towards the center of the "
				+ "object.");
		CHANNELS.put("Gz", "<b>Depth flow</b> (Cellpose): the direction towards the center of the object along Z.");
		CHANNELS.put("H", "<b>Horizontal distance to the center</b> of the object (HoVer-Net): it changes sharply "
				+ "where two objects meet.");
		CHANNELS.put("V", "<b>Vertical distance to the center</b> of the object (HoVer-Net): it changes sharply where "
				+ "two objects meet.");
		CHANNELS.put("Z", "<b>Depth distance to the center</b> of the object (HoVer-Net): it changes sharply where two "
				+ "objects meet.");
		CHANNELS.put("A", "<b>Affinities</b>: one image per direction (e.g. Z, Y and X): how likely each pixel and its "
				+ "neighbor in that direction belong to the same object.");
		CHANNELS.put("E_offset", "<b>Embedding offsets</b>: for each pixel, where the center of its object is.");
		CHANNELS.put("E_sigma", "<b>Embedding sizes</b>: how large the object of each pixel is, along each axis.");
		CHANNELS.put("E_seediness", "<b>Seediness</b>: how likely each pixel is to be a good center of its object.");
		CHANNELS.put("class", "<b>Class</b>: one image per class, how likely each pixel is to be of that class (the "
				+ "class of each object comes from them).");
	}

	/** Channels that are low inside the objects: their seeds and growth mask are the pixels below the threshold. */
	private static final List<String> LOW_INSIDE = java.util.Arrays.asList("C", "B", "T", "Dn", "Dc");
	private static final List<String> HVZ = java.util.Arrays.asList("H", "V", "Z");

	/**
	 * Opens the window and fills it with the job's settings when BiaPy has read them.
	 *
	 * @param logFile the job's full log, to show the thresholds BiaPy used (null if not available yet)
	 */
	static void show(Component parent, File configFile, String workflow, File jobFolder, String jobId, File logFile,
			String docsUrl) {
		String name = WorkflowConfirmDialog.workflowName(workflow);
		JEditorPane text = UiUtils.helpWindow(parent, "Workflow info: " + name,
				html(workflow, null, jobFolder, jobId, null, docsUrl, true));
		new Thread(() -> {
			JsonObject info = null;
			try {
				JsonObject args = new JsonObject();
				args.addProperty("yaml_file", configFile.getAbsolutePath());
				info = new PythonBridge().call("workflow_info", args, null);
			} catch (Exception e) {
				// The general text is enough (e.g. a BiaPy version without this command)
			}
			String body = html(workflow, info, jobFolder, jobId, thresholdsUsed(logFile), docsUrl, false);
			SwingUtilities.invokeLater(() -> {
				text.setText(UiUtils.htmlPage(body));
				text.setCaretPosition(0);
			});
		}, "BiaPy-workflow-info").start();
	}

	/** The window's HTML body (package visible for tests). */
	static String html(String workflow, JsonObject info, File jobFolder, String jobId, String thresholds, String docsUrl,
			boolean reading) {
		StringBuilder sb = new StringBuilder();
		sb.append("<h2>What it does</h2><p>").append(DESCRIPTIONS.getOrDefault(workflow, "")).append("</p>");
		if (reading) sb.append("<p class='muted'>Reading the settings of the job . . .</p>");

		boolean train = info == null || info.get("train").getAsBoolean();
		boolean test = info == null || info.get("test").getAsBoolean();
		sb.append("<h2>What the job creates</h2><p>In the job's folder (<i>").append(esc(jobFolder.getAbsolutePath()))
				.append("</i>):</p><ul>");
		if (train)
			sb.append("<li><b>checkpoints</b>: the trained model (a <i>.pth</i> file). Load it to predict new images or "
					+ "to keep training it (in the Wizard, as a pretrained BiaPy model).</li>");
		sb.append("<li><b>logs</b>: everything BiaPy printed (the BiaPy output tab, complete), the first place to look "
				+ "when something goes wrong.</li>");
		sb.append("<li><b>results/").append(esc(jobId)).append("</b>: the results, the most important part:<ul>");
		if (test) sb.append(resultFolders(workflow, info));
		if (train)
			sb.append("<li><i>charts</i> and <i>train_pred_samples</i>: the training curves and predictions shown in "
					+ "the other tabs.</li>");
		sb.append("<li><i>CITATIONS.md</i> and <i>citations.bib</i>: the works to cite if you use the results.</li>");
		sb.append("</ul></li></ul>");
		if (info != null && info.get("by_chunks").getAsBoolean())
			sb.append("<p>As the test images are large, they are predicted by chunks and their results are saved as "
					+ "<i>.zarr</i> files (see <i>Zarr files</i> below to open them).</p>");
		if (info != null && info.has("zarr_outputs") && info.get("zarr_outputs").getAsBoolean())
			sb.append(zarrFiles(info.get("ome_zarr_version").getAsString()));

		if (test) sb.append(analysis(workflow, info, thresholds));

		sb.append("<h2>Need help?</h2><p>The <a href='").append(docsUrl).append("'>documentation of the workflow</a> "
				+ "explains all of this in detail. Do you have problems or doubts? Post them in the <a href='")
				.append(BiaPyFiji.URL_FORUM).append("'>image.sc forum</a> (tag <i>biapy</i>): describe your problem and "
						+ "attach the configuration file and the log, so others can help you.</p>");
		return sb.toString();
	}

	/** How to open the Zarr files the job writes, which are OME-Zarr images. */
	static String zarrFiles(String version) {
		boolean v05 = version.startsWith("0.5");
		StringBuilder sb = new StringBuilder("<h2>Zarr files</h2><p>The <i>.zarr</i> files the job creates are "
				+ "<a href='https://ngff.openmicroscopy.org'>OME-Zarr</a> " + esc(version) + " images (Zarr format "
				+ (v05 ? "3" : "2") + "): besides the data, they store the order of their axes and the size of their "
				+ "voxels, so other tools open them correctly. Open them in Fiji with File &gt; Import &gt; "
				+ "HDF5/N5/Zarr/OME-NGFF.</p>");
		if (v05)
			sb.append("<p><b>Fiji reads OME-Zarr 0.5 (Zarr format 3) since the <a href='").append(BiaPyFiji.URL_FIJI_ZARR3)
					.append("'>new release of its N5 plugins</a></b>: if Fiji can't open them, update it (Help &gt; "
							+ "Update...). For tools that only read the previous version, set <i>DATA.OME_ZARR.VERSION</i> "
							+ "to 0.4 in the configuration.</p>");
		return sb.toString();
	}

	/** The main folders of the results of each workflow. */
	private static String resultFolders(String workflow, JsonObject info) {
		switch (workflow) {
		case "SEMANTIC_SEG":
			return "<li><i>per_image</i>: the model's raw prediction: for each pixel, how likely it is to be of each "
					+ "class (from 0 to 1).</li><li><i>per_image_binarized</i>: the final masks, each pixel with its most "
					+ "likely class (with two classes, the threshold is chosen automatically, with Otsu's method).</li>";
		case "INSTANCE_SEG":
			return "<li><i>per_image</i>: the model's raw prediction, the helper images described below.</li>"
					+ "<li><i>per_image_instances</i>: the final objects, each with its own number (in Fiji, a lookup "
					+ "table such as <i>glasbey</i> tells them apart). If there is a <i>per_image_post_processing</i> "
					+ "folder, it has them after extra cleaning.</li>"
					+ (info != null && info.has("tracking") && info.get("tracking").getAsBoolean()
							? "<li><i>tracking</i>: the objects tracked over the time-lapse: the masks of each frame "
									+ "(<i>mask000.tif</i>...), where each object keeps the number of its track in all "
									+ "the frames, and the tracks in the Cell Tracking Challenge format (<i>res_track.txt"
									+ "</i>: track number, first and last frame, and parent track when it comes from a "
									+ "division) and as GEFF (<i>tracks.geff</i>).</li>"
							: "");
		case "DETECTION":
			return "<li><i>per_image</i>: the model's raw prediction: how likely each pixel is to be the center of an "
					+ "object.</li><li><i>per_image_local_max_check</i>: the points found, as an image and as a table per "
					+ "image (<i>&lt;image&gt;_points.csv</i>) with their coordinates.</li>";
		case "DENOISING":
			return "<li><i>per_image</i>: the denoised images.</li>";
		case "SUPER_RESOLUTION":
			return "<li><i>per_image</i>: the higher resolution images.</li>";
		case "IMAGE_TO_IMAGE":
			return "<li><i>per_image</i>: the predicted images.</li>";
		case "CLASSIFICATION":
			return "<li><i>predictions.csv</i>: the category predicted for each image.</li>";
		case "SELF_SUPERVISED":
			return "<li><i>per_image</i>: the model's repair of the degraded test images, to check what it learned. Its "
					+ "main result, though, is the model itself (checkpoints).</li>";
		default:
			return "<li><i>per_image</i>: the predictions.</li>";
		}
	}

	/** How to look at the results and what to change to improve them. */
	private static String analysis(String workflow, JsonObject info, String thresholds) {
		StringBuilder sb = new StringBuilder("<h2>Looking at the results</h2>");
		switch (workflow) {
		case "SEMANTIC_SEG":
			sb.append("<p>Open an image of <i>per_image</i> next to its input: the bright pixels are the ones the model "
					+ "is sure about. If the masks of <i>per_image_binarized</i> miss faint objects or include noise, "
					+ "threshold <i>per_image</i> yourself in Fiji (Image &gt; Adjust &gt; Threshold) with a value that "
					+ "suits your images.</p>");
			break;
		case "INSTANCE_SEG":
			sb.append(instanceAnalysis(info, thresholds));
			break;
		case "DETECTION":
			JsonObject d = info != null && info.has("detection") ? info.getAsJsonObject("detection") : null;
			sb.append("<p>The points are the peaks of <i>per_image</i> that are above a threshold (")
					.append("<i>TEST.DET_MIN_TH_TO_BE_PEAK</i>").append(d != null ? ", now " + d.get("min_th") : "")
					.append(") and at least a distance apart (<i>TEST.DET_PEAK_LOCAL_MAX_MIN_DISTANCE</i>")
					.append(d != null ? ", now " + d.get("min_distance") + " pixels" : "").append(").</p><ul>")
					.append("<li>Objects without a point: lower the threshold.</li>")
					.append("<li>Points where there is no object: raise the threshold.</li>")
					.append("<li>Two points in the same object: raise the distance.</li></ul>")
					.append(reuseTip());
			break;
		case "CLASSIFICATION":
			sb.append("<p><i>predictions.csv</i> lists each test image with its predicted category. If the test images "
					+ "were sorted by category, the accuracy is at the end of the BiaPy output.</p>");
			break;
		case "SELF_SUPERVISED":
			sb.append("<p>If the repaired images look like the originals, the model learned the look of your images. "
					+ "Use its checkpoint as a pretrained model in another workflow (in the Wizard, a pretrained BiaPy "
					+ "model).</p>");
			break;
		default:
			sb.append("<p>Open each result next to its input (e.g. in Fiji, Image &gt; Stacks &gt; Tools &gt; "
					+ "Combine). If they show artifacts or blur, a longer training or more training images usually "
					+ "help.</p>");
		}
		return sb.toString();
	}

	private static String instanceAnalysis(JsonObject info, String thresholds) {
		StringBuilder sb = new StringBuilder();
		JsonObject inst = info != null && info.has("instance") ? info.getAsJsonObject("instance") : null;
		if (inst == null) {
			sb.append("<p>Each channel of <i>per_image</i> is one of the helper images the model predicts, and the "
					+ "post-processing builds the objects from them. Its settings are in "
					+ "<i>PROBLEM.INSTANCE_SEG</i> of the configuration.</p>");
			return sb.append(reuseTip()).toString();
		}
		// The channels of the raw prediction, in order
		sb.append("<p><i>per_image</i> has one channel per helper image the model predicts (in Fiji, move through "
				+ "them with the C slider):</p><ol>");
		int index = 1;
		for (JsonElement e : inst.getAsJsonArray("outputs")) {
			JsonObject o = e.getAsJsonObject();
			String ch = o.get("channel").getAsString();
			int count = o.get("count").getAsInt();
			String key = "P".equals(ch) && o.has("type") && !o.get("type").isJsonNull()
					&& "skeleton".equals(o.get("type").getAsString()) ? "P_skeleton" : ch;
			String range = count == 1 ? "Channel " + index : "Channels " + index + "-" + (index + count - 1);
			sb.append("<li value='").append(index).append("'>").append(range).append(": ")
					.append(CHANNELS.getOrDefault(key, "<b>" + esc(ch) + "</b>")).append("</li>");
			index += count;
		}
		sb.append("</ol>");

		String creation = inst.get("creation").getAsString();
		sb.append("<p class='muted'>The settings named below are under <i>PROBLEM.INSTANCE_SEG</i> in the configuration "
				+ "file.</p>");
		switch (creation) {
		case "watershed":
			sb.append(watershed(inst.getAsJsonObject("watershed"), thresholds));
			break;
		case "stardist":
			JsonObject sd = inst.getAsJsonObject("stardist");
			sb.append("<p><b>How the objects are built (StarDist)</b>: each object is a polygon (a polyhedron in 3D) "
					+ "around a center. The centers are the pixels where the model is sure there is an object, and the "
					+ "shape comes from the radial distances.</p><ul>")
					.append("<li><i>STARDIST.PROB_THRESH</i> (now ").append(sd.get("prob_thresh"))
					.append("): how sure the model must be to create an object. Missing objects: lower it; false "
							+ "objects: raise it.</li>")
					.append("<li><i>STARDIST.NMS_IOU_THRESH</i> (now ").append(sd.get("nms_iou_thresh"))
					.append("): how much two objects may overlap before one of them is discarded. Touching objects "
							+ "lost: raise it; the same object twice: lower it.</li></ul>");
			break;
		case "gradient-flow":
			JsonObject cp = inst.getAsJsonObject("cellpose");
			sb.append("<p><b>How the objects are built (Cellpose)</b>: each pixel of the foreground follows the flows "
					+ "towards the center of its object, and the pixels that meet at the same center form one object.</p><ul>")
					.append("<li><i>CELLPOSE.FG_THRESH</i> (now ").append(cp.get("fg_thresh"))
					.append("): the foreground threshold. Objects too small or missing: lower it; too large: raise "
							+ "it.</li>")
					.append("<li><i>CELLPOSE.FLOW_THRESHOLD</i> (now ").append(cp.get("flow_threshold"))
					.append("): objects whose flows look wrong are discarded. Real objects lost: raise it; spurious "
							+ "pieces: lower it.</li></ul>");
			break;
		case "embeddings":
			JsonObject es = inst.getAsJsonObject("embedseg");
			sb.append("<p><b>How the objects are built (embeddings)</b>: each pixel points to the center of its object, "
					+ "and the pixels that point to the same place are grouped, starting from the best centers.</p><ul>")
					.append("<li><i>EMBEDSEG.FG_THRESH</i> (now ").append(es.get("fg_thresh"))
					.append("): which pixels belong to some object. Objects too small: lower it.</li>")
					.append("<li><i>EMBEDSEG.SEED_THRESH</i> (now ").append(es.get("seed_thresh"))
					.append("): how good a center must be to start an object. Missing objects: lower it; false "
							+ "objects: raise it.</li></ul>");
			break;
		case "agglomeration":
			JsonObject ag = inst.getAsJsonObject("agglomeration");
			sb.append("<p><b>How the objects are built (agglomeration)</b>: the affinities first split the image into "
					+ "many small fragments, which are then merged when the affinity across their common border is "
					+ "high.</p><ul>")
					.append("<li><i>AGGLOMERATION.MERGE_TH</i> (now ").append(ag.get("merge_th"))
					.append("): how high the affinity must be to merge two fragments. Objects split into pieces: lower "
							+ "it; different objects merged: raise it.</li>")
					.append("<li><i>FRAGMENT_SEED_TH</i> (now ").append(ag.get("fragment_seed_th"))
					.append(") and <i>FRAGMENT_GROWTH_TH</i> (now ").append(ag.get("fragment_growth_th"))
					.append("): how the first fragments are made; rarely need changing.</li></ul>");
			break;
		default:
			sb.append("<p>The post-processing (<i>").append(esc(creation)).append("</i>) builds the objects from them; "
					+ "its settings are in <i>PROBLEM.INSTANCE_SEG</i> of the configuration.</p>");
		}
		return sb.append(reuseTip()).toString();
	}

	/** The marker-controlled watershed: seeds, surface and mask of this job, with the figure. */
	private static String watershed(JsonObject ws, String thresholds) {
		StringBuilder sb = new StringBuilder("<p><b>How the objects are built (watershed)</b>, in three steps:</p>");
		URL fig = BiaPyFiji.resource("images/workflow_info/watershed.png");
		if (fig != null) {
			javax.swing.ImageIcon ic = new javax.swing.ImageIcon(fig);
			sb.append("<p align='center'><img src='").append(fig).append("' width='").append(ic.getIconWidth())
					.append("' height='").append(ic.getIconHeight()).append("'><br><span class='small'>An example with "
							+ "nuclei (DSB2018 dataset) and the foreground + contour representation. Top: what the model "
							+ "predicts (<i>per_image</i>). Middle: the three steps. Bottom: the objects. Seeds and objects "
							+ "share colors: each seed grows into one object.</span></p>");
		}
		List<String> seeds = strings(ws.getAsJsonArray("seed_channels"));
		List<String> seedThs = strings(ws.getAsJsonArray("seed_ths"));
		List<String> mask = strings(ws.getAsJsonArray("growth_mask_channels"));
		List<String> maskThs = strings(ws.getAsJsonArray("growth_mask_ths"));
		sb.append("<ol><li><b>Seeds</b>: one starting region per object, where ").append(conditions(seeds))
				.append(". Each seed becomes one object, so their number decides how many objects there are.</li>")
				.append("<li><b>Topographic surface</b>: the ").append(name(ws.get("topographic_surface").getAsString()))
				.append(" channel. The seeds grow over it like water filling valleys, and two objects meet where their "
						+ "water meets.</li>")
				.append("<li><b>Growth mask</b>: the objects can only grow where ").append(conditions(mask))
				.append(", which decides how large they are.</li></ol>");
		sb.append("<p>The thresholds are in the configuration: <i>WATERSHED.SEED_CHANNELS_THRESH</i> (one per seed "
				+ "channel: ").append(esc(String.join(", ", seedThs)))
				.append(") and <i>WATERSHED.GROWTH_MASK_CHANNELS_THRESH</i> (").append(esc(String.join(", ", maskThs)))
				.append("). <i>auto</i> chooses each one from the image (with Otsu's method)");
		if (thresholds != null) sb.append("; for the last test image BiaPy used ").append(esc(thresholds));
		sb.append(". To set them yourself, write numbers between 0 and 1 instead:</p><ul>")
				.append("<li>Touching objects merged into one: make the seeds stricter, so that each object gets its own ")
				.append(seedHint(seeds, true)).append(".</li>")
				.append("<li>One object split into several: make the seeds looser ").append(seedHint(seeds, false))
				.append(".</li>")
				.append("<li>Objects too small or too large: change the growth mask threshold (")
				.append(LOW_INSIDE.contains(mask.isEmpty() ? "" : mask.get(0)) ? "higher" : "lower")
				.append(" = larger objects).</li></ul>");
		return sb.toString();
	}

	/** E.g. "Foreground is above its threshold and Contour below its threshold". */
	private static String conditions(List<String> channels) {
		List<String> parts = new ArrayList<>();
		for (String ch : channels)
			parts.add(name(ch) + " is " + (LOW_INSIDE.contains(ch) ? "below" : "above") + " its threshold");
		return parts.isEmpty() ? "the model predicts objects" : String.join(" and ", parts);
	}

	/** How to make the seeds stricter (or looser), e.g. "(raise the Foreground threshold, or lower the Contour one)". */
	private static String seedHint(List<String> seeds, boolean stricter) {
		List<String> parts = new ArrayList<>();
		for (String ch : seeds) {
			if (HVZ.contains(ch)) continue; // combined into one boundary map, not thresholded one by one
			boolean low = LOW_INSIDE.contains(ch);
			parts.add((stricter != low ? "raise" : "lower") + " the " + name(ch) + " threshold");
		}
		return parts.isEmpty() ? "" : "(" + String.join(", or ", parts) + ")";
	}

	/** The short name of a channel, e.g. "Foreground". */
	private static String name(String ch) {
		String d = CHANNELS.get(ch);
		if (d == null) return ch;
		Matcher m = Pattern.compile("<b>(.*?)</b>").matcher(d);
		return m.find() ? m.group(1) : ch;
	}

	private static List<String> strings(JsonArray a) {
		List<String> out = new ArrayList<>();
		if (a != null) for (JsonElement e : a) out.add(e.getAsString());
		return out;
	}

	private static String reuseTip() {
		return "<p><b>Trying other settings without predicting again</b>: in the configuration file, set "
				+ "<i>TEST.REUSE_PREDICTIONS</i> to <i>True</i> and <i>TRAIN.ENABLE</i> to <i>False</i>, and run it "
				+ "again with the same job name. BiaPy then reads the raw predictions of <i>per_image</i> instead of "
				+ "predicting the images, and only builds the results again.</p>";
	}

	/**
	 * The thresholds BiaPy printed for the last test image ("Thresholds used: {'seed': [...], 'growth_mask': [...]}"),
	 * e.g. "0.53, 0.21 for the seeds and 0.26 for the growth mask"; null if not in the log.
	 */
	static String thresholdsUsed(File logFile) {
		if (logFile == null || !logFile.isFile()) return null;
		try {
			String last = null;
			for (String line : Files.readAllLines(logFile.toPath(), StandardCharsets.UTF_8))
				if (line.contains("Thresholds used:")) last = line;
			if (last == null) return null;
			Matcher seed = Pattern.compile("'seed': \\[([^\\]]*)\\]").matcher(last);
			Matcher mask = Pattern.compile("'growth_mask': \\[([^\\]]*)\\]").matcher(last);
			if (!seed.find() || !mask.find()) return null;
			return round(seed.group(1)) + " for the seeds and " + round(mask.group(1)) + " for the growth mask";
		} catch (Exception e) {
			return null;
		}
	}

	private static String round(String values) {
		List<String> out = new ArrayList<>();
		for (String v : values.split(",")) {
			String t = v.trim().replaceAll("np\\.float\\d*\\((.*)\\)", "$1");
			try {
				out.add(String.format(java.util.Locale.ROOT, "%.2f", Double.parseDouble(t)));
			} catch (NumberFormatException e) {
				out.add(t);
			}
		}
		return String.join(", ", out);
	}

	private static String esc(String s) {
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
