package io.github.biapyx.fiji.gui;

import java.io.File;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;

import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import com.google.gson.JsonObject;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.WindowManager;
import ij.gui.PointRoi;
import ij.macro.Interpreter;
import ij.measure.ResultsTable;
import io.github.biapyx.fiji.ScreenshotHarness;

/**
 * "Apply model" with a real model, headless (images in ImageJ's batch mode):
 * {@code ApplyModelHarness <shots dir> <2D instance segmentation .pth> <2D 1-channel image> <results of a 3D detection
 * test> <results of a classification test> <dir for the .npy checks>}.
 * The last three come from BiaPy's predict(image, return_prediction=False) (folders "results/<job>"); the .npy files
 * written in the last one are checked by the Python side of the test.
 */
public class ApplyModelHarness {
	static void check(boolean ok, String what) {
		if (!ok) throw new AssertionError(what);
		System.out.println("ok: " + what);
	}

	static ApplyModelPanel p;
	static File shots;
	static int shot;

	/** Memory used on a GPU, as nvidia-smi numbers them. */
	static int gpuMiB(String gpu) throws Exception {
		Process pr = new ProcessBuilder("nvidia-smi", "--query-gpu=memory.used", "--format=csv,noheader,nounits", "-i", gpu).start();
		try (java.util.Scanner sc = new java.util.Scanner(pr.getInputStream())) {
			return sc.nextInt();
		}
	}

	public static void main(String[] a) throws Exception {
		shots = new File(a[0]);
		Interpreter.batchMode = true;
		ImagePlus img = IJ.openImage(a[2]);
		img.setTitle("cells.tif");
		ImageStack st = new ImageStack(img.getWidth(), img.getHeight());
		for (int i = 0; i < 3; i++) st.addSlice(img.getProcessor().duplicate());
		ImagePlus stack = new ImagePlus("cells stack", st);
		ImagePlus rgb = new ImagePlus("rgb", img.getProcessor().convertToColorProcessor());
		for (ImagePlus i : new ImagePlus[] { img, stack, rgb }) Interpreter.addBatchModeImage(i);

		// The devices come from the environment check, as in the main window
		EnvMonitor.refresh();
		for (int i = 0; i < 120 && !"ok".equals(EnvMonitor.state()); i++) Thread.sleep(500);
		SwingUtilities.invokeAndWait(() -> p = new ApplyModelPanel());
		snap("empty");
		check(((JLabel) get("modelInfo")).getText().equals("No model selected yet.")
				&& ((JLabel) get("imageInfo")).getText().contains("No image is open"), "texts before anything is chosen");
		edt(() -> p.refreshImages());
		check(((javax.swing.JComboBox<?>) get("images")).getItemCount() == 3, "the images open are listed");

		// Step 4: a free GPU (the first one listed is the default)
		String gpu = System.getProperty("gpu", "0");
		edt(() -> {
			javax.swing.JComboBox<?> d = (javax.swing.JComboBox<?>) get("device");
			for (int i = 0; i < d.getItemCount(); i++) if (d.getItemAt(i).toString().startsWith("GPU " + gpu + " ")) d.setSelectedIndex(i);
		});
		// Step 1: the checkpoint is described as soon as it is chosen and the outputs of its workflow are offered; it is
		// loaded on the device when it is applied
		edt(() -> {
			((JTextField) get("checkpoint")).setText(a[1]);
			((JTextField) get("checkpoint")).postActionEvent();
		});
		waitIdle();
		JsonObject info = (JsonObject) get("loadedInfo");
		check(info != null && "INSTANCE_SEG".equals(info.get("workflow").getAsString()), "the model is read: " + info);
		check(get("loaded") == null && gpuMiB(gpu) < 50, "nothing loaded, GPU " + gpu + " untouched: " + gpuMiB(gpu) + " MiB");
		@SuppressWarnings("unchecked")
		List<JCheckBox> outs = (List<JCheckBox>) get("outputChecks");
		check(outs.size() == 2 && !outs.get(0).isSelected() && outs.get(1).isSelected(),
				"outputs: raw (unchecked) and instances (checked)");
		select("cells.tif");
		snap("loaded");

		// The whole image: raw output and instances
		edt(() -> outs.get(0).setSelected(true));
		apply();
		check(a[1].equals(get("loaded")) && gpu.equals(get("loadedDevice")) && gpuMiB(gpu) > 50,
				"applied on GPU " + gpu + ": " + gpuMiB(gpu) + " MiB");
		ImagePlus inst = image("BiaPy instances - cells"), raw = image("BiaPy raw output - cells");
		check(inst != null && inst.getWidth() == img.getWidth() && inst.getStackSize() == 1, "instances opened");
		check(inst.getStatistics().max > 1, "instances found: " + inst.getStatistics().max);
		check(raw != null && raw.getNChannels() >= 1, "raw output opened, " + raw.getNChannels() + " channels");
		java.awt.image.IndexColorModel lut = inst.getProcessor().getLut();
		check(lut.getRGB(0) == 0xff000000 && lut.getRed(1) != lut.getBlue(1) && lut.getRGB(1) != lut.getRGB(2),
				"instances with glasbey colors, background black");
		check(raw.getProcessor().getLut().getRGB(255) == 0xffffffff, "raw output left in grays");
		String html = ApplyModelPanel.outputsHtml(info, Arrays.asList("raw", "final"), img);
		check(html.contains("BiaPy instances - cells") && html.contains("glasbey") && html.contains("<b>Contour</b>")
				|| html.contains("<b>Foreground</b>"), "output info: windows, LUT and the model's channels");
		javax.swing.JEditorPane pane = UiUtils.htmlPane(UiUtils.htmlPage(html));
		ScreenshotHarness.save(new javax.swing.JScrollPane(pane), new File(shots, "apply_output_info.png"), 640, 760);

		// A stack: each slice with the 2D model
		select("cells stack");
		check(((JLabel) get("imageInfo")).getText().contains("each slice"), "a 2D model on a stack: each slice");
		snap("stack");
		apply();
		ImagePlus instStack = image("BiaPy instances - cells stack");
		check(instStack != null && instStack.getNSlices() == 3, "instances of each slice stacked");
		check(Arrays.equals((float[]) instStack.getStack().getPixels(3), (float[]) inst.getProcessor().getPixels()),
				"the same instances in each slice as in the image");

		// An image the model can't take
		select("rgb");
		check(((JLabel) get("imageInfo")).getText().contains("1-channel images, not 3"), "RGB refused: 1 channel model");
		snap("rgb");

		// A zoo model, as "Find models" gives it: described from its search entry, downloaded only when applied
		chooseZoo("stupendous-blowfish");
		info = (JsonObject) get("loadedInfo");
		check(info != null && "stupendous-blowfish".equals(get("described")) && a[1].equals(get("loaded")),
				"zoo model described, not downloaded: " + info);
		check(outs.size() == 2 && ((javax.swing.JButton) get("find")).isEnabled(), "its outputs offered, Step 1 unlocked");
		JCheckBox keep = (JCheckBox) get("keep");
		check(keep.getText().matches("Keep a copy on this computer \\(\\d+ MB\\), not to download it again") && !keep.isSelected(),
				"its size offered to keep it: " + keep.getText());
		select("cells.tif");
		snap("zoo_loaded");
		// Not kept: downloaded only while it is built
		File kept = new File(System.getProperty("bmzfolder"), "stupendous-blowfish/biapy_download.json");
		apply();
		check(!kept.exists(), "not kept");
		check(image("BiaPy instances (2) - cells") != null, "a second result of the same image: '(2)' in its title");
		// Kept on this computer (after confirming the space it takes), although it is loaded already
		edt(() -> keep.setSelected(true));
		apply();
		check(kept.isFile() && !keep.isEnabled() && keep.getText().startsWith("Kept on this computer"), "kept: " + kept);

		// Another device: nothing happens until the model is applied, then BiaPy restarts there (the Python process
		// can't change its device)
		edt(() -> ((javax.swing.JComboBox<?>) get("device")).setSelectedItem("CPU"));
		check(get("running") == null && gpu.equals(get("loadedDevice")), "choosing the device loads nothing");
		apply();
		check("".equals(get("loadedDevice")) && "stupendous-blowfish".equals(get("loaded")) && gpuMiB(gpu) < 50,
				"applied on the CPU, GPU " + gpu + " freed: " + gpuMiB(gpu) + " MiB");
		check(image("BiaPy instances (4) - cells") != null, "the result on the CPU");
		edt(() -> {
			javax.swing.JComboBox<?> d = (javax.swing.JComboBox<?>) get("device");
			for (int i = 0; i < d.getItemCount(); i++) if (d.getItemAt(i).toString().startsWith("GPU " + gpu + " ")) d.setSelectedIndex(i);
		});
		apply();
		check(gpu.equals(get("loadedDevice")) && gpuMiB(gpu) > 50, "back on GPU " + gpu + ": " + gpuMiB(gpu) + " MiB");

		// The download as it goes: how much and about how long it still takes
		edt(() -> {
			try {
				Field f = ApplyModelPanel.class.getDeclaredField("downloadStart");
				f.setAccessible(true);
				f.set(p, System.currentTimeMillis() - 10000);
				java.lang.reflect.Method m = ApplyModelPanel.class.getDeclaredMethod("showDownload", long.class, long.class);
				m.setAccessible(true);
				m.invoke(p, 50_000_000L, 200_000_000L);
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		});
		check(((JLabel) get("status")).getText().equals("Downloading 'stupendous-blowfish': 25% of 200 MB, about 30 s left"),
				"download progress: " + ((JLabel) get("status")).getText());

		// An RGB Cellpose model and a grayscale image: refused before anything is downloaded or loaded
		chooseZoo("powerful-sloth");
		check(((JLabel) get("imageInfo")).getText().contains("3-channel images, not 1"), "the image doesn't fit, in Step 2");
		edt(() -> ((javax.swing.JButton) get("apply")).doClick(0));
		check(get("running") == null && "stupendous-blowfish".equals(get("loaded")), "Apply refused at once, nothing loaded");
		String why = ApplyModelPanel.problem(img, (JsonObject) get("loadedInfo"), "powerful-sloth");
		check(why.equals("The model selected (powerful-sloth) takes 3-channel images, but 'cells.tif' has 1 channel. Please "
				+ "choose another model, or an image with 3 channels."), "the message of Apply: " + why);

		// A CartoCell model (channels F, C and M): every channel is named in the output info
		chooseZoo("merry-water-buffalo");
		String carto = ApplyModelPanel.outputsHtml((JsonObject) get("loadedInfo"), Arrays.asList("raw"), img);
		check(carto.contains("Channel 1: <b>Foreground</b>") && carto.contains("Channel 2: <b>Contour</b>")
				&& carto.contains("Channel 3: <b>Mask</b>"), "CartoCell channels named: " + get("loadedInfo"));
		javax.swing.JEditorPane cpane = UiUtils.htmlPane(UiUtils.htmlPage(carto));
		ScreenshotHarness.save(new javax.swing.JScrollPane(cpane), new File(shots, "apply_output_info_cartocell.png"), 640, 760);

		// Cancel while another command (a zoo search) keeps the Python side busy: it stops waiting at once
		io.github.biapyx.fiji.env.PythonBridge search = new io.github.biapyx.fiji.env.PythonBridge();
		JsonObject all = new JsonObject();
		all.addProperty("workflow", "all");
		all.addProperty("ndim", "all");
		all.addProperty("nchannels", "all");
		Thread searching = new Thread(() -> {
			try {
				search.call("find_models", all, null);
			} catch (Exception e) {
				System.out.println("search: " + e.getMessage());
			}
		});
		searching.start();
		Thread.sleep(3000);
		edt(() -> {
			((javax.swing.JTabbedPane) get("source")).setSelectedIndex(0);
		});
		Thread.sleep(1000);
		check(get("running") != null, "the checkpoint waits for the search");
		long t0 = System.currentTimeMillis();
		edt(() -> ((javax.swing.JButton) get("cancel")).doClick(0));
		waitIdle();
		check(System.currentTimeMillis() - t0 < 5000 && "Canceled.".equals(((JLabel) get("status")).getText()),
				"canceled while waiting, in " + (System.currentTimeMillis() - t0) + " ms");
		search.cancel();
		searching.join();

		// Cancel while the model loads (Apply): the Python process is stopped; what the model is stays known
		edt(() -> invoke("modelChanged"));
		waitIdle();
		check(a[1].equals(get("described")) && outs.size() == 2, "the checkpoint described again");
		edt(() -> ((javax.swing.JButton) get("apply")).doClick(0));
		Thread.sleep(2000);
		t0 = System.currentTimeMillis();
		edt(() -> ((javax.swing.JButton) get("cancel")).doClick(0));
		waitIdle();
		check(System.currentTimeMillis() - t0 < 5000 && get("loaded") == null && outs.size() == 2
				&& "Canceled.".equals(((JLabel) get("status")).getText()),
				"canceled while loading, in " + (System.currentTimeMillis() - t0) + " ms, Step 3 kept");
		snap("canceled");
		// A window opened after the environment check gets its state (the main window's box was empty)
		HomePanel[] home = new HomePanel[1];
		edt(() -> home[0] = new HomePanel(null));
		edt(() -> {});
		// The window of the models downloaded (the broom): their folders and sizes, measured in the background
		ModelFoldersDialog[] folders = new ModelFoldersDialog[1];
		edt(() -> {
			folders[0] = new ModelFoldersDialog();
			folders[0].measure();
		});
		JLabel[] cache = null, where = null;
		for (int i = 0; i < 120; i++) {
			cache = (JLabel[]) field(ModelFoldersDialog.class, folders[0], "labels");
			if (cache[0].getText().contains(":")) break;
			Thread.sleep(500);
		}
		edt(() -> {});
		where = (JLabel[]) field(ModelFoldersDialog.class, folders[0], "paths");
		check(cache[0].getText().endsWith(": 11 MB") && where[0].getText().endsWith("bmz_models")
				&& where[1].getText().endsWith("bioimageio"), "the folders of the models downloaded: " + cache[0].getText()
						+ " " + where[0].getText() + " | " + cache[1].getText() + " " + where[1].getText());
		ScreenshotHarness.save(folders[0], new File(shots, "apply_model_folders.png"), 640, folders[0].getPreferredSize().height);
		ScreenshotHarness.save(home[0], new File(shots, "apply_install_tab.png"), 820, 700);
		edt(() -> home[0] = new HomePanel(page -> {}));
		edt(() -> {});
		ScreenshotHarness.save(home[0], new File(shots, "main_home.png"), 980, 900);

		// Detection (3D) and classification results of BiaPy, as they are opened
		ImagePlus vol = IJ.createImage("vol", "8-bit black", 64, 64, 16);
		ApplyModelPanel.Results det = new ApplyModelPanel.Results("DETECTION", Arrays.asList("final"), false);
		det.collect(new File(a[3]), 0);
		ResultsTable pts = det.pointsTable(vol);
		int csvRows = java.nio.file.Files.readAllLines(new File(a[3], "per_image_local_max_check_post_processing/in_memory_image_points.csv")
				.toPath()).size() - 1;
		check(pts.size() == csvRows && vol.getRoi() instanceof PointRoi && ((PointRoi) vol.getRoi()).size() == csvRows,
				"detection: " + csvRows + " points in the table and as a selection");
		check(pts.getColumnIndex("Z") >= 0, "3D points have their Z");
		ApplyModelPanel.Results cls = new ApplyModelPanel.Results("CLASSIFICATION", Arrays.asList("final"), false);
		cls.collect(new File(a[4]), 1);
		check(cls.classTable(img).size() == 1 && !cls.classTable(img).getStringValue("Class", 0).isEmpty(),
				"classification: the class in a table");

		// The images as BiaPy takes them (checked in Python)
		ImagePlus hyper = IJ.createImage("hyper", "16-bit ramp", 20, 10, 2, 3, 1);
		for (int i = 1; i <= hyper.getStackSize(); i++) hyper.getStack().getProcessor(i).add(i * 1000);
		ApplyModelPanel.writeNpy(new File(a[5], "hyper_all.npy"), hyper, 0, 1);
		ApplyModelPanel.writeNpy(new File(a[5], "hyper_z2.npy"), hyper, 2, 1);
		ApplyModelPanel.writeNpy(new File(a[5], "rgb.npy"), rgb, 1, 1);
		IJ.saveAsTiff(hyper, new File(a[5], "hyper.tif").getAbsolutePath());
		IJ.saveAsTiff(rgb, new File(a[5], "rgb.tif").getAbsolutePath());
		System.out.println("ALL OK");
		System.exit(0);
	}

	private static final java.util.Map<String, JsonObject> ZOO = new java.util.HashMap<>();

	/** Chooses a zoo model as "Find models" does, with its entry of the search (from the cache in -Dbmzcache). */
	static void chooseZoo(String id) throws Exception {
		if (ZOO.isEmpty()) {
			JsonObject args = new JsonObject();
			args.addProperty("workflow", "all");
			args.addProperty("ndim", "all");
			args.addProperty("cache", System.getProperty("bmzcache"));
			for (com.google.gson.JsonElement e : new io.github.biapyx.fiji.env.PythonBridge().call("find_models", args, null)
					.getAsJsonArray("models"))
				ZOO.put(e.getAsJsonObject().get("id").getAsString(), e.getAsJsonObject());
		}
		edt(() -> {
			try {
				Field f = ApplyModelPanel.class.getDeclaredField("zooEntry");
				f.setAccessible(true);
				f.set(p, ZOO.get(id));
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
			((JTextField) get("zooModel")).setText(id);
			javax.swing.JTabbedPane tabs = (javax.swing.JTabbedPane) get("source");
			if (tabs.getSelectedIndex() != 1) tabs.setSelectedIndex(1);
			else invoke("modelChanged");
		});
		waitIdle();
	}

	static void apply() throws Exception {
		boolean[] locked = { false };
		edt(() -> {
			((javax.swing.JButton) get("apply")).doClick(0);
			locked[0] = !((javax.swing.JComboBox<?>) get("images")).isEnabled() && !((javax.swing.JComboBox<?>) get("device")).isEnabled()
					&& !((javax.swing.JButton) get("find")).isEnabled() && !((JTextField) get("checkpoint")).isEnabled();
		});
		check(locked[0], "Step 1, the image and the device can't be changed while the model is applied");
		waitIdle();
		check(((javax.swing.JComboBox<?>) get("images")).isEnabled(), "and they can afterwards");
		String status = ((JLabel) get("status")).getText();
		check(status.startsWith("Done"), "applied: " + status);
	}

	static void select(String title) throws Exception {
		edt(() -> {
			javax.swing.JComboBox<?> box = (javax.swing.JComboBox<?>) get("images");
			for (int i = 0; i < box.getItemCount(); i++) if (box.getItemAt(i).toString().equals(title)) box.setSelectedIndex(i);
		});
	}

	static ImagePlus image(String title) {
		for (int id : WindowManager.getIDList()) {
			ImagePlus i = WindowManager.getImage(id);
			if (i != null && title.equals(i.getTitle())) return i;
		}
		return null;
	}

	static void waitIdle() throws Exception {
		Thread.sleep(300);
		for (int i = 0; i < 1200 && get("running") != null; i++) Thread.sleep(250);
		edt(() -> {});
	}

	static void invoke(String method) {
		try {
			java.lang.reflect.Method m = ApplyModelPanel.class.getDeclaredMethod(method);
			m.setAccessible(true);
			m.invoke(p);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	static Object field(Class<?> c, Object o, String name) throws Exception {
		Field f = c.getDeclaredField(name);
		f.setAccessible(true);
		return f.get(o);
	}

	static Object get(String field) {
		try {
			Field f = ApplyModelPanel.class.getDeclaredField(field);
			f.setAccessible(true);
			return f.get(p);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	static void snap(String name) throws Exception {
		edt(() -> ScreenshotHarness.save(p, new File(shots, String.format("apply_%02d_%s.png", shot++, name)), 640,
				p.getPreferredSize().height));
	}

	static void edt(Runnable r) throws Exception {
		SwingUtilities.invokeAndWait(r);
	}
}
