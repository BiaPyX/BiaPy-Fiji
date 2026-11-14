package io.github.biapyx.fiji.gui;

import java.awt.Color;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.SwingUtilities;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.Macro;
import ij.WindowManager;
import ij.gui.PointRoi;
import ij.measure.Calibration;
import ij.measure.ResultsTable;
import ij.plugin.frame.Recorder;
import ij.process.ByteProcessor;
import ij.process.FloatProcessor;
import ij.process.ShortProcessor;
import io.github.biapyx.fiji.env.PythonBridge;
import io.github.biapyx.fiji.env.PythonEnv;

/**
 * Applies a model (a BiaPy checkpoint or a BioImage Model Zoo model) to an image open in Fiji through the bridge's
 * server, where the model stays loaded between images (commands describe_model, load_model and apply_model, BiaPy's
 * predict()). Used by the "Apply model" window ({@link ApplyModelPanel}) and by its macro command:
 *
 * <pre>
 * run("Apply model", "model=[/path/to/model.pth] outputs=[final post] device=0");
 * </pre>
 *
 * "model" is a .pth checkpoint or a zoo nickname; "outputs" (raw, final, post) and "device" (a GPU number, mps or cpu)
 * are optional: the outputs the window opens by default, and the device used already or the GPU with most free memory.
 * The model is applied to the current image and its outputs are opened as in the window. Applying a model in the window
 * records this command.
 */
public final class ModelApplier {

	/** The macro command, as in plugins.config. */
	static final String COMMAND = "Apply model";
	private static File workDir;

	private ModelApplier() {}

	static synchronized File workDir() throws IOException {
		if (workDir == null) workDir = Files.createTempDirectory("biapy-apply-model").toFile();
		return workDir;
	}

	/**
	 * What the model is (describe_model), without downloading it, building it or touching the GPU: a .pth checkpoint, or
	 * a zoo model by its search entry ({@code zoo}).
	 */
	static JsonObject describe(PythonBridge bridge, String model, JsonObject zoo, Consumer<String> log) throws Exception {
		if (zoo == null) {
			if (!new File(model).isFile()) throw new PythonBridge.BridgeException("The checkpoint file does not exist: " + model);
			if (!model.endsWith(".pth"))
				throw new PythonBridge.BridgeException("Choose a .pth checkpoint: other files (e.g. .safetensors) don't hold "
						+ "the configuration of the model.");
		}
		JsonObject args = new JsonObject();
		args.addProperty("model", model);
		if (zoo != null) args.add("zoo", zoo);
		return bridge.call("describe_model", args, log);
	}

	/**
	 * Downloads (zoo; kept if {@code keep}) and loads the model on {@code gpu} (a GPU number, "mps", "" for the CPU, or
	 * null: the device used already, else the GPU with most free memory), unless it is loaded already. The Python process
	 * can't change its device once it has used one: it is restarted then. Returns what the model is (load_model).
	 */
	static JsonObject load(PythonBridge bridge, String model, String gpu, boolean keep, Consumer<String> log) throws Exception {
		JsonObject args = new JsonObject();
		args.addProperty("model", model);
		args.addProperty("out_dir", workDir().getAbsolutePath());
		if (gpu != null) args.addProperty("gpu", gpu);
		args.addProperty("keep", keep);
		JsonObject info = bridge.call("load_model", args, log);
		if (info.has("restart")) {
			log.accept("Restarting BiaPy to use " + deviceName(gpu) + " . . .");
			PythonBridge.restartServer();
			info = bridge.call("load_model", args, log);
		}
		return info;
	}

	static String deviceName(String gpu) {
		return gpu == null ? "the device chosen" : gpu.isEmpty() ? "the CPU" : gpu.equals("mps") ? "Apple's GPU" : "GPU " + gpu;
	}

	/**
	 * Applies the model loaded ({@link #load}, same {@code model} and {@code gpu}) to the image (each slice of it for a 2D
	 * model, its current frame), collecting the outputs wanted ({@code keys}). {@code busy} gets what is being done.
	 */
	static Results apply(PythonBridge bridge, ImagePlus imp, JsonObject info, String model, String gpu, List<String> keys,
			Consumer<String> busy) throws Exception {
		boolean is3d = "3D".equals(info.get("ndim").getAsString());
		int[] slices;
		if (is3d) {
			slices = new int[] { 0 }; // all the slices together
		} else if (imp.getNSlices() > 1) {
			slices = new int[imp.getNSlices()];
			for (int z = 0; z < slices.length; z++) slices[z] = z + 1;
		} else {
			slices = new int[] { 1 };
		}
		Results results = new Results(info.get("workflow").getAsString(), keys, !is3d && slices.length > 1);
		File npy = new File(workDir(), "image.npy");
		try {
			for (int i = 0; i < slices.length; i++) {
				String which = slices.length > 1 ? " (slice " + (i + 1) + " of " + slices.length + ")" : "";
				busy.accept("Sending '" + imp.getTitle() + "'" + which + " to BiaPy . . .");
				writeNpy(npy, imp, slices[i], imp.getFrame());
				busy.accept("BiaPy is predicting '" + imp.getTitle() + "'" + which + " . . .");
				JsonObject args = new JsonObject();
				args.addProperty("model", model);
				if (gpu != null) args.addProperty("gpu", gpu);
				args.addProperty("out_dir", workDir().getAbsolutePath());
				args.addProperty("image", npy.getAbsolutePath());
				com.google.gson.JsonArray wanted = new com.google.gson.JsonArray();
				for (String k : keys) wanted.add(k);
				args.add("outputs", wanted);
				results.collect(bridge.call("apply_model", args, null), slices[i]);
			}
		} finally {
			npy.delete();
		}
		busy.accept("Opening the results in Fiji . . .");
		return results;
	}

	/** The outputs a workflow opens by default (Step 3's boxes checked at first). */
	static List<String> defaultOutputs(JsonObject info) {
		List<String> keys = new ArrayList<>();
		for (String[] o : ApplyModelPanel.outputs(info.get("workflow").getAsString(), info.get("post_processing").getAsBoolean()))
			if (!o[2].isEmpty()) keys.add(o[0]);
		return keys;
	}

	/////////////////////////////////
	// The macro command
	/////////////////////////////////
	/** Records the macro command of applying the model to {@code imp} (if the Recorder is open). */
	static void record(ImagePlus imp, String model, List<String> keys, String gpu) {
		if (!Recorder.record) return;
		Recorder.record("selectImage", imp.getTitle());
		Recorder.setCommand(COMMAND);
		Recorder.recordOption("model", model);
		Recorder.recordOption("outputs", String.join(" ", keys));
		Recorder.recordOption("device", gpu.isEmpty() ? "cpu" : gpu);
		Recorder.saveCommand();
	}

	/**
	 * The macro command: applies the model of {@code options} to the current image and opens its outputs. Errors are
	 * shown and abort the macro.
	 */
	public static void runMacro(String options) {
		ImagePlus imp = WindowManager.getCurrentImage();
		String model = Macro.getValue(options, "model", "").trim();
		String outputs = Macro.getValue(options, "outputs", "").trim();
		String device = Macro.getValue(options, "device", null);
		String gpu = device == null ? null : device.trim().equalsIgnoreCase("cpu") ? "" : device.trim().toLowerCase();
		try {
			if (imp == null) throw new PythonBridge.BridgeException("Open the image to apply the model to first.");
			if (model.isEmpty())
				throw new PythonBridge.BridgeException("Say which model to apply: model=[<checkpoint .pth or zoo nickname>].");
			if (!PythonEnv.pythonExists())
				throw new PythonBridge.BridgeException("BiaPy is not installed: install it with Plugins > BiaPy > Apply model.");
			List<String> keys = outputs.isEmpty() ? null : Arrays.asList(outputs.split("[\\s,]+"));
			if (keys != null) for (String k : keys)
				if (!Arrays.asList("raw", "final", "post").contains(k))
					throw new PythonBridge.BridgeException("Unknown output '" + k + "': choose among raw, final and post.");
			PythonBridge bridge = new PythonBridge();
			Consumer<String> status = line -> {
				if (!line.startsWith("DOWNLOAD_")) IJ.showStatus(line);
			};
			String name = model.endsWith(".pth") ? new File(model).getName() : model;
			// A checkpoint is checked against the image before loading it; a zoo model, once loaded (its search entry,
			// which tells what it is, is not known here)
			if (model.endsWith(".pth")) check(imp, describe(bridge, model, null, status), name);
			status.accept("Loading the model '" + name + "' . . .");
			JsonObject info = load(bridge, model, gpu, false, status);
			check(imp, info, name);
			Results results = apply(bridge, imp, info, model, gpu, keys != null ? keys : defaultOutputs(info), status);
			SwingUtilities.invokeAndWait(() -> results.show(imp));
			IJ.showStatus("");
		} catch (Exception e) {
			String message = e instanceof java.lang.reflect.InvocationTargetException ? e.getCause().getMessage() : e.getMessage();
			IJ.error("BiaPy - Apply model", message);
			Macro.abort();
		}
	}

	private static void check(ImagePlus imp, JsonObject info, String model) throws PythonBridge.BridgeException {
		String problem = ApplyModelPanel.problem(imp, info, model);
		if (problem != null) throw new PythonBridge.BridgeException(problem);
	}

	/////////////////////////////////
	// The image, as BiaPy takes it
	/////////////////////////////////
	/**
	 * Writes the image as a NumPy file, (z, y, x, c) with {@code slice} 0 (all the slices of {@code frame}), otherwise
	 * (y, x, c) for that slice (1-based). RGB images have 3 channels; 8- and 16-bit images keep their type, which BiaPy's
	 * normalization depends on.
	 */
	static void writeNpy(File f, ImagePlus imp, int slice, int frame) throws IOException {
		int w = imp.getWidth(), h = imp.getHeight(), c = ApplyModelPanel.channels(imp);
		boolean rgb = imp.getType() == ImagePlus.COLOR_RGB;
		int bytes = rgb ? 1 : imp.getBitDepth() / 8;
		String descr = bytes == 1 ? "|u1" : bytes == 2 ? "<u2" : "<f4";
		int[] zs;
		if (slice == 0) {
			zs = new int[imp.getNSlices()];
			for (int z = 0; z < zs.length; z++) zs[z] = z + 1;
		} else {
			zs = new int[] { slice };
		}
		String shape = (slice == 0 ? zs.length + ", " : "") + h + ", " + w + ", " + c;
		StringBuilder header = new StringBuilder("{'descr': '" + descr + "', 'fortran_order': False, 'shape': (" + shape + "), }");
		while ((10 + header.length() + 1) % 64 != 0) header.append(' ');
		header.append('\n');
		ImageStack stack = imp.getStack();
		try (OutputStream out = new BufferedOutputStream(new FileOutputStream(f), 1 << 20)) {
			out.write(new byte[] { (byte) 0x93, 'N', 'U', 'M', 'P', 'Y', 1, 0, (byte) (header.length() & 0xff),
					(byte) (header.length() >> 8) });
			out.write(header.toString().getBytes(StandardCharsets.US_ASCII));
			ByteBuffer row = ByteBuffer.allocate(w * c * bytes).order(ByteOrder.LITTLE_ENDIAN);
			for (int z : zs) {
				Object[] pixels = new Object[rgb ? 1 : c];
				for (int ch = 0; ch < pixels.length; ch++)
					pixels[ch] = stack.getProcessor(imp.getStackIndex(ch + 1, z, frame)).getPixels();
				for (int y = 0; y < h; y++) {
					row.clear();
					for (int x = 0; x < w; x++) {
						int i = y * w + x;
						if (rgb) {
							int v = ((int[]) pixels[0])[i];
							row.put((byte) (v >> 16)).put((byte) (v >> 8)).put((byte) v);
						} else {
							for (int ch = 0; ch < c; ch++) {
								Object p = pixels[ch];
								if (bytes == 1) row.put(((byte[]) p)[i]);
								else if (bytes == 2) row.putShort(((short[]) p)[i]);
								else row.putFloat(((float[]) p)[i]);
							}
						}
					}
					out.write(row.array());
				}
			}
		}
	}

	/**
	 * Reads a NumPy file of BiaPy's outputs, (z, y, x, c) in C order: 8-bit (uint8, bool), 16-bit (uint16) or 32-bit (the
	 * other numbers, e.g. labels above 65535, probabilities) image with c channels and z slices.
	 */
	static ImagePlus readNpy(File f) throws IOException {
		try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f), 1 << 20))) {
			byte[] magic = new byte[8];
			in.readFully(magic);
			int length = magic[6] == 1 ? in.readUnsignedByte() | in.readUnsignedByte() << 8 : Integer.reverseBytes(in.readInt());
			byte[] h = new byte[length];
			in.readFully(h);
			String header = new String(h, StandardCharsets.ISO_8859_1);
			Matcher descr = Pattern.compile("'descr':\\s*'([<>|=])([a-z])(\\d+)'").matcher(header);
			Matcher shape = Pattern.compile("'shape':\\s*\\(([^)]*)\\)").matcher(header);
			if (!descr.find() || !shape.find() || header.contains("'fortran_order': True"))
				throw new IOException("Unsupported NumPy file " + f + ": " + header.trim());
			char kind = descr.group(2).charAt(0);
			int size = Integer.parseInt(descr.group(3));
			ByteOrder order = descr.group(1).equals(">") ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN;
			String[] dims = shape.group(1).split(",");
			if (dims.length != 4) throw new IOException("Expected a (z, y, x, c) array in " + f + ": " + header.trim());
			int nz = Integer.parseInt(dims[0].trim()), height = Integer.parseInt(dims[1].trim()),
					width = Integer.parseInt(dims[2].trim()), nc = Integer.parseInt(dims[3].trim());
			int bits = kind == 'b' || kind == 'u' && size == 1 ? 8 : kind == 'u' && size == 2 ? 16 : 32;
			if (bits == 32 && !(kind == 'f' && (size == 4 || size == 8)) && !((kind == 'i' || kind == 'u') && size <= 8))
				throw new IOException("Unsupported data type in " + f + ": " + descr.group());
			int plane = width * height;
			byte[] slab = new byte[plane * nc * size];
			ImageStack stack = new ImageStack(width, height);
			for (int z = 0; z < nz; z++) {
				in.readFully(slab);
				ByteBuffer b = ByteBuffer.wrap(slab).order(order);
				Object[] pixels = new Object[nc];
				for (int c = 0; c < nc; c++)
					pixels[c] = bits == 8 ? new byte[plane] : bits == 16 ? (Object) new short[plane] : new float[plane];
				for (int i = 0; i < plane; i++)
					for (int c = 0; c < nc; c++) {
						if (bits == 8) ((byte[]) pixels[c])[i] = b.get();
						else if (bits == 16) ((short[]) pixels[c])[i] = b.getShort();
						else ((float[]) pixels[c])[i] = (float) value(b, kind, size);
					}
				for (int c = 0; c < nc; c++)
					stack.addSlice(bits == 8 ? new ByteProcessor(width, height, (byte[]) pixels[c])
							: bits == 16 ? new ShortProcessor(width, height, (short[]) pixels[c], null)
									: new FloatProcessor(width, height, (float[]) pixels[c]));
			}
			ImagePlus imp = new ImagePlus("", stack);
			imp.setDimensions(nc, nz, 1);
			if (nc > 1 && nz > 1) imp.setOpenAsHyperStack(true);
			return imp;
		}
	}

	private static double value(ByteBuffer b, char kind, int size) {
		if (kind == 'f') return size == 4 ? b.getFloat() : b.getDouble();
		long v = size == 1 ? b.get() : size == 2 ? b.getShort() : size == 4 ? b.getInt() : b.getLong();
		if (kind == 'u' && size == 4) v &= 0xffffffffL;
		if (kind == 'u' && size == 8 && v < 0) return v + 0x1p64;
		return v;
	}

	/////////////////////////////////
	// The results, opened in Fiji
	/////////////////////////////////
	/** The outputs wanted of every slice the model was applied to, as BiaPy wrote them in its results folder. */
	static final class Results {
		private final String workflow;
		private final List<String> keys;
		/** A 2D model applied to each slice: the images of each output are stacked. */
		private final boolean perSlice;
		private final Map<String, List<ImagePlus>> images = new LinkedHashMap<>();
		/** Points detected: (slice of the image, 1-based, or 0 for a 3D model), z, y, x, probability (NaN if none). */
		private final List<double[]> points = new ArrayList<>();
		private final List<String[]> classes = new ArrayList<>();
		private final List<String> missing = new ArrayList<>();

		Results(String workflow, List<String> keys, boolean perSlice) {
			this.workflow = workflow;
			this.keys = keys;
			this.perSlice = perSlice;
		}

		/** Adds the outputs of the model applied to one slice (1-based, or 0 for a 3D model), as apply_model returns them. */
		void collect(JsonObject outputs, int slice) throws IOException {
			for (String key : keys) {
				if (!outputs.has(key)) {
					if (!missing.contains(key)) missing.add(key);
					continue;
				}
				JsonObject o = outputs.getAsJsonObject(key);
				if (o.has("value")) {
					classes.add(new String[] { String.valueOf(slice), o.get("value").getAsString() });
				} else if (o.has("columns")) {
					readPoints(o, slice);
				} else {
					File npy = new File(o.get("npy").getAsString());
					images.computeIfAbsent(key, k -> new ArrayList<>()).add(readNpy(npy));
					npy.delete();
				}
			}
		}

		/** BiaPy's table of points: columns axis-0, axis-1 (and axis-2 in 3D), from Z to X. */
		private void readPoints(JsonObject table, int slice) {
			List<String> header = new ArrayList<>();
			for (JsonElement c : table.getAsJsonArray("columns")) header.add(c.getAsString());
			int a0 = header.indexOf("axis-0"), a1 = header.indexOf("axis-1"), a2 = header.indexOf("axis-2"),
					prob = header.indexOf("probability");
			for (JsonElement r : table.getAsJsonArray("rows")) {
				com.google.gson.JsonArray v = r.getAsJsonArray();
				double z = a2 >= 0 ? v.get(a0).getAsDouble() : 0;
				double y = v.get(a2 >= 0 ? a1 : a0).getAsDouble(), x = v.get(a2 >= 0 ? a2 : a1).getAsDouble();
				points.add(new double[] { slice, z, y, x, prob >= 0 && !v.get(prob).isJsonNull() ? v.get(prob).getAsDouble() : Double.NaN });
			}
		}

		/** Opens the outputs in Fiji (call it on the EDT). */
		void show(ImagePlus source) {
			String title = source.getTitle().replaceFirst("\\.[A-Za-z0-9]+$", "");
			for (ImagePlus out : images(source)) out.show();
			if (keys.contains("final") && "DETECTION".equals(workflow)) showPoints(source, title);
			if (!classes.isEmpty()) classTable(source).show(uniqueTitle("BiaPy class", title));
			if (!missing.isEmpty())
				IJ.log("[BiaPy] The model did not give these outputs: " + String.join(", ", missing));
		}

		/** The output images, named after {@code source} and with its calibration. */
		List<ImagePlus> images(ImagePlus source) {
			String title = source.getTitle().replaceFirst("\\.[A-Za-z0-9]+$", "");
			List<ImagePlus> shown = new ArrayList<>();
			for (Map.Entry<String, List<ImagePlus>> e : images.entrySet()) {
				ImagePlus out = perSlice ? stack(e.getValue()) : e.getValue().get(0);
				out.setTitle(uniqueTitle("BiaPy " + name(workflow, e.getKey()), title));
				Calibration cal = source.getCalibration().copy();
				// e.g. super-resolution: the pixels are smaller
				cal.pixelWidth *= (double) source.getWidth() / out.getWidth();
				cal.pixelHeight *= (double) source.getHeight() / out.getHeight();
				out.setCalibration(cal);
				if (!"raw".equals(e.getKey()) && ("INSTANCE_SEG".equals(workflow) || "SEMANTIC_SEG".equals(workflow))) labels(out);
				shown.add(out);
			}
			return shown;
		}

		ResultsTable classTable(ImagePlus source) {
			ResultsTable rt = new ResultsTable();
			for (String[] c : classes) {
				rt.incrementCounter();
				rt.addValue("Image", source.getTitle());
				if (perSlice) rt.addValue("Slice", c[0]);
				rt.addValue("Class", c[1]);
			}
			return rt;
		}

		/**
		 * "&lt;what&gt; - &lt;image&gt;", or with " (2)", " (3)"... after what if a window of Fiji has that title already, as
		 * Fiji names duplicates.
		 */
		static String uniqueTitle(String what, String image) {
			java.util.Set<String> taken = new java.util.HashSet<>();
			for (String[] titles : new String[][] { WindowManager.getImageTitles(), WindowManager.getNonImageTitles() })
				if (titles != null) taken.addAll(Arrays.asList(titles));
			String title = what + " - " + image;
			for (int n = 2; taken.contains(title); n++) title = what + " (" + n + ") - " + image;
			return title;
		}

		/** What an output's window shows, the start of its title. */
		static String name(String workflow, String key) {
			if ("raw".equals(key)) return "raw output";
			if ("post".equals(key)) return "post-processed";
			return "INSTANCE_SEG".equals(workflow) ? "instances" : "prediction";
		}

		/** The 2D outputs of each slice, as a stack (a hyperstack if they have channels). */
		private static ImagePlus stack(List<ImagePlus> slices) {
			ImagePlus first = slices.get(0);
			int c = first.getStackSize();
			ImageStack stack = new ImageStack(first.getWidth(), first.getHeight());
			for (ImagePlus s : slices)
				for (int ch = 1; ch <= c; ch++) stack.addSlice(s.getStack().getProcessor(ch));
			ImagePlus out = new ImagePlus("", stack);
			out.setDimensions(c, slices.size(), 1);
			if (c > 1) out.setOpenAsHyperStack(true);
			return out;
		}

		/**
		 * A label image (classes or instances) with the "glasbey on dark" lookup table: label n in color n, the
		 * background black. Above 255 instances the colors are spread over all the labels.
		 */
		static void labels(ImagePlus imp) {
			imp.setLut(GLASBEY);
			double max = 0;
			for (int i = 1; i <= imp.getStackSize(); i++) max = Math.max(max, imp.getStack().getProcessor(i).getStatistics().max);
			imp.setDisplayRange(0, Math.max(255, max));
		}

		static final ij.process.LUT GLASBEY = glasbey();

		/** Fiji's glasbey_on_dark, or distinct colors like it where Fiji's lookup tables are not found. */
		private static ij.process.LUT glasbey() {
			String luts = IJ.getDirectory("luts");
			File file = luts == null ? null : new File(luts, "glasbey_on_dark.lut");
			ij.process.LUT lut = file != null && file.isFile() ? ij.plugin.LutLoader.openLut(file.getPath()) : null;
			if (lut != null) return lut;
			byte[] r = new byte[256], g = new byte[256], b = new byte[256];
			for (int i = 1; i < 256; i++) {
				int rgb = Color.HSBtoRGB((i * 0.618034f) % 1f, 0.55f + 0.45f * ((i * 7) % 3) / 2f, 0.7f + 0.3f * (i % 2));
				r[i] = (byte) (rgb >> 16);
				g[i] = (byte) (rgb >> 8);
				b[i] = (byte) rgb;
			}
			return new ij.process.LUT(r, g, b);
		}

		/** The points detected, on the image (a point selection) and in a table. */
		private void showPoints(ImagePlus source, String title) {
			if (points.isEmpty()) {
				IJ.showMessage("BiaPy", "No points were detected in " + source.getTitle() + ".");
				return;
			}
			pointsTable(source).show(uniqueTitle("BiaPy points", title));
		}

		/** The table of the points detected; they are also set as a point selection on {@code source}. */
		ResultsTable pointsTable(ImagePlus source) {
			ResultsTable rt = new ResultsTable();
			PointRoi roi = new PointRoi();
			boolean is3d = points.stream().anyMatch(p -> p[0] == 0) && source.getNSlices() > 1;
			for (double[] p : points) {
				int z = p[0] > 0 ? (int) p[0] : (int) Math.round(p[1]) + 1;
				int position = source.getStackSize() > 1 ? source.getStackIndex(source.getChannel(), z, source.getFrame()) : 0;
				roi.addPoint(p[3], p[2], position);
				rt.incrementCounter();
				rt.addValue("X", p[3]);
				rt.addValue("Y", p[2]);
				if (is3d || perSlice) rt.addValue("Z", z - 1);
				if (!Double.isNaN(p[4])) rt.addValue("Probability", p[4]);
			}
			if (!points.isEmpty()) source.setRoi(roi);
			return rt;
		}
	}
}
