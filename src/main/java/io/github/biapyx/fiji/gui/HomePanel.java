package io.github.biapyx.fiji.gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.github.biapyx.fiji.BiaPyFiji;
import io.github.biapyx.fiji.env.PythonBridge;
import io.github.biapyx.fiji.env.BiaPyEnvManager;
import io.github.biapyx.fiji.env.BiaPyVersions;
import io.github.biapyx.fiji.env.PythonEnv;

/** HOME tab: welcome, useful links, Python environment status and shortcuts to the other tabs. */
public class HomePanel extends JPanel {

	private static final long serialVersionUID = 1L;

	private final MainPanel main;
	private final JLabel envStatus = new JLabel();
	private final JLabel envPath = new JLabel();
	private final JLabel gpuStatus = new JLabel();
	private final Spinner envSpinner = new Spinner(22);
	private final JButton installBtn = UiUtils.primaryButton("Install BiaPy",
			"Create a Python environment with the selected BiaPy version and its dependencies (needs internet, several GB)");
	/** BiaPy versions that can be installed (newest first); the selection is remembered. */
	private final javax.swing.JComboBox<String> versionBox = new javax.swing.JComboBox<>();
	private final JButton switchBtn = UiUtils.primaryButton("Switch version",
			"Install the selected BiaPy version in the current environment, replacing the installed one");
	private final JButton devSourceBtn = UiUtils.secondaryButton("Source...");
	private final JLabel versionNote = new JLabel(" ");
	private boolean fillingVersions = false;
	/** Last environment information received (to compare the installed version with the selected one). */
	private JsonObject envInfo;
	private final JButton customBtn = UiUtils.secondaryButton("Use existing Python...",
			"Use a Python environment of yours where BiaPy is already installed");
	private final JButton managedBtn = UiUtils.secondaryButton("Use Fiji's environment",
			"Go back to the environment the plugin installs and manages");
	private final JButton refreshBtn = UiUtils.secondaryButton("Refresh",
			"Check again the environment: BiaPy and PyTorch versions and the GPUs available");
	private final JButton cancelBtn = UiUtils.secondaryButton("Cancel installation",
			"Stop the installation; it can be started again later");
	private final InstallProgressPanel progress = new InstallProgressPanel();
	private volatile BiaPyEnvManager installer;

	public HomePanel(MainPanel main) {
		super(new BorderLayout());
		this.main = main;
		setBackground(BiaPyFiji.WHITE);

		JPanel body = new WidthTrackingPanel();
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setBackground(BiaPyFiji.WHITE);
		body.setBorder(BorderFactory.createEmptyBorder(20, 30, 20, 30));

		JLabel title = UiUtils.title("Welcome to BiaPy", 26f);
		title.setAlignmentX(Component.LEFT_ALIGNMENT);
		body.add(title);
		body.add(Box.createVerticalStrut(6));
		// Fixed HTML width that fits the minimum window size
		JLabel subtitle = new JLabel("<html><div style='width:500px'>BiaPy is an open source, ready-to-use library that "
				+ "provides deep-learning workflows for a large variety of bioimage analysis tasks: semantic and instance "
				+ "segmentation, object detection, denoising, super-resolution, self-supervised learning, classification "
				+ "and image to image translation, in 2D and 3D.</div></html>");
		subtitle.setFont(subtitle.getFont().deriveFont(Font.PLAIN, 13f));
		subtitle.setAlignmentX(Component.LEFT_ALIGNMENT);
		body.add(subtitle);
		body.add(Box.createVerticalStrut(18));

		body.add(buildLinks());
		body.add(Box.createVerticalStrut(18));
		body.add(buildVersionRow());
		body.add(Box.createVerticalStrut(8));
		body.add(buildEnvCard());
		body.add(Box.createVerticalStrut(18));
		body.add(buildShortcuts());
		body.add(Box.createVerticalGlue());

		JScrollPane sp = new JScrollPane(body);
		sp.setBorder(null);
		sp.getViewport().setBackground(BiaPyFiji.WHITE);
		sp.getVerticalScrollBar().setUnitIncrement(16);
		add(sp, BorderLayout.CENTER);

		main.addEnvListener(this::updateEnvStatus);
	}

	/** BiaPy version to install (above the environment card). */
	private JPanel buildVersionRow() {
		JPanel versionRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
		versionRow.setOpaque(false);
		versionRow.add(new JLabel("BiaPy version:"));
		versionRow.add(versionBox);
		versionRow.add(devSourceBtn);
		versionRow.add(switchBtn);
		versionNote.setForeground(BiaPyFiji.GREY);
		versionRow.add(versionNote);
		versionBox.setToolTipText("BiaPy versions supported by this plugin (" + BiaPyFiji.BIAPY_MIN_VERSION
				+ " or newer). Your choice is remembered.");
		switchBtn.setVisible(false);
		switchBtn.addActionListener(e -> switchVersion());
		versionBox.addActionListener(e -> {
			if (fillingVersions) return;
			String v = selectedVersion();
			if (BiaPyVersions.isDev(v)) askDevSource();
			if (v != null) BiaPyVersions.select(v);
			updateVersionControls();
		});
		devSourceBtn.setVisible(false);
		devSourceBtn.setToolTipText("Where the development version is installed from");
		devSourceBtn.addActionListener(e -> {
			askDevSource();
			updateVersionControls();
		});
		fillVersions(BiaPyVersions.cached(), null);
		new Thread(() -> {
			try {
				java.util.List<String> versions = BiaPyVersions.fetch();
				SwingUtilities.invokeLater(() -> fillVersions(versions, null));
			} catch (Exception ex) {
				SwingUtilities.invokeLater(() -> fillVersions(BiaPyVersions.cached(),
						"could not get the list of versions from PyPI (offline?)"));
			}
		}, "BiaPy-versions").start();
		versionRow.setAlignmentX(Component.LEFT_ALIGNMENT);
		versionRow.setMaximumSize(new java.awt.Dimension(900, versionRow.getPreferredSize().height));
		return versionRow;
	}

	private JPanel buildLinks() {
		JPanel links = new JPanel(new GridLayout(1, 6, 8, 0));
		links.setBackground(BiaPyFiji.WHITE);
		links.add(linkCard("Project page", "images/github_icon.png", BiaPyFiji.URL_PROJECT));
		links.add(linkCard("Forum", "images/forum_icon.png", BiaPyFiji.URL_FORUM));
		links.add(linkCard("Documentation", "images/doc_icon.png", BiaPyFiji.URL_DOCS));
		links.add(linkCard("Templates", "images/template_icon.png", BiaPyFiji.URL_TEMPLATES));
		links.add(linkCard("Notebooks", "images/notebook_icon.png", BiaPyFiji.URL_NOTEBOOKS));
		links.add(linkCard("Citation", "images/citation_icon.png", BiaPyFiji.URL_CITATION));
		links.setAlignmentX(Component.LEFT_ALIGNMENT);
		links.setMaximumSize(new java.awt.Dimension(900, 110));
		return links;
	}

	private JPanel linkCard(String text, String icon, String url) {
		JPanel card = new JPanel(new BorderLayout(0, 6));
		card.setBackground(BiaPyFiji.WHITE);
		card.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(BiaPyFiji.LIGHT_BLUE, 2, true),
				BorderFactory.createEmptyBorder(10, 6, 10, 6)));
		card.add(new JLabel(BiaPyFiji.icon(icon, 40), SwingConstants.CENTER), BorderLayout.CENTER);
		JLabel l = new JLabel(text, SwingConstants.CENTER);
		l.setFont(l.getFont().deriveFont(Font.PLAIN, 11f));
		l.setForeground(BiaPyFiji.BLUE);
		card.add(l, BorderLayout.SOUTH);
		card.setToolTipText(url);
		makeClickable(card, () -> UiUtils.openUrl(url));
		return card;
	}

	private JPanel buildEnvCard() {
		JPanel card = new JPanel(new BorderLayout(0, 8)) {
			private static final long serialVersionUID = 1L;

			@Override
			public java.awt.Dimension getMaximumSize() {
				return new java.awt.Dimension(900, getPreferredSize().height);
			}
		};
		card.setBackground(new Color(245, 249, 255));
		card.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(BiaPyFiji.LIGHT_BLUE, 2, true),
				BorderFactory.createEmptyBorder(12, 14, 12, 14)));

		JPanel info = new JPanel(new GridLayout(0, 1, 0, 4));
		info.setOpaque(false);
		// Title with a spinner on the right while the environment and GPUs are checked in the background
		JPanel titleRow = new JPanel(new BorderLayout());
		titleRow.setOpaque(false);
		titleRow.add(UiUtils.title("Python environment", 16f), BorderLayout.WEST);
		JPanel spin = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
		spin.setOpaque(false);
		spin.add(envSpinner);
		titleRow.add(spin, BorderLayout.EAST);
		info.add(titleRow);
		info.add(envStatus);
		envPath.setForeground(BiaPyFiji.GREY);
		info.add(envPath);
		info.add(gpuStatus);
		card.add(info, BorderLayout.NORTH);

		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
		buttons.setOpaque(false);
		buttons.add(installBtn);
		buttons.add(customBtn);
		buttons.add(managedBtn);
		buttons.add(refreshBtn);
		buttons.add(cancelBtn);
		card.add(buttons, BorderLayout.CENTER);

		progress.setVisible(false);
		card.add(progress, BorderLayout.SOUTH);

		installBtn.addActionListener(e -> install());
		customBtn.addActionListener(e -> chooseCustomPython());
		managedBtn.addActionListener(e -> {
			PythonEnv.setCustomPython(null);
			main.refreshEnvInfo();
		});
		refreshBtn.addActionListener(e -> main.refreshEnvInfo());
		cancelBtn.addActionListener(e -> {
			BiaPyEnvManager i = installer;
			if (i != null) i.cancel();
		});
		cancelBtn.setVisible(false);

		card.setAlignmentX(Component.LEFT_ALIGNMENT);
		return card;
	}

	private JPanel buildShortcuts() {
		JPanel p = new JPanel(new GridLayout(1, 2, 20, 0)) {
			private static final long serialVersionUID = 1L;

			// As tall as its texts once wrapped (narrow windows wrap them in more lines), and no taller
			@Override
			public java.awt.Dimension getMaximumSize() {
				return new java.awt.Dimension(900, getPreferredSize().height);
			}
		};
		p.setBackground(BiaPyFiji.WHITE);
		p.add(shortcut("First timers: talk to the Wizard!",
				"Answer a few questions and the Wizard will create the configuration file for you.",
				"wizard/wizard.png", MainPanel.WIZARD));
		p.add(shortcut("Frequent users",
				"Already have a configuration file? Go directly to run your workflow.", "images/biapy_logo.png",
				MainPanel.RUN));
		p.setAlignmentX(Component.LEFT_ALIGNMENT);
		return p;
	}

	private JPanel shortcut(String title, String text, String icon, String page) {
		JPanel card = new JPanel(new BorderLayout(12, 0));
		card.setBackground(BiaPyFiji.WHITE);
		card.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(BiaPyFiji.BLUE, 2, true),
				BorderFactory.createEmptyBorder(10, 10, 10, 10)));
		card.add(new JLabel(BiaPyFiji.icon(icon, 70)), BorderLayout.WEST);
		// The text wraps to the width the card gives it. With a fixed CSS width it was cut when the card was narrower
		// (a small window, or wider fonts as on Windows), e.g. the "!" of the Wizard's title; Swing's HTML also makes
		// CSS pixels about a third wider than the screen's, so no CSS width is used: the HTML view wraps to the
		// label's own width, and the label asks for the height of the wrapped text
		JLabel l = new JLabel("<html><b style='font-size:12px'>" + title + "</b><br>" + text + "</html>") {
			private static final long serialVersionUID = 1L;

			@Override
			public java.awt.Dimension getPreferredSize() {
				javax.swing.text.View v = (javax.swing.text.View) getClientProperty(javax.swing.plaf.basic.BasicHTML.propertyKey);
				if (v == null) return super.getPreferredSize();
				int w = getWidth() > 0 ? getWidth() : 220;
				v.setSize(w, 0);
				return new java.awt.Dimension(220, (int) Math.ceil(v.getPreferredSpan(javax.swing.text.View.Y_AXIS)));
			}

			@Override
			public java.awt.Dimension getMinimumSize() {
				return new java.awt.Dimension(0, getPreferredSize().height);
			}

			@Override
			public void setBounds(int x, int y, int w, int h) {
				boolean resized = w != getWidth();
				super.setBounds(x, y, w, h);
				if (resized) revalidate(); // its height depends on its width
			}
		};
		l.setFont(l.getFont().deriveFont(Font.PLAIN, 12f));
		card.add(l, BorderLayout.CENTER);
		makeClickable(card, () -> main.showPage(page));
		return card;
	}

	/** Whole card reacts to the mouse (like the tabs): hand cursor, light-blue highlight, press = click. */
	private static void makeClickable(JPanel card, Runnable action) {
		card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		card.addMouseListener(new MouseAdapter() {
			@Override
			public void mousePressed(MouseEvent e) {
				card.setBackground(BiaPyFiji.WHITE);
				action.run();
			}

			@Override
			public void mouseEntered(MouseEvent e) {
				card.setBackground(BiaPyFiji.LIGHT_BLUE);
			}

			@Override
			public void mouseExited(MouseEvent e) {
				card.setBackground(BiaPyFiji.WHITE);
			}
		});
	}

	/** Fills the versions (newest first) and selects the remembered one, or the latest. */
	private void fillVersions(java.util.List<String> versions, String problem) {
		fillingVersions = true;
		versionBox.removeAllItems();
		java.util.List<String> all = new java.util.ArrayList<>(versions);
		String selected = BiaPyVersions.selected();
		if (!all.contains(selected) && !BiaPyVersions.isDev(selected) && !versions.isEmpty()) all.add(selected);
		if (!all.contains(selected) && !BiaPyVersions.isDev(selected)) selected = BiaPyVersions.DEV; // no release yet
		all.sort((a, b) -> BiaPyVersions.compare(b, a));
		all.add(BiaPyVersions.DEV); // last: not a release
		for (int i = 0; i < all.size(); i++)
			versionBox.addItem(all.get(i) + (i == 0 && !BiaPyVersions.isDev(all.get(i)) ? " (latest)" : ""));
		for (int i = 0; i < all.size(); i++) if (all.get(i).equals(selected)) versionBox.setSelectedIndex(i);
		fillingVersions = false;
		if (problem != null) versionNote.setText(problem);
		updateVersionControls();
	}

	private String selectedVersion() {
		Object o = versionBox.getSelectedItem();
		return o == null ? null : o.toString().replace(" (latest)", "");
	}

	/** Where the installed BiaPy comes from when it is not a release (a URL or a folder), or null. */
	private String installedSource() {
		return installedVersion() != null && envInfo.has("biapy_source") && !envInfo.get("biapy_source").isJsonNull()
				? envInfo.get("biapy_source").getAsString() : null;
	}

	private static String abbreviateSource(String s) {
		String gh = "https://github.com/";
		if (s.startsWith(gh) && s.contains("/archive/refs/heads/"))
			return s.substring(gh.length(), s.indexOf("/archive/")) + "@" + s.replaceAll(".*/archive/refs/heads/", "").replaceAll("\\.zip$", "");
		return s.length() > 60 ? "..." + s.substring(s.length() - 57) : s;
	}

	/** Asks where the development version is installed from (remembered). */
	private void askDevSource() {
		Object answer = javax.swing.JOptionPane.showInputDialog(this,
				"Install BiaPy's development version from:\n"
						+ " - a GitHub repository and branch, as owner/repository@branch (e.g. " + BiaPyVersions.DEFAULT_DEV_SOURCE + ")\n"
						+ " - or a URL or folder that pip can install (e.g. your BiaPy checkout)",
				"BiaPy development version", javax.swing.JOptionPane.QUESTION_MESSAGE, null, null, BiaPyVersions.devSource());
		if (answer != null) BiaPyVersions.setDevSource(answer.toString());
	}

	/** Version installed in the environment in use, or null. */
	private String installedVersion() {
		return envInfo != null && "ok".equals(envInfo.get("_state").getAsString()) && envInfo.has("biapy_version")
				&& !envInfo.get("biapy_version").isJsonNull() ? envInfo.get("biapy_version").getAsString() : null;
	}

	/** Note next to the versions and the button to switch to the selected one when another is installed. */
	private void updateVersionControls() {
		String selected = selectedVersion();
		String installed = installedVersion();
		boolean custom = PythonEnv.getCustomPython() != null;
		boolean installing = installer != null;
		versionBox.setEnabled(!custom && !installing);
		devSourceBtn.setVisible(!custom && BiaPyVersions.isDev(selected));
		devSourceBtn.setEnabled(!installing);
		switchBtn.setVisible(false);
		if (custom) {
			versionNote.setText(installed == null ? "your own Python environment is used"
					: "your own Python environment has BiaPy " + installed);
		} else if (BiaPyVersions.isDev(selected)) {
			String source = installedSource();
			versionNote.setText(installed == null ? "from " + BiaPyVersions.devSource()
					: source != null ? "installed from " + abbreviateSource(source) : "BiaPy " + installed + " is installed");
			if (installed != null) {
				switchBtn.setText(source != null ? "Update" : "Switch to the development version");
				switchBtn.setVisible(true);
				switchBtn.setEnabled(!installing);
			}
		} else if (installed == null) {
			versionNote.setText(selected == null ? " " : "'Install BiaPy' installs this version");
		} else if (installed.equals(selected) && installedSource() == null) {
			versionNote.setText("installed");
		} else {
			// The development version has the number of a release: it is told apart by its source
			versionNote.setText(installedSource() != null ? "the development version (" + installed + ") is installed"
					: "BiaPy " + installed + " is installed");
			switchBtn.setText("Switch to BiaPy " + selected);
			switchBtn.setVisible(true);
			switchBtn.setEnabled(!installing);
		}
		revalidate();
		repaint();
	}

	private void updateEnvStatus(JsonObject info) {
		envInfo = info;
		updateVersionControls();
		String state = info.get("_state").getAsString();
		boolean verifying = info.has("_verifying") && info.get("_verifying").getAsBoolean();
		envSpinner.spin("checking".equals(state) || verifying);

		String custom = PythonEnv.getCustomPython();
		envPath.setText("Python: " + PythonEnv.getPython().getAbsolutePath() + (custom != null ? "  (user selected)" : "  (Fiji-managed)"));
		managedBtn.setVisible(custom != null);
		boolean installing = installer != null;
		installBtn.setEnabled(!installing && custom == null);
		customBtn.setEnabled(!installing);
		refreshBtn.setEnabled(!installing);
		if (installing) {
			envStatus.setText("Installing BiaPy . . .");
			envStatus.setForeground(BiaPyFiji.BLUE);
			return;
		}
		switch (state) {
		case "checking":
			envStatus.setText("Checking the Python environment . . .");
			envStatus.setForeground(BiaPyFiji.GREY);
			gpuStatus.setText(" ");
			break;
		case "ok":
			String biapy = info.get("biapy_version").getAsString();
			String problem = BiaPyFiji.wizardProblem(info);
			envStatus.setText("<html>BiaPy <b>" + biapy + "</b> ready (Python " + info.get("python").getAsString()
					+ ", PyTorch " + str(info.get("torch_version")) + ")"
					+ (problem == null ? "" : " &nbsp;<span style='color:#ff8c00'>" + problem + "</span>")
					+ (info.has("bmz_warning") ? "<br><span style='color:#e67e00'>" + info.get("bmz_warning").getAsString() + "</span>" : "")
					+ "</html>");
			envStatus.setForeground(BiaPyFiji.GREEN);
			installBtn.setText("Reinstall BiaPy");
			progress.setVisible(false); // installation (if any) went fine
			revalidate();
			repaint();
			gpuStatus.setText(gpuText(info));
			break;
		case "broken":
			if (info.has("torch_error"))
				envStatus.setText("<html><div style='width:600px'>BiaPy is installed but PyTorch does not work: "
						+ info.get("torch_error").getAsString().replace("<", "&lt;")
						+ "<br>Reinstall BiaPy or select another environment.</div></html>");
			else
				envStatus.setText("Python found but BiaPy is not installed in it. Install it or select another environment.");
			envStatus.setForeground(BiaPyFiji.RED);
			installBtn.setText("Reinstall BiaPy");
			gpuStatus.setText(" ");
			break;
		default:
			envStatus.setText("BiaPy is not installed yet. Click 'Install BiaPy' (needs internet, several GB).");
			envStatus.setForeground(BiaPyFiji.RED);
			installBtn.setText("Install BiaPy");
			gpuStatus.setText(" ");
		}
	}

	private static String str(JsonElement e) {
		return e == null || e.isJsonNull() ? "?" : e.getAsString();
	}

	private static String gpuText(JsonObject info) {
		if (!info.has("gpus") || info.getAsJsonArray("gpus").size() == 0) {
			boolean mps = info.has("mps_available") && info.get("mps_available").getAsBoolean();
			return mps ? "<html><b>GPU:</b> Apple Silicon (MPS) available</html>"
					: "<html><b>GPU:</b> none found, BiaPy will run on CPU (slow for training)</html>";
		}
		StringBuilder sb = new StringBuilder("<html><div style='width:540px'><b>GPU:</b> ");
		for (JsonElement g : info.getAsJsonArray("gpus")) {
			JsonObject o = g.getAsJsonObject();
			sb.append(o.get("index").getAsInt()).append(": ").append(o.get("name").getAsString()).append(" (")
					.append(o.get("memory_mb").getAsInt() / 1024).append(" GB) &nbsp; ");
		}
		if (info.has("gpu_warning"))
			sb.append("<br><span style='color:#e67e00'>").append(info.get("gpu_warning").getAsString()).append("</span>");
		return sb.append("</div></html>").toString();
	}

	private void chooseCustomPython() {
		File f = UiUtils.chooseFile(this, PythonEnv.getCustomPython(), "Python executable");
		if (f == null) return;
		PythonEnv.setCustomPython(f.getAbsolutePath());
		main.refreshEnvInfo();
	}

	private void install() {
		final boolean fresh;
		if (BiaPyEnvManager.managedEnvExists()) {
			// Typically an interrupted installation: continuing reuses what was already downloaded
			int choice = UiUtils.choose(this, "A BiaPy environment already exists in:\n" + PythonEnv.getManagedRoot()
					+ "\n\n'Continue / repair' keeps it and installs only what is missing (recommended after a failed "
					+ "installation).\n\n'Start from scratch' deletes it and installs everything again.",
					"Continue / repair", "Start from scratch", "Cancel");
			if (choice < 0 || choice == 2) return;
			fresh = choice == 1;
		} else {
			if (!UiUtils.confirm(this, "BiaPy and its dependencies (PyTorch, etc.) will be downloaded and installed into:\n"
					+ PythonEnv.getManagedRoot() + "\n\nThis needs an internet connection and several GB of disk. Continue?"))
				return;
			fresh = true;
		}
		String version = selectedVersion() != null ? selectedVersion() : BiaPyVersions.selected();
		BiaPyVersions.select(version);
		runInstaller("Installing " + BiaPyVersions.describe(version) + " . . .", i -> i.install(fresh, version));
	}

	/** Installs the selected version in the existing Fiji-managed environment instead of the one it has. */
	private void switchVersion() {
		String version = selectedVersion();
		String installed = installedVersion();
		if (version == null) return;
		if (!UiUtils.confirm(this, BiaPyVersions.describe(version) + " will be installed in Fiji's Python environment"
				+ (installed != null ? ", replacing BiaPy " + installed : "") + ".\n\nThis needs an internet connection. Continue?"))
			return;
		BiaPyVersions.select(version);
		runInstaller("Installing " + BiaPyVersions.describe(version) + " . . .", i -> i.switchVersion(version));
	}

	private interface InstallTask {
		void run(BiaPyEnvManager installer) throws Exception;
	}

	private void runInstaller(String message, InstallTask task) {
		progress.reset();
		progress.setVisible(true);
		cancelBtn.setVisible(true);
		installer = new BiaPyEnvManager(progress);
		installBtn.setEnabled(false);
		customBtn.setEnabled(false);
		refreshBtn.setEnabled(false);
		updateVersionControls();
		envStatus.setText(message);
		envStatus.setForeground(BiaPyFiji.BLUE);
		revalidate();
		new Thread(() -> {
			String error = null;
			try {
				PythonBridge.shutdown(); // it uses the environment being changed
				task.run(installer);
			} catch (BiaPyEnvManager.NetworkException t) {
				error = t.getMessage();
				progress.log("ERROR: " + error);
			} catch (InterruptedException t) {
				error = "Installation canceled. Click 'Install BiaPy' to continue it later.";
			} catch (Throwable t) {
				error = t.getMessage() == null ? t.toString() : t.getMessage();
				progress.log("ERROR: " + error);
			}
			progress.finished(error);
			final String fe = error;
			SwingUtilities.invokeLater(() -> {
				installer = null;
				cancelBtn.setVisible(false);
				if (fe != null) UiUtils.error(this, "BiaPy installation failed:\n" + fe);
				main.refreshEnvInfo();
			});
		}, "BiaPy-install").start();
	}
}
