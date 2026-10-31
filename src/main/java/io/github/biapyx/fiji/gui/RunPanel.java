package io.github.biapyx.fiji.gui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.File;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import ij.Prefs;
import io.bioimage.modelrunner.system.PlatformDetection;
import io.github.biapyx.fiji.BiaPyFiji;
import io.github.biapyx.fiji.env.PythonEnv;

/**
 * RUN WORKFLOW tab: select a configuration file, where to store the results and the device. Each run then
 * gets its own tab under "BiaPy runs" in the sidebar ({@link JobPanel}), so several can run at once.
 */
public class RunPanel extends JPanel {

	private static final long serialVersionUID = 1L;
	private static final String PREF_OUT_DIR = "biapy.run.output_dir";
	private static final String PREF_YAML = "biapy.run.yaml";

	private final MainPanel main;
	private final JTextField configField = new JTextField();
	private final JTextField outputField = new JTextField();
	private final JTextField jobName = new JTextField();
	private final JLabel finalDir = new JLabel(" ");
	/** "GPU", "MPS : Apple Silicon" or "CPU"; with GPU, the GPUs to use are checked in {@link #gpuBoxes}. */
	private final JComboBox<String> device = new JComboBox<>();
	private final JPanel gpuBoxes = new JPanel();
	private final List<javax.swing.JCheckBox> gpuChecks = new java.util.ArrayList<>();
	private final JLabel multiGpuNote = new JLabel("Training is distributed among the selected GPUs.");
	private final JButton runBtn = UiUtils.primaryButton("Run BiaPy",
			"Start the job with this configuration; it opens in its own tab");

	public RunPanel(MainPanel main) {
		super(new BorderLayout());
		this.main = main;
		setBackground(BiaPyFiji.WHITE);
		setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));

		JPanel top = new JPanel(new BorderLayout(0, 14));
		top.setOpaque(false);
		top.add(UiUtils.title("Run workflow", 22f), BorderLayout.NORTH);
		top.add(buildForm(), BorderLayout.CENTER);
		WrappingTextArea hint = new WrappingTextArea("Each run opens in its own tab under \"BiaPy runs\" in the left bar, so you "
				+ "can launch several and follow them there.");
		hint.setForeground(BiaPyFiji.GREY);
		JPanel bottom = new JPanel(new BorderLayout(0, 10));
		bottom.setOpaque(false);
		JPanel btnRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.CENTER));
		btnRow.setOpaque(false);
		btnRow.add(runBtn);
		bottom.add(btnRow, BorderLayout.NORTH);
		bottom.add(hint, BorderLayout.CENTER);
		top.add(bottom, BorderLayout.SOUTH);
		add(top, BorderLayout.NORTH);

		runBtn.addActionListener(e -> run());

		// The output folder starts empty and is cleared after each run, so that a new job is not written by mistake where
		// the previous one was (e.g. with another configuration but the same job name). Browse opens in the last one.
		outputField.setToolTipText("Folder where the job's results folder is created. It is cleared after each run, "
				+ "so that each job's folder is chosen on purpose");
		String lastYaml = Prefs.get(PREF_YAML, "");
		if (!lastYaml.isEmpty() && new File(lastYaml).isFile()) setConfigFile(new File(lastYaml), false);
		updateFinalDir();
		main.addEnvListener(this::updateDevices);
	}

	private JPanel buildForm() {
		JPanel form = new JPanel(new GridBagLayout());
		form.setOpaque(false);
		GridBagConstraints c = new GridBagConstraints();
		c.insets = new Insets(4, 4, 4, 4);
		c.anchor = GridBagConstraints.WEST;

		addRow(form, c, 0, "Configuration file:", configField, browseButton("Choose the configuration file (.yaml) to run", () -> {
			File f = UiUtils.chooseFile(this, configField.getText(), "BiaPy configuration (*.yaml, *.yml)", "yaml", "yml");
			if (f != null) setConfigFile(f, false);
		}));
		addRow(form, c, 1, "Output folder:", outputField, browseButton("Choose the folder where the job's folder is created", () -> {
			String current = outputField.getText().trim();
			File f = UiUtils.chooseDirectory(this, current.isEmpty() ? Prefs.get(PREF_OUT_DIR, "") : current);
			if (f != null) outputField.setText(f.getAbsolutePath());
			updateFinalDir();
		}));
		addRow(form, c, 2, "Job name:", jobName, null);
		addRow(form, c, 3, "Device:", device, null);
		gpuBoxes.setOpaque(false);
		multiGpuNote.setForeground(BiaPyFiji.GREY);
		gpuBoxes.setLayout(new javax.swing.BoxLayout(gpuBoxes, javax.swing.BoxLayout.Y_AXIS));
		c.gridx = 1;
		c.gridy = 4;
		c.gridwidth = 2;
		form.add(gpuBoxes, c);
		device.addActionListener(e -> updateGpuBoxesVisibility());
		device.setToolTipText("Where BiaPy runs: a GPU is much faster than the CPU");
		c.gridx = 1;
		c.gridy = 5;
		c.gridwidth = 2;
		finalDir.setForeground(BiaPyFiji.GREY);
		form.add(finalDir, c);

		javax.swing.event.DocumentListener dl = new javax.swing.event.DocumentListener() {
			@Override
			public void insertUpdate(javax.swing.event.DocumentEvent e) {
				updateFinalDir();
			}

			@Override
			public void removeUpdate(javax.swing.event.DocumentEvent e) {
				updateFinalDir();
			}

			@Override
			public void changedUpdate(javax.swing.event.DocumentEvent e) {
				updateFinalDir();
			}
		};
		outputField.getDocument().addDocumentListener(dl);
		jobName.getDocument().addDocumentListener(dl);
		return form;
	}

	private static void addRow(JPanel form, GridBagConstraints c, int row, String label, Component field, JButton btn) {
		c.gridy = row;
		c.gridx = 0;
		c.gridwidth = 1;
		c.weightx = 0;
		c.fill = GridBagConstraints.NONE;
		form.add(new JLabel(label), c);
		c.gridx = 1;
		c.weightx = 1;
		c.fill = GridBagConstraints.HORIZONTAL;
		form.add(field, c);
		if (btn != null) {
			c.gridx = 2;
			c.weightx = 0;
			c.fill = GridBagConstraints.NONE;
			form.add(btn, c);
		}
	}

	private JButton browseButton(String tooltip, Runnable r) {
		JButton b = UiUtils.secondaryButton("Browse", tooltip);
		b.addActionListener(e -> r.run());
		return b;
	}

	/**
	 * Selects the configuration to run (called too by the Wizard once it creates one).
	 *
	 * @param newJob whether it is a new job (the Wizard's): its name replaces the job name typed. Otherwise the
	 *            name of the file is only used if there is no job name yet (or the Wizard's "my_..." default).
	 */
	public void setConfigFile(File yaml, boolean newJob) {
		configField.setText(yaml.getAbsolutePath());
		Prefs.set(PREF_YAML, yaml.getAbsolutePath());
		String current = jobName.getText().trim();
		if (newJob || current.isEmpty() || current.startsWith("my_")) jobName.setText(jobNameFor(yaml));
	}

	/**
	 * Job name for a configuration file: its name without ".yaml" and with "_" for the characters a job name can't
	 * have. With the extension, the job's folder would have the name of the file, and in the same folder BiaPy could
	 * not create it (it happened with the Wizard's file, saved in the output folder).
	 */
	static String jobNameFor(File yaml) {
		String name = yaml.getName().replaceAll("(?i)\\.ya?ml$", "").replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
		return name.isEmpty() ? "my_job" : name;
	}

	/** Fills the form to run a configuration again (from the History page); the user still presses Run. */
	public void prepareRun(File yaml, File outputDir, String name) {
		configField.setText(yaml.getAbsolutePath());
		Prefs.set(PREF_YAML, yaml.getAbsolutePath());
		outputField.setText(outputDir.getAbsolutePath());
		jobName.setText(name);
		updateFinalDir();
	}

	private File jobFolder() {
		String out = outputField.getText().trim();
		String name = jobName.getText().trim();
		return out.isEmpty() || name.isEmpty() ? null : new File(out, name);
	}

	private void updateFinalDir() {
		File f = jobFolder();
		finalDir.setText(f == null ? " " : "Job results folder: " + f.getAbsolutePath());
		finalDir.setToolTipText(f == null ? null : f.getAbsolutePath());
	}

	private void updateDevices(JsonObject info) {
		Object prev = device.getSelectedItem();
		java.util.Set<Integer> prevGpus = new java.util.HashSet<>(selectedGpus());
		device.removeAllItems();
		gpuBoxes.removeAll();
		gpuChecks.clear();
		if ("ok".equals(info.get("_state").getAsString())) {
			if (info.has("gpus") && info.getAsJsonArray("gpus").size() > 0) {
				device.addItem("GPU");
				for (JsonElement g : info.getAsJsonArray("gpus")) {
					JsonObject o = g.getAsJsonObject();
					int index = o.get("index").getAsInt();
					String mem = o.has("memory_mb") ? String.format(" (%.0f GB)", o.get("memory_mb").getAsInt() / 1024.0) : "";
					javax.swing.JCheckBox cb = new javax.swing.JCheckBox("GPU " + index + " : " + o.get("name").getAsString() + mem);
					cb.setOpaque(false);
					cb.setToolTipText("Use this GPU (with several, training is distributed among them)");
					cb.putClientProperty("gpu", index);
					cb.addActionListener(e -> updateGpuBoxesVisibility());
					gpuChecks.add(cb);
					gpuBoxes.add(cb);
				}
				// Keep the user's GPUs across refreshes; otherwise the first one
				boolean any = false;
				for (javax.swing.JCheckBox cb : gpuChecks) {
					cb.setSelected(prevGpus.contains(cb.getClientProperty("gpu")));
					any |= cb.isSelected();
				}
				if (!any) gpuChecks.get(0).setSelected(true);
				gpuBoxes.add(multiGpuNote);
			}
			if (info.has("mps_available") && info.get("mps_available").getAsBoolean()) device.addItem("MPS : Apple Silicon");
		}
		device.addItem("CPU");
		// Keep the user's device type across refreshes; otherwise the first one (GPU when there are GPUs)
		if (prev != null && !"CPU".equals(prev)) device.setSelectedItem(prev);
		if (device.getSelectedIndex() < 0 || prev == null || "CPU".equals(prev)) device.setSelectedIndex(0);
		updateGpuBoxesVisibility();
		revalidate();
		repaint();
	}

	private void updateGpuBoxesVisibility() {
		gpuBoxes.setVisible("GPU".equals(device.getSelectedItem()));
		multiGpuNote.setVisible(selectedGpus().size() > 1);
	}

	/** Indices (as nvidia-smi / BiaPy's --gpu) of the GPUs checked. */
	private List<Integer> selectedGpus() {
		List<Integer> out = new java.util.ArrayList<>();
		for (javax.swing.JCheckBox cb : gpuChecks) if (cb.isSelected()) out.add((Integer) cb.getClientProperty("gpu"));
		return out;
	}

	/** Selects the device to run on (also used by the test harnesses): "GPU" with the given GPUs, "MPS" or "CPU". */
	public void selectDevice(String type, int... gpus) {
		for (int i = 0; i < device.getItemCount(); i++)
			if (device.getItemAt(i).startsWith(type)) device.setSelectedIndex(i);
		java.util.Set<Integer> wanted = new java.util.HashSet<>();
		for (int g : gpus) wanted.add(g);
		for (javax.swing.JCheckBox cb : gpuChecks) cb.setSelected(wanted.contains(cb.getClientProperty("gpu")));
		updateGpuBoxesVisibility();
	}

	/**
	 * Text of the device of a run, e.g. "GPU 0 : NVIDIA GeForce RTX 2080 Ti", or "GPUs 0, 1 (distributed)" followed
	 * by a line per GPU.
	 */
	private String deviceDescription(List<Integer> gpus) {
		Object type = device.getSelectedItem();
		if (!"GPU".equals(type)) return type == null ? "CPU" : type.toString();
		if (gpus.size() == 1)
			for (javax.swing.JCheckBox cb : gpuChecks)
				if (cb.getClientProperty("gpu").equals(gpus.get(0))) return cb.getText();
		StringBuilder sb = new StringBuilder("GPUs ");
		for (int i = 0; i < gpus.size(); i++) sb.append(i > 0 ? ", " : "").append(gpus.get(i));
		sb.append(" (distributed)");
		// Then a line per GPU with its name (the run's tab shows them)
		for (int id : gpus)
			for (javax.swing.JCheckBox cb : gpuChecks)
				if (cb.getClientProperty("gpu").equals(id)) sb.append("\n").append(cb.getText());
		return sb.toString();
	}

	private static int freePort() {
		try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
			return s.getLocalPort();
		} catch (Exception e) {
			return 29500;
		}
	}

	/**
	 * Copy of the configuration to run on Apple Silicon's GPU (MPS), or null if it needs nothing. BiaPy versions
	 * before 3.7.2 ignore "--gpu mps" and run on SYSTEM.DEVICE, "cpu" by default, so it is set to "mps". The
	 * user's file is not modified.
	 */
	@SuppressWarnings("unchecked")
	private static File mpsConfig(File yaml, String jobName) throws Exception {
		org.yaml.snakeyaml.Yaml y = new org.yaml.snakeyaml.Yaml();
		java.util.Map<String, Object> cfg;
		try (java.io.Reader r = new java.io.InputStreamReader(new java.io.FileInputStream(yaml),
				java.nio.charset.StandardCharsets.UTF_8)) {
			Object root = y.load(r);
			if (!(root instanceof java.util.Map)) return null;
			cfg = (java.util.Map<String, Object>) root;
		}
		Object sys = cfg.get("SYSTEM");
		java.util.Map<String, Object> system = sys instanceof java.util.Map ? (java.util.Map<String, Object>) sys
				: new java.util.LinkedHashMap<>();
		if ("mps".equals(String.valueOf(system.get("DEVICE")))) return null;
		system.put("DEVICE", "mps");
		cfg.put("SYSTEM", system);
		File out = new File(System.getProperty("java.io.tmpdir"), "biapy_fiji_" + jobName + "_" + System.currentTimeMillis() + ".yaml");
		org.yaml.snakeyaml.DumperOptions opts = new org.yaml.snakeyaml.DumperOptions();
		opts.setDefaultFlowStyle(org.yaml.snakeyaml.DumperOptions.FlowStyle.BLOCK);
		try (java.io.Writer w = new java.io.OutputStreamWriter(new java.io.FileOutputStream(out),
				java.nio.charset.StandardCharsets.UTF_8)) {
			new org.yaml.snakeyaml.Yaml(opts).dump(cfg, w);
		}
		out.deleteOnExit();
		return out;
	}

	private void run() {
		File yaml = new File(configField.getText().trim());
		File out = new File(outputField.getText().trim());
		String name = jobName.getText().trim();
		if (!yaml.isFile()) {
			UiUtils.error(this, "Please select an existing configuration file.");
			return;
		}
		if (outputField.getText().trim().isEmpty()) {
			UiUtils.error(this, "Please select the output folder of this job.\n\nIt is cleared after each run, so that a "
					+ "new job does not write its results where the previous one did by mistake.");
			return;
		}
		if (name.isEmpty()) {
			UiUtils.error(this, "Please enter a job name.");
			return;
		}
		if (name.matches(".*[\\\\/:*?\"<>|\\s].*")) {
			UiUtils.error(this, "The job name cannot contain spaces or any of \\ / : * ? \" < > |");
			return;
		}
		if (name.matches("(?i).*\\.ya?ml")) {
			UiUtils.error(this, "The job name names the folder of the job's results, not the configuration file: "
					+ "please remove the \"" + name.substring(name.lastIndexOf('.')) + "\" at its end.");
			return;
		}
		if (jobFolder().exists() && !jobFolder().isDirectory()) {
			UiUtils.error(this, "There is a file where the job's results folder would be created:\n" + jobFolder()
					+ "\nPlease use another job name or output folder.");
			return;
		}
		if (!PythonEnv.pythonExists() || main.getEnvInfo() == null) {
			UiUtils.error(this, "BiaPy is not installed yet or its environment is not working.\nGo to the HOME tab to install it.");
			return;
		}
		if (main.findRunningJob(name, jobFolder()) != null) {
			UiUtils.error(this, "A run called '" + name + "' is still running and would write in the same folder.\n"
					+ "Please use another job name.");
			return;
		}
		boolean useGpus = "GPU".equals(device.getSelectedItem());
		List<Integer> gpus = useGpus ? selectedGpus() : new java.util.ArrayList<>();
		if (useGpus && gpus.isEmpty()) {
			UiUtils.error(this, "Please select at least one GPU (or choose the CPU).");
			return;
		}
		String dev = deviceDescription(gpus);
		if (!confirmRun(yaml, name, dev, gpus)) return;
		Prefs.set(PREF_OUT_DIR, out.getAbsolutePath());
		Prefs.savePreferences();

		List<String> cmd = PythonEnv.pythonCommand();
		if (gpus.size() > 1) {
			// One process per GPU, started by PyTorch's distributed launcher
			File launcher;
			try {
				launcher = io.github.biapyx.fiji.env.PythonBridge.script("biapy_run.py");
			} catch (Exception e) {
				UiUtils.error(this, "Could not prepare the distributed run: " + e.getMessage());
				return;
			}
			cmd.add("-u");
			cmd.add("-m");
			cmd.add("torch.distributed.run");
			cmd.add("--nproc_per_node=" + gpus.size());
			cmd.add("--master_port=" + freePort());
			cmd.add(launcher.getAbsolutePath());
		} else {
			cmd.add("-c");
			cmd.add("import sys, biapy; sys.argv[0] = 'biapy'; biapy.main()");
		}
		cmd.add("--config");
		File runConfig = yaml;
		boolean mps = device.getSelectedItem() != null && device.getSelectedItem().toString().startsWith("MPS");
		if (mps) {
			try {
				File patched = mpsConfig(yaml, name);
				if (patched != null) runConfig = patched;
			} catch (Exception e) {
				ij.IJ.log("[BiaPy] Could not prepare the configuration for Apple Silicon's GPU: " + e.getMessage());
			}
		}
		cmd.add(runConfig.getAbsolutePath());
		cmd.add("--result_dir");
		cmd.add(out.getAbsolutePath());
		cmd.add("--name");
		cmd.add(name);
		cmd.add("--run_id");
		cmd.add(String.valueOf(JobPanel.RUN_ID));
		cmd.add("--dist_backend");
		cmd.add(PlatformDetection.isWindows() ? "gloo" : "nccl");
		if (!gpus.isEmpty()) {
			cmd.add("--gpu");
			StringBuilder ids = new StringBuilder();
			for (int i = 0; i < gpus.size(); i++) ids.append(i > 0 ? "," : "").append(gpus.get(i));
			cmd.add(ids.toString());
		} else if (mps) {
			cmd.add("--gpu");
			cmd.add("mps");
		}
		main.startJob(name, yaml, jobFolder(), dev, cmd);
		outputField.setText("");
	}

	/**
	 * Always asks before running (prevents runs by mistake), with what will be run and a warning if any of the
	 * selected GPUs is already in use.
	 */
	private boolean confirmRun(File yaml, String name, String dev, List<Integer> gpus) {
		List<String> busy = gpus.isEmpty() ? null : io.github.biapyx.fiji.env.GpuStatus.busy(gpus);
		String html = confirmationHtml(main.biapyVersion(), yaml.getAbsolutePath(), name, jobFolder().getAbsolutePath(), dev,
				!gpus.isEmpty(), busy);
		return UiUtils.confirmHtml(this, "BiaPy - Run job", html, "Run", "Cancel");
	}

	/**
	 * Message of the run confirmation.
	 *
	 * @param busy descriptions of the selected GPUs in use (null: they could not be checked)
	 */
	static String confirmationHtml(String biapyVersion, String config, String name, String results, String dev,
			boolean usesGpus, List<String> busy) {
		StringBuilder html = new StringBuilder("<div style='width:440px'>");
		html.append("<h1>Do you want to run this job with BiaPy ").append(escape(biapyVersion))
				.append("?</h1><table cellpadding='2'>");
		html.append(row("BiaPy version", biapyVersion)).append(row("Configuration", config)).append(row("Job name", name))
				.append(row("Results", results))
				.append(row("Device", dev)).append("</table>");
		if (usesGpus) {
			if (busy == null) {
				html.append("<p class='muted'>The GPUs could not be checked (nvidia-smi not available).</p>");
			} else if (!busy.isEmpty()) {
				html.append("<table width='100%' cellpadding='6' style='background-color:#fff4e5'><tr><td style='color:#b05c00'>")
						.append("<b>").append(busy.size() == 1 ? "This GPU is" : "These GPUs are").append(" already in use:</b><ul>");
				for (String b : busy) html.append("<li>").append(escape(b)).append("</li>");
				html.append("</ul>Running another job on ").append(busy.size() == 1 ? "it" : "them")
						.append(" may make it run out of memory or go slower.</td></tr></table>");
			}
		}
		return UiUtils.htmlPage(html.append("</div>").toString());
	}

	private static String row(String key, String value) {
		return "<tr valign='top'><td><b>" + key + ":</b></td><td>" + escape(value) + "</td></tr>";
	}

	private static String escape(String s) {
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
