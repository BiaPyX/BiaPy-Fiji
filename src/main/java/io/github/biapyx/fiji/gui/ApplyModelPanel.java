package io.github.biapyx.fiji.gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.io.BufferedOutputStream;
import java.io.File;
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

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.WindowManager;
import ij.gui.PointRoi;
import ij.measure.Calibration;
import ij.measure.ResultsTable;
import io.github.biapyx.fiji.BiaPyFiji;
import io.github.biapyx.fiji.env.PythonBridge;
import io.github.biapyx.fiji.env.PythonEnv;

/**
 * Content of "Apply model" ({@link ApplyModelWindow}): applies a model (a BiaPy checkpoint or a BioImage Model Zoo
 * model) to an image open in Fiji and opens its results in Fiji. The model stays loaded in the bridge's server between
 * images (bridge commands describe_model when it is chosen, load_model and apply_model when applied, BiaPy's predict()).
 */
public class ApplyModelPanel extends JPanel {

	private static final long serialVersionUID = 1L;

	/** Outputs of a workflow: key ("raw", "final", "post"), what it is, and whether it is opened by default. */
	static List<String[]> outputs(String workflow, boolean postProcessing) {
		List<String[]> o = new ArrayList<>();
		switch (workflow) {
		case "SEMANTIC_SEG":
			o.add(new String[] { "raw", "Raw output (class probabilities)", "" });
			o.add(new String[] { "final", "Final prediction (classes)", "on" });
			if (postProcessing) o.add(new String[] { "post", "Post-processed prediction", "on" });
			break;
		case "INSTANCE_SEG":
			o.add(new String[] { "raw", "Raw output (helper channels)", "" });
			o.add(new String[] { "final", "Final prediction (instances)", "on" });
			if (postProcessing) o.add(new String[] { "post", "Post-processed instances", "on" });
			break;
		case "DETECTION":
			o.add(new String[] { "raw", "Raw output (point probability)", "" });
			o.add(new String[] { "final", postProcessing ? "Points (post-processed)" : "Points detected", "on" });
			break;
		case "CLASSIFICATION":
			o.add(new String[] { "final", "Predicted class (table)", "on" });
			break;
		case "DENOISING":
			o.add(new String[] { "raw", "Denoised image", "on" });
			break;
		case "SUPER_RESOLUTION":
			o.add(new String[] { "raw", "Higher-resolution image", "on" });
			break;
		default: // image to image, self-supervised learning
			o.add(new String[] { "raw", "Predicted image", "on" });
		}
		return o;
	}

	/** Folders of BiaPy's test results with each output, the first that exists is used. */
	private static final Map<String, String[]> FOLDERS = new LinkedHashMap<>();
	static {
		FOLDERS.put("raw", new String[] { "per_image", "full_image" });
		FOLDERS.put("final", new String[] { "per_image_instances", "full_image_instances", "per_image_binarized",
				"full_image_binarized", "per_image_local_max_check_post_processing", "per_image_local_max_check" });
		FOLDERS.put("post", new String[] { "per_image_post_processing", "full_image_post_processing" });
	}

	/** Step 1's tabs: a checkpoint file or a zoo model. */
	private final javax.swing.JTabbedPane source = new javax.swing.JTabbedPane();
	private static final int CHECKPOINT = 0, ZOO = 1;
	private final JCheckBox keep = new JCheckBox();
	private final JTextField checkpoint = new JTextField(30);
	private final JTextField zooModel = new JTextField(30);
	private final JLabel modelInfo = note("No model selected yet.");
	private final JComboBox<ImageChoice> images = new JComboBox<>();
	private final JComboBox<String> device = new JComboBox<>();
	private final JLabel imageInfo = note("");
	private final JPanel outputsBox = new JPanel();
	private final List<JCheckBox> outputChecks = new ArrayList<>();
	private final JButton outputInfo = UiUtils.secondaryButton("Output info",
			"What each output is, how to look at it in Fiji and how it was obtained");
	private final JButton apply = UiUtils.primaryButton("Apply", "Apply the model to the image and open its results in Fiji");
	private final JButton cancel = UiUtils.secondaryButton("Cancel", "Stop the model");
	private final JProgressBar busyBar = new JProgressBar();
	private final JLabel status = new JLabel(" ");
	private final JPanel statusSlot = new JPanel(new java.awt.CardLayout());

	/**
	 * The model described (checkpoint path or zoo nickname) and what describe_model said of it, without downloading or
	 * loading it; the model loaded in Python (when applied) and its device, or null.
	 */
	private volatile String described, loaded, loadedDevice;
	private volatile JsonObject loadedInfo;
	/** The search entry of the zoo model chosen: what it is, without downloading it. */
	private JsonObject zooEntry;
	private volatile PythonBridge running;
	private static File workDir;
	private JButton browse, find;

	ApplyModelPanel() {
		super(new BorderLayout());
		setBackground(BiaPyFiji.WHITE);
		setBorder(BorderFactory.createEmptyBorder(14, 16, 12, 16));
		JPanel steps = new JPanel();
		steps.setOpaque(false);
		steps.setLayout(new BoxLayout(steps, BoxLayout.Y_AXIS));
		steps.add(step("Step 1: select a model", modelBox()));
		steps.add(Box.createVerticalStrut(12));
		steps.add(step("Step 2: select the image", imageBox()));
		steps.add(Box.createVerticalStrut(12));
		steps.add(step("Step 3: select the outputs to open", outputsBox));
		steps.add(Box.createVerticalStrut(12));
		steps.add(step("Step 4: select the device", deviceBox()));
		steps.add(Box.createVerticalStrut(12));
		steps.add(step("Step 5: understand the outputs", infoBox()));
		steps.add(Box.createVerticalStrut(12));
		add(steps, BorderLayout.NORTH);
		add(buttons(), BorderLayout.SOUTH);
		showOutputs(null);
		describeImage();
	}

	private JPanel deviceBox() {
		JPanel p = new JPanel(new BorderLayout(10, 0));
		device.setToolTipText("Where BiaPy runs the model: a GPU is much faster than the CPU");
		p.add(device, BorderLayout.CENTER);
		updateDevices(new JsonObject()); // the CPU, until the environment check lists the GPUs
		EnvMonitor.addListener(this::updateDevices);
		return p;
	}

	/** The GPUs of the machine (as in the main window), Apple's GPU and the CPU, keeping the one selected. */
	private void updateDevices(JsonObject info) {
		Object prev = device.getSelectedItem();
		device.removeAllItems();
		if (info.has("gpus"))
			for (JsonElement g : info.getAsJsonArray("gpus")) {
				JsonObject o = g.getAsJsonObject();
				String mem = o.has("memory_mb") ? String.format(" (%.0f GB)", o.get("memory_mb").getAsInt() / 1024.0) : "";
				device.addItem("GPU " + o.get("index").getAsInt() + " : " + o.get("name").getAsString() + mem);
			}
		if (info.has("mps_available") && info.get("mps_available").getAsBoolean()) device.addItem("MPS : Apple Silicon");
		device.addItem("CPU");
		if (prev != null) device.setSelectedItem(prev);
		if (device.getSelectedIndex() < 0) device.setSelectedIndex(0);
	}

	/** The device selected, as BiaPy takes it: a GPU number, "mps" or "" (CPU). */
	private String selectedDevice() {
		String d = String.valueOf(device.getSelectedItem());
		return d.startsWith("GPU ") ? d.substring(4, d.indexOf(' ', 4)) : d.startsWith("MPS") ? "mps" : "";
	}

	/** Whether the model {@code m} has been read (its outputs are shown). */
	private boolean isDescribed(String m) {
		return m != null && m.equals(described);
	}

	private JPanel infoBox() {
		JPanel p = new JPanel(new BorderLayout(10, 0));
		p.add(note("What each output is and how to look at it in Fiji."),
				BorderLayout.CENTER);
		outputInfo.addActionListener(e -> {
			List<String> keys = new ArrayList<>();
			for (JCheckBox cb : outputChecks) if (cb.isSelected()) keys.add(cb.getActionCommand());
			UiUtils.helpWindow(this, "Output info: " + WorkflowConfirmDialog.workflowName(loadedInfo.get("workflow").getAsString()),
					outputsHtml(loadedInfo, keys, selectedImage()));
		});
		outputInfo.setEnabled(false);
		p.add(outputInfo, BorderLayout.EAST);
		return p;
	}

	private static JLabel note(String text) {
		return note(text, 1);
	}

	/** Gray text as tall as {@code lines} lines at least. */
	private static JLabel note(String text, int lines) {
		JLabel l = new JLabel(text) {
			private static final long serialVersionUID = 1L;

			@Override
			public Dimension getPreferredSize() {
				Dimension d = super.getPreferredSize();
				return new Dimension(d.width, Math.max(d.height, lines * getFontMetrics(getFont()).getHeight() + 2));
			}
		};
		l.setForeground(BiaPyFiji.GREY);
		l.setFont(l.getFont().deriveFont(Font.PLAIN, 12f));
		l.setVerticalAlignment(SwingConstants.TOP);
		return l;
	}

	/** A step: its title (as the wizard's steps) and its content in a light blue box. */
	private static JPanel step(String title, JPanel inside) {
		JPanel box = new JPanel(new BorderLayout());
		box.setBackground(new Color(245, 249, 255));
		box.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(BiaPyFiji.LIGHT_BLUE, 2, true),
				BorderFactory.createEmptyBorder(8, 10, 8, 10)));
		inside.setOpaque(false);
		box.add(inside, BorderLayout.CENTER);
		JPanel p = new JPanel(new BorderLayout(0, 4));
		p.setOpaque(false);
		p.add(WizardPanel.stepLabel(title), BorderLayout.NORTH);
		p.add(box, BorderLayout.CENTER);
		p.setAlignmentX(Component.LEFT_ALIGNMENT);
		return p;
	}

	private JPanel modelBox() {
		JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		browse = UiUtils.secondaryButton("Browse", "Choose the checkpoint of a model trained with BiaPy (.pth)");
		browse.addActionListener(e -> {
			File f = UiUtils.chooseFile(this, checkpoint.getText(), "BiaPy checkpoint (*.pth)", "pth");
			if (f == null) return;
			checkpoint.setText(f.getAbsolutePath());
			modelChanged();
		});
		checkpoint.addActionListener(e -> modelChanged());
		checkpoint.setToolTipText("A .pth checkpoint of BiaPy (in the 'checkpoints' folder of a training run). The "
				+ ".safetensors ones don't hold the configuration the model needs");
		zooModel.setEditable(false);
		zooModel.setToolTipText("The nickname of the model, chosen with 'Find models'");
		find = UiUtils.secondaryButton("Find models", "Look for pretrained models of the BioImage Model Zoo that BiaPy can use");
		find.addActionListener(e -> ZooModelsDialog.choose(SwingUtilities.getWindowAncestor(this), m -> {
			zooEntry = m;
			zooModel.setText(m.get("id").getAsString());
			modelChanged();
		}));
		keep.setOpaque(false);
		keep.setFont(keep.getFont().deriveFont(Font.PLAIN, 12f));
		showKeep(null);
		JLabel hint = note("A model trained with BiaPy: the .pth file in the 'checkpoints' folder of its training.");
		hint.setBorder(BorderFactory.createEmptyBorder(4, 2, 0, 0));
		source.addTab("Checkpoint file", tab(sourceRow(checkpoint, browse), hint));
		source.addTab("BioImage Model Zoo", tab(sourceRow(zooModel, find), keep));
		source.setToolTipTextAt(CHECKPOINT, "A model trained with BiaPy: its .pth checkpoint");
		source.setToolTipTextAt(ZOO, "A pretrained model of the BioImage Model Zoo, downloaded when it is applied");
		source.addChangeListener(e -> modelChanged());
		p.add(source);
		p.add(Box.createVerticalStrut(6));
		p.add(modelInfo);
		for (Component c : p.getComponents()) ((javax.swing.JComponent) c).setAlignmentX(Component.LEFT_ALIGNMENT);
		return p;
	}

	private static JPanel sourceRow(JTextField field, JButton button) {
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setOpaque(false);
		row.add(field, BorderLayout.CENTER);
		row.add(button, BorderLayout.EAST);
		return row;
	}

	/** A tab of Step 1: the field of the model and a line under it. */
	private static JPanel tab(JPanel row, javax.swing.JComponent under) {
		JPanel t = new JPanel(new BorderLayout());
		t.setBackground(BiaPyFiji.WHITE);
		t.setBorder(BorderFactory.createEmptyBorder(6, 6, 4, 6));
		t.add(row, BorderLayout.NORTH);
		t.add(under, BorderLayout.CENTER);
		return t;
	}

	private boolean zooSelected() {
		return source.getSelectedIndex() == ZOO;
	}

	/** The checkbox to keep the zoo model, with its size when known ({@code download} of describe_model). */
	private void showKeep(JsonObject download) {
		boolean saved = download != null && download.get("saved").getAsBoolean();
		String size = download == null || download.get("size").isJsonNull() ? ""
				: " (" + megabytes(download.get("size").getAsLong()) + ")";
		keep.setText(saved ? "Kept on this computer: it is not downloaded again"
				: "Keep a copy on this computer" + size + ", not to download it again");
		keep.setToolTipText(download == null ? "In the system's cache folder (biapy/bmz_models)"
				: "In " + download.get("folder").getAsString());
		if (saved) keep.setSelected(true);
		keep.setEnabled(!saved && running == null);
	}

	static String megabytes(long bytes) {
		return bytes >= 1e9 ? String.format("%.1f GB", bytes / 1e9) : bytes < 1e6 ? "< 1 MB" : Math.round(bytes / 1e6) + " MB";
	}

	private JPanel imageBox() {
		JPanel p = new JPanel(new BorderLayout(0, 6));
		images.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
			public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) { refreshImages(); }
			public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) {}
			public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) {}
		});
		images.addActionListener(e -> describeImage());
		p.add(images, BorderLayout.NORTH);
		p.add(imageInfo, BorderLayout.CENTER);
		return p;
	}

	private JPanel buttons() {
		JPanel p = new JPanel(new BorderLayout(8, 0));
		p.setOpaque(false);
		busyBar.setIndeterminate(true);
		busyBar.setStringPainted(true);
		cancel.setVisible(false);
		cancel.addActionListener(e -> {
			PythonBridge b = running;
			if (b != null) b.cancel();
		});
		apply.addActionListener(e -> applyModel());
		JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
		right.setOpaque(false);
		right.add(cancel);
		right.add(apply);
		// Next to the buttons: the progress bar with what is being done, or the outcome as text
		status.setFont(status.getFont().deriveFont(Font.PLAIN, 12f));
		JPanel bar = new JPanel(new BorderLayout());
		bar.setOpaque(false);
		bar.add(busyBar, BorderLayout.CENTER);
		statusSlot.setOpaque(false);
		statusSlot.add(status, "text");
		statusSlot.add(bar, "bar");
		p.add(statusSlot, BorderLayout.CENTER);
		p.add(right, BorderLayout.EAST);
		return p;
	}

	///////////////////
	// Step 1: model
	///////////////////
	/** The model selected: a checkpoint path or a zoo nickname, or null. */
	private String selectedModel() {
		String m = (zooSelected() ? zooModel : checkpoint).getText().trim();
		return m.isEmpty() ? null : m;
	}

	private void modelChanged() {
		String m = selectedModel();
		if (isDescribed(m)) return;
		// The outputs of the previous model stay (disabled) until the new one is loaded: the window keeps its size
		if (m == null) showOutputs(null);
		else for (JCheckBox cb : outputChecks) cb.setEnabled(false);
		outputInfo.setEnabled(false);
		modelInfo.setText(m == null ? "No model selected yet." : "Not read yet.");
		showKeep(null);
		describeImage();
		if (m != null && running == null) run(() -> describe(m), null);
	}

	/**
	 * What the model is (in the background), shown in the steps. Nothing is downloaded or loaded: that is done when it
	 * is applied, once the image is known to fit.
	 */
	private JsonObject describe(String m) throws Exception {
		if (isDescribed(m)) return loadedInfo;
		if (!zooSelected()) {
			if (!new File(m).isFile()) throw new PythonBridge.BridgeException("The checkpoint file does not exist: " + m);
			if (!m.endsWith(".pth"))
				throw new PythonBridge.BridgeException("Choose a .pth checkpoint: other files (e.g. .safetensors) don't hold "
						+ "the configuration of the model.");
		}
		busy("Reading the model . . .");
		JsonObject args = new JsonObject();
		args.addProperty("model", m);
		if (zooSelected() && zooEntry != null) args.add("zoo", zooEntry);
		JsonObject info = call("describe_model", args);
		JsonObject read = info;
		described = m;
		loadedInfo = info;
		SwingUtilities.invokeLater(() -> {
			modelInfo.setText("<html>" + WorkflowConfirmDialog.workflowName(read.get("workflow").getAsString()) + ", "
					+ read.get("ndim").getAsString() + ", images with " + plural(read.get("channels").getAsInt(), "channel")
					+ "</html>");
			showOutputs(read);
			showKeep(read.has("download") ? read.getAsJsonObject("download") : null);
			describeImage();
		});
		return info;
	}

	/**
	 * Downloads (zoo) and loads the model on the device selected, unless it is there already. The Python process can't
	 * change its device once it has used one: it is restarted then.
	 */
	private void load(String m) throws Exception {
		String gpu = selectedDevice();
		JsonObject download = loadedInfo.has("download") ? loadedInfo.getAsJsonObject("download") : null;
		boolean fetch = download != null && !download.get("saved").getAsBoolean();
		// Loaded already, unless it is to be kept now
		if (m.equals(loaded) && gpu.equals(loadedDevice) && !(fetch && zooSelected() && keep.isSelected())) return;
		busy((fetch ? "Downloading" : "Loading") + " the model '" + modelName() + "'"
				+ (fetch ? "" : " on " + deviceName(gpu)) + " . . .");
		JsonObject args = new JsonObject();
		args.addProperty("model", m);
		args.addProperty("out_dir", workDir().getAbsolutePath());
		args.addProperty("gpu", gpu);
		args.addProperty("keep", zooSelected() && keep.isSelected());
		downloadStart = 0;
		JsonObject info = call("load_model", args);
		if (info.has("restart")) {
			busy("Restarting BiaPy to use " + deviceName(gpu) + " . . .");
			PythonBridge.restartServer();
			info = call("load_model", args);
		}
		loaded = m;
		loadedDevice = gpu;
		// The workflow's own answer (describe_model's post-processing rule is a copy of it)
		JsonObject built = info;
		if (built.get("post_processing").getAsBoolean() != loadedInfo.get("post_processing").getAsBoolean()) {
			loadedInfo = built;
			SwingUtilities.invokeLater(() -> showOutputs(built));
		}
	}

	private static String deviceName(String gpu) {
		return gpu.isEmpty() ? "the CPU" : gpu.equals("mps") ? "Apple's GPU" : "GPU " + gpu;
	}

	///////////////////
	// Step 2: image
	///////////////////
	private static final class ImageChoice {
		final int id;
		final String title;

		ImageChoice(int id, String title) {
			this.id = id;
			this.title = title;
		}

		@Override
		public String toString() {
			return title;
		}
	}

	/** The images open in Fiji, keeping the one selected (the current image at first). */
	void refreshImages() {
		ImageChoice was = (ImageChoice) images.getSelectedItem();
		int[] ids = WindowManager.getIDList();
		List<ImageChoice> now = new ArrayList<>();
		if (ids != null) for (int id : ids) {
			ImagePlus imp = WindowManager.getImage(id);
			if (imp != null) now.add(new ImageChoice(id, imp.getTitle()));
		}
		List<String> before = new ArrayList<>(), after = new ArrayList<>();
		for (int i = 0; i < images.getItemCount(); i++) before.add(images.getItemAt(i).id + images.getItemAt(i).title);
		for (ImageChoice c : now) after.add(c.id + c.title);
		if (before.equals(after)) return;
		images.removeAllItems();
		for (ImageChoice c : now) images.addItem(c);
		ImagePlus current = WindowManager.getCurrentImage();
		int keep = was != null ? was.id : current != null ? current.getID() : 0;
		for (ImageChoice c : now) if (c.id == keep) images.setSelectedItem(c);
		describeImage();
	}

	private ImagePlus selectedImage() {
		ImageChoice c = (ImageChoice) images.getSelectedItem();
		return c == null ? null : WindowManager.getImage(c.id);
	}

	/** The image, and whether and how the model takes it. */
	private void describeImage() {
		ImagePlus imp = selectedImage();
		String first, second, problem = null;
		if (imp == null) {
			first = images.getItemCount() == 0 ? "No image is open in Fiji: open one first." : "No image selected yet.";
			second = images.getItemCount() == 0 ? "The images you open appear in this list." : "Select the image to apply the model to.";
		} else {
			first = imp.getWidth() + " x " + imp.getHeight() + " pixels";
			if (imp.getNSlices() > 1) first += ", " + plural(imp.getNSlices(), "slice");
			if (imp.getNFrames() > 1) first += ", " + plural(imp.getNFrames(), "frame");
			first += ", " + plural(channels(imp), "channel") + ", "
					+ (imp.getType() == ImagePlus.COLOR_RGB ? "RGB" : imp.getBitDepth() + "-bit");
			if (loadedInfo == null || !selectedMatchesLoaded()) {
				second = "Select a model to check that it can take this image.";
			} else {
				problem = problem(imp, loadedInfo);
				String plan = plan(imp, loadedInfo);
				second = problem != null ? problem : plan != null ? plan : "The model will be applied to the whole image.";
			}
		}
		// One line (cut with "..." if too long: the whole text is in its tooltip), red if the model can't take the image
		String text = first + (first.endsWith(".") ? " " : ". ") + second;
		imageInfo.setText(text);
		imageInfo.setToolTipText(text);
		imageInfo.setForeground(problem != null ? BiaPyFiji.RED : BiaPyFiji.GREY);
	}

	private static String esc(String s) {
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	private boolean selectedMatchesLoaded() {
		return isDescribed(selectedModel());
	}

	private static int channels(ImagePlus imp) {
		return imp.getType() == ImagePlus.COLOR_RGB ? 3 : imp.getNChannels();
	}

	/** Why the model can't be applied to the image, or null. */
	static String problem(ImagePlus imp, JsonObject info) {
		return problem(imp, info, null);
	}

	/** The same, explained in full with the name of the model (Apply's message), or in short (Step 2's line). */
	static String problem(ImagePlus imp, JsonObject info, String model) {
		String m = "The model selected (" + model + ")", image = "'" + imp.getTitle() + "'";
		if (info.get("tracking").getAsBoolean())
			return model == null ? "This model tracks over time: use 'Apply entire workflow' (Plugins > BiaPy)."
					: m + " tracks objects over time, which 'Apply model' can't do. Please use it with 'Apply entire "
							+ "workflow' (Plugins > BiaPy) instead.";
		int c = info.get("channels").getAsInt();
		if (channels(imp) != c)
			return model == null ? "The model takes " + c + "-channel images, not " + channels(imp) + "."
					: m + " takes " + c + "-channel images, but " + image + " has " + plural(channels(imp), "channel")
							+ ". Please choose another model, or an image with " + plural(c, "channel") + ".";
		if ("3D".equals(info.get("ndim").getAsString()) && imp.getNSlices() == 1)
			return model == null ? "The model is 3D: this image has a single slice."
					: m + " is 3D, but " + image + " has a single slice. Please choose a 2D model, or a 3D image.";
		return null;
	}

	/** The model selected as the user knows it: its zoo nickname or the name of its checkpoint file. */
	private String modelName() {
		String m = selectedModel();
		return m == null ? "" : zooSelected() ? m : new File(m).getName();
	}

	/** What the model will be applied to, if not simply the whole image. */
	private static String plan(ImagePlus imp, JsonObject info) {
		List<String> notes = new ArrayList<>();
		if ("2D".equals(info.get("ndim").getAsString()) && imp.getNSlices() > 1)
			notes.add("The model is 2D: applied to each slice.");
		if (imp.getNFrames() > 1) notes.add("Only frame " + imp.getFrame() + " is used.");
		return notes.isEmpty() ? null : String.join(" ", notes);
	}

	///////////////////
	// Step 3: outputs
	///////////////////
	private void showOutputs(JsonObject info) {
		outputsBox.removeAll();
		outputChecks.clear();
		// Two columns: at most two rows (the details of each are in Step 5)
		outputsBox.setLayout(new java.awt.GridLayout(0, info == null ? 1 : 2, 10, 2));
		if (info == null) {
			outputsBox.add(note("No model selected yet: the outputs depend on its workflow."));
		} else {
			for (String[] o : outputs(info.get("workflow").getAsString(), info.get("post_processing").getAsBoolean())) {
				JCheckBox cb = new JCheckBox(o[1], !o[2].isEmpty());
				cb.setOpaque(false);
				cb.setActionCommand(o[0]);
				outputChecks.add(cb);
				outputsBox.add(cb);
			}
		}
		outputInfo.setEnabled(info != null);
		outputsBox.revalidate();
		outputsBox.repaint();
		// The window fits the outputs of the model (from one to three rows), keeping its width
		java.awt.Window w = SwingUtilities.getWindowAncestor(this);
		if (w != null && w.isShowing()) {
			int width = w.getWidth();
			w.pack();
			w.setSize(width, w.getHeight());
		}
	}

	///////////////////
	// Step 4: output info
	///////////////////
	/**
	 * What each output wanted ({@code keys}) is and how to look at it, and how the model is applied to {@code imp} (may
	 * be null), for the model described by {@code info} (load_model).
	 */
	static String outputsHtml(JsonObject info, List<String> keys, ImagePlus imp) {
		String wf = info.get("workflow").getAsString();
		String title = imp == null ? "&lt;image&gt;" : esc(imp.getTitle().replaceFirst("\\.[A-Za-z0-9]+$", ""));
		String glasbey = " It is shown with the <i>glasbey on dark</i> lookup table (Image &gt; Lookup Tables): each ";
		StringBuilder sb = new StringBuilder("<h2>What the model does</h2><p>")
				.append(WorkflowInfoDialog.description(wf)).append("</p><h2>What opens in Fiji</h2>");
		if (keys.isEmpty()) sb.append("<p>No output is selected in Step 3.</p>");
		sb.append("<ul>");
		for (String key : keys) {
			String window = "<b>BiaPy " + Results.name(wf, key) + " - " + title + "</b>";
			switch (wf + " " + key) {
			case "SEMANTIC_SEG raw":
				sb.append("<li>").append(window).append(": for each pixel, how likely it is to be of each class, from 0 to 1 ")
						.append(info.get("classes").getAsInt() <= 2 ? "(with two classes, a single channel: the foreground)"
								: "(one channel per class: move through them with the C slider)")
						.append(". To draw your own mask, threshold it (Image &gt; Adjust &gt; Threshold).</li>");
				break;
			case "SEMANTIC_SEG final":
			case "SEMANTIC_SEG post":
				sb.append("<li>").append(window).append(": each pixel with its most likely class (0 = background, 1, 2...")
						.append(info.get("classes").getAsInt() <= 2 ? "; with two classes the threshold is chosen automatically" : "")
						.append(")").append("post".equals(key) ? ", after the model's post-processing" : "").append(".")
						.append(glasbey).append("class has its own color and the background is black. To keep one class, ")
						.append("threshold its value.</li>");
				break;
			case "INSTANCE_SEG raw":
				sb.append("<li>").append(window).append(": the helper images the model predicts, from which the objects are ")
						.append("built, one per channel (C slider):")
						.append(WorkflowInfoDialog.channelList(info.getAsJsonArray("instance_outputs"))).append("</li>");
				break;
			case "INSTANCE_SEG final":
			case "INSTANCE_SEG post":
				sb.append("<li>").append(window).append(": the objects found, each with its own number (0 = background)")
						.append("post".equals(key) ? ", after the model's post-processing (e.g. removing small objects)" : "")
						.append(".").append(glasbey).append("object has its own color and the background is black. To ")
						.append("measure them, e.g. Analyze &gt; Analyze Particles on the pixels above 0 (touching objects ")
						.append("then merge) or a label analysis plugin such as MorphoLibJ's.</li>");
				break;
			case "DETECTION raw":
				sb.append("<li>").append(window).append(": how likely each pixel is to be the center of an object (0 to ")
						.append("1).</li>");
				break;
			case "DETECTION final":
				sb.append("<li><b>The points</b>, as a point selection on <i>").append(title).append("</i> (add it to the ")
						.append("ROI Manager with T to keep it) and in the table <b>BiaPy points - ").append(title)
						.append("</b> (X, Y").append("3D".equals(info.get("ndim").getAsString()) ? ", Z" : "")
						.append(" in pixels and the probability of each). They are the peaks of the raw output above ")
						.append(info.has("min_th") ? info.get("min_th").getAsString() : "a threshold")
						.append(" and at least ")
						.append(info.has("min_distance") ? info.get("min_distance").getAsString() + " pixels" : "a distance")
						.append(" apart").append(info.get("post_processing").getAsBoolean() ? ", after the model's post-processing" : "")
						.append(".</li>");
				break;
			case "CLASSIFICATION final":
				sb.append("<li>The table <b>BiaPy class - ").append(title).append("</b>: the number of the class predicted, ")
						.append("in the order of the classes the model was trained with (with BiaPy, the alphabetical order ")
						.append("of the folders of the training images).</li>");
				break;
			default:
				sb.append("<li>").append(window).append(": ").append("SUPER_RESOLUTION".equals(wf)
						? "the image at a higher resolution: more pixels, each smaller (its calibration is adjusted)."
						: "DENOISING".equals(wf) ? "the image without its noise, to compare with the original."
								: "the image the model predicts from yours.")
						.append(" Compare it with the original, e.g. side by side with Image &gt; Stacks &gt; Tools &gt; ")
						.append("Combine.</li>");
			}
		}
		sb.append("</ul><h2>How the model is applied</h2><ul>");
		sb.append("<li>Your image is normalized as the model expects, so you don't need to prepare it.</li>");
		if (imp != null && "2D".equals(info.get("ndim").getAsString()) && imp.getNSlices() > 1)
			sb.append("<li>The model is 2D: each slice is predicted on its own and the results are stacked. The objects of ")
					.append("one slice are not linked to those of the next: for 3D objects use a 3D model.</li>");
		if (imp != null && imp.getNFrames() > 1)
			sb.append("<li>Only the current frame is predicted: move to another frame and apply the model again.</li>");
		sb.append("<li>The results keep the calibration of your image (its pixel size).</li>")
				.append("<li>Nothing is saved: save the results you want to keep (File &gt; Save As).</li></ul>")
				.append("<h2>Need help?</h2><p>Post your questions in the <a href='").append(BiaPyFiji.URL_FORUM)
				.append("'>image.sc forum</a> (tag <i>biapy</i>).</p>");
		return sb.toString();
	}

	///////////////////
	// Apply
	///////////////////
	private void applyModel() {
		String m = selectedModel();
		if (m == null) {
			UiUtils.info(this, zooSelected() ? "Choose a model of the BioImage Model Zoo with 'Find models' first."
					: "Choose the checkpoint file (.pth) of the model first.");
			return;
		}
		ImagePlus imp = selectedImage();
		if (imp == null) {
			UiUtils.info(this, "Open the image to apply the model to in Fiji first, and select it in Step 2.");
			return;
		}
		List<String> keys = new ArrayList<>();
		for (JCheckBox cb : outputChecks) if (cb.isSelected()) keys.add(cb.getActionCommand());
		if (isDescribed(m) && keys.isEmpty()) {
			UiUtils.info(this, "Select at least one output to open in Step 3.");
			return;
		}
		// Nothing is downloaded or loaded for an image the model can't take
		String problem = isDescribed(m) ? problem(imp, loadedInfo, modelName()) : null;
		if (problem != null) {
			UiUtils.error(this, problem);
			return;
		}
		// The disk space a copy of a zoo model takes, before downloading it
		JsonObject download = isDescribed(m) && loadedInfo.has("download") ? loadedInfo.getAsJsonObject("download") : null;
		if (zooSelected() && keep.isSelected() && download != null && !download.get("saved").getAsBoolean()
				&& !UiUtils.confirmHtml(this, "Keep the model", "<html>The model <b>" + esc(m) + "</b> will be downloaded and "
						+ "kept in<br>" + esc(download.get("folder").getAsString()) + "<br>to use it again without "
						+ "downloading it." + (download.get("size").isJsonNull() ? ""
								: "<br><br>It takes <b>" + megabytes(download.get("size").getAsLong()) + "</b> of disk space.")
						+ "</html>", "Download and keep", "Cancel"))
			return;
		images.setEnabled(false);
		run(() -> {
			JsonObject info = describe(m);
			String late = problem(imp, info, modelName());
			if (late != null) throw new PythonBridge.BridgeException(late);
			load(m);
			// The outputs were just shown: the defaults are taken, as the checkboxes are
			List<String> wanted = new ArrayList<>(keys);
			if (wanted.isEmpty())
				for (String[] o : outputs(info.get("workflow").getAsString(), info.get("post_processing").getAsBoolean()))
					if (!o[2].isEmpty()) wanted.add(o[0]);
			Results results = predict(imp, info, wanted);
			SwingUtilities.invokeLater(() -> results.show(imp));
			return info;
		}, "Done: the results are open in Fiji.");
	}

	/** Applies the model to the image (each slice of it for a 2D model), collecting the outputs wanted. */
	private Results predict(ImagePlus imp, JsonObject info, List<String> keys) throws Exception {
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
		for (int i = 0; i < slices.length; i++) {
			String which = slices.length > 1 ? " (slice " + (i + 1) + " of " + slices.length + ")" : "";
			busy("Sending '" + imp.getTitle() + "'" + which + " to BiaPy . . .");
			writeNpy(npy, imp, slices[i], imp.getFrame());
			busy("BiaPy is predicting '" + imp.getTitle() + "'" + which + " . . .");
			JsonObject args = new JsonObject();
			args.addProperty("model", loaded);
			args.addProperty("gpu", loadedDevice);
			args.addProperty("out_dir", workDir().getAbsolutePath());
			args.addProperty("image", npy.getAbsolutePath());
			File dir = new File(call("apply_model", args).get("results_dir").getAsString());
			results.collect(dir, slices[i]);
		}
		npy.delete();
		busy("Opening the results in Fiji . . .");
		return results;
	}

	/** Runs {@code task} in the background, with the window busy; {@code done} is shown when it ends well. */
	private void run(Task task, String done) {
		if (running != null) return;
		if (!PythonEnv.pythonExists()) {
			images.setEnabled(true);
			UiUtils.info(this, "BiaPy is not installed: install it in the 'Installation' tab.");
			return;
		}
		running = new PythonBridge();
		setControls(false);
		new Thread(() -> {
			String error = null;
			try {
				task.run();
			} catch (InterruptedException e) {
				error = "";
			} catch (Exception e) {
				error = e.getMessage();
			}
			String fe = error;
			SwingUtilities.invokeLater(() -> {
				running = null;
				setControls(true);
				showStatus(fe == null ? (done == null ? " " : done) : fe.isEmpty() ? "Canceled." : " ", false);
				if (fe == null) return;
				if (fe.isEmpty()) {
					forget();
					return;
				}
				if (!isDescribed(selectedModel())) {
					modelInfo.setText("Not read: see the message.");
					showOutputs(null);
				}
				UiUtils.error(this, fe);
			});
		}, "BiaPy-apply-model").start();
	}

	private interface Task {
		Object run() throws Exception;
	}

	/** Canceling stops the server, and the model loaded with it (what it is stays known, if it was read). */
	private void forget() {
		loaded = null;
		loadedDevice = null;
		if (!isDescribed(selectedModel())) {
			showOutputs(null);
			modelInfo.setText(selectedModel() == null ? "No model selected yet." : "Not read yet.");
		}
		describeImage();
	}

	/** While the model loads or is applied, it can't be changed: Step 1 and Apply are disabled. */
	private void setControls(boolean enabled) {
		for (java.awt.Component c : new java.awt.Component[] { apply, checkpoint, browse, find, device })
			c.setEnabled(enabled);
		source.setEnabledAt(zooSelected() ? CHECKPOINT : ZOO, enabled);
		JsonObject d = loadedInfo != null && loadedInfo.has("download") && isDescribed(selectedModel())
				? loadedInfo.getAsJsonObject("download") : null;
		keep.setEnabled(enabled && !(d != null && d.get("saved").getAsBoolean()));
		// The image is locked while the model is applied to it (applyModel), not while a model loads
		if (enabled) images.setEnabled(true);
		outputInfo.setEnabled(enabled && loadedInfo != null && selectedMatchesLoaded());
		cancel.setVisible(!enabled);
	}

	/** A message in the progress bar ({@code busy}) or as text. */
	private void showStatus(String message, boolean busy) {
		status.setText(message);
		busyBar.setString(message);
		busyBar.setToolTipText(message);
		((java.awt.CardLayout) statusSlot.getLayout()).show(statusSlot, busy ? "bar" : "text");
	}

	private void busy(String message) {
		SwingUtilities.invokeLater(() -> {
			busyBar.setIndeterminate(true);
			showStatus(message, true);
		});
	}

	/** When the download of the model started (ms), 0 before its first bytes. */
	private volatile long downloadStart;

	/** The download of the zoo model: how much, of how much and about how long it still takes. */
	private void showDownload(long done, long total) {
		long now = System.currentTimeMillis();
		if (downloadStart == 0) downloadStart = now;
		long elapsed = now - downloadStart;
		String left = "";
		if (done > 0 && total > done && elapsed > 2000) {
			long seconds = (total - done) * elapsed / done / 1000;
			left = seconds < 60 ? ", about " + Math.max(5, (seconds + 4) / 5 * 5) + " s left"
					: ", about " + (seconds + 30) / 60 + " min left";
		}
		int percent = total > 0 ? (int) Math.min(100, done * 100 / total) : 0;
		busyBar.setIndeterminate(total <= 0);
		busyBar.setValue(percent);
		showStatus("Downloading '" + modelName() + "': " + (total > 0 ? percent + "% of " + megabytes(total) : megabytes(done))
				+ left, true);
	}

	private JsonObject call(String command, JsonObject args) throws Exception {
		String model = args.get("model").getAsString(), name = new File(model).getName();
		return running.call(command, args, line -> {
			if (line.equals("DOWNLOAD_KEPT") && loadedInfo != null && loadedInfo.has("download")) {
				JsonObject download = loadedInfo.getAsJsonObject("download");
				download.addProperty("saved", true);
				SwingUtilities.invokeLater(() -> showKeep(download));
			} else if (line.startsWith("DOWNLOAD_PROGRESS:")) {
				String[] v = line.substring("DOWNLOAD_PROGRESS:".length()).trim().split(" ");
				SwingUtilities.invokeLater(() -> showDownload(Long.parseLong(v[0]), Long.parseLong(v[1])));
			} else if (line.startsWith("Loading the model"))
				busy(line.replace(model, "'" + name + "'"));
		});
	}

	private static synchronized File workDir() throws IOException {
		if (workDir == null) workDir = Files.createTempDirectory("biapy-apply-model").toFile();
		return workDir;
	}

	static String plural(int n, String what) {
		return n + " " + what + (n == 1 ? "" : "s");
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
		int w = imp.getWidth(), h = imp.getHeight(), c = channels(imp);
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

		void collect(File dir, int slice) throws IOException {
			if ("CLASSIFICATION".equals(workflow)) {
				List<String> lines = Files.readAllLines(new File(dir, "predictions.csv").toPath(), StandardCharsets.UTF_8);
				classes.add(new String[] { String.valueOf(slice), lines.get(1).substring(lines.get(1).lastIndexOf(',') + 1) });
				return;
			}
			for (String key : keys) {
				File folder = null;
				for (String name : FOLDERS.get(key))
					if (new File(dir, name).isDirectory()) {
						folder = new File(dir, name);
						break;
					}
				if (folder == null) {
					if (!missing.contains(key)) missing.add(key);
					continue;
				}
				if ("DETECTION".equals(workflow) && "final".equals(key)) {
					readPoints(new File(folder, "in_memory_image_points.csv"), slice);
					continue;
				}
				ImagePlus out = IJ.openImage(new File(folder, "in_memory_image.tif").getAbsolutePath());
				if (out == null) throw new IOException("Could not open " + new File(folder, "in_memory_image.tif"));
				images.computeIfAbsent(key, k -> new ArrayList<>()).add(out);
			}
		}

		/** The CSV of points of BiaPy: columns axis-0, axis-1 (and axis-2 in 3D), from Z to X. */
		private void readPoints(File csv, int slice) throws IOException {
			if (!csv.isFile()) return; // no points
			List<String> lines = Files.readAllLines(csv.toPath(), StandardCharsets.UTF_8);
			List<String> header = Arrays.asList(lines.get(0).split(","));
			int a0 = header.indexOf("axis-0"), a1 = header.indexOf("axis-1"), a2 = header.indexOf("axis-2"),
					prob = header.indexOf("probability");
			for (String line : lines.subList(1, lines.size())) {
				if (line.trim().isEmpty()) continue;
				String[] v = line.split(",");
				double z = a2 >= 0 ? Double.parseDouble(v[a0]) : 0;
				double y = Double.parseDouble(v[a2 >= 0 ? a1 : a0]), x = Double.parseDouble(v[a2 >= 0 ? a2 : a1]);
				points.add(new double[] { slice, z, y, x, prob >= 0 ? Double.parseDouble(v[prob]) : Double.NaN });
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
