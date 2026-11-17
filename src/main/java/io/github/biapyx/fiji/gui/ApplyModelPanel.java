package io.github.biapyx.fiji.gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

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

import ij.ImagePlus;
import ij.WindowManager;
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
		busy("Reading the model . . .");
		JsonObject info = ModelApplier.describe(running, m, zooSelected() ? zooEntry : null, log(m));
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
				+ (fetch ? "" : " on " + ModelApplier.deviceName(gpu)) + " . . .");
		downloadStart = 0;
		JsonObject info = ModelApplier.load(running, m, gpu, zooSelected() && keep.isSelected(), log(m));
		loaded = m;
		loadedDevice = gpu;
		// The workflow's own answer (describe_model's post-processing rule is a copy of it)
		JsonObject built = info;
		if (built.get("post_processing").getAsBoolean() != loadedInfo.get("post_processing").getAsBoolean()) {
			loadedInfo = built;
			SwingUtilities.invokeLater(() -> showOutputs(built));
		}
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

	static int channels(ImagePlus imp) {
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
			return model == null ? "This model tracks over time: use 'Run BiaPy' (Plugins > BiaPy)."
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
			String window = "<b>BiaPy " + ModelApplier.Results.name(wf, key) + " - " + title + "</b>";
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
			List<String> wanted = keys.isEmpty() ? ModelApplier.defaultOutputs(info) : keys;
			ModelApplier.Results results = ModelApplier.apply(running, imp, info, loaded, loadedDevice, wanted, this::busy);
			SwingUtilities.invokeLater(() -> {
				results.show(imp);
				ModelApplier.record(imp, m, wanted, loadedDevice);
			});
			return info;
		}, "Done: the results are open in Fiji.");
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

	/** What the bridge prints while it reads, downloads or loads the model {@code model}, shown in the window. */
	private Consumer<String> log(String model) {
		String name = new File(model).getName();
		return line -> {
			if (line.equals("DOWNLOAD_KEPT") && loadedInfo != null && loadedInfo.has("download")) {
				JsonObject download = loadedInfo.getAsJsonObject("download");
				download.addProperty("saved", true);
				SwingUtilities.invokeLater(() -> showKeep(download));
			} else if (line.startsWith("DOWNLOAD_PROGRESS:")) {
				String[] v = line.substring("DOWNLOAD_PROGRESS:".length()).trim().split(" ");
				SwingUtilities.invokeLater(() -> showDownload(Long.parseLong(v[0]), Long.parseLong(v[1])));
			} else if (line.startsWith("Loading the model") || line.startsWith("Restarting BiaPy")) {
				busy(line.replace(model, "'" + name + "'"));
			}
		};
	}

	static String plural(int n, String what) {
		return n + " " + what + (n == 1 ? "" : "s");
	}
}
