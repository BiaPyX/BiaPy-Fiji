package io.github.biapyx.fiji.gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import io.github.biapyx.fiji.BiaPyFiji;
import io.github.biapyx.fiji.env.ProcessRunner;
import io.github.biapyx.fiji.env.PythonEnv;

/**
 * One BiaPy run, shown in its own tab under "BiaPy runs" in the sidebar: job summary, one progress bar per
 * phase (training in epochs, test in images), training curves and prediction previews, and the output.
 */
public class JobPanel extends JPanel {

	private static final long serialVersionUID = 1L;

	public enum Status {
		RUNNING("Running", new Color(255, 196, 0)), FINISHED("Finished", BiaPyFiji.GREEN),
		FAILED("Failed", BiaPyFiji.RED), STOPPED("Stopped", new Color(150, 150, 150));

		public final String label;
		public final Color color;

		Status(String label, Color color) {
			this.label = label;
			this.color = color;
		}
	}

	/** run_id passed to BiaPy (see RunPanel); the job identifier is "<name>_<run_id>". */
	static final int RUN_ID = 1;
	private static final int INDENT = 10;

	// BiaPy output patterns
	private static final Pattern PHASE = Pattern.compile("#\\s{1,4}([A-Z][A-Z ]+[A-Z])\\s{1,4}#\\s*$");
	private static final Pattern EPOCH = Pattern.compile("~~~ Epoch (\\d+)/(\\d+) ~~~");
	private static final Pattern ITER = Pattern.compile("Epoch: \\[(\\d+)\\]\\s+\\[\\s*(\\d+)/(\\d+)\\]\\s+eta: (\\S+)");
	private static final Pattern IMAGE = Pattern.compile("Processing image: (\\S+)");
	private static final Pattern STATS = Pattern.compile("\\[(Train|Val)\\] averaged stats: (.*)");
	private static final Pattern TIMESTAMP = Pattern.compile("^\\[\\d\\d:\\d\\d:\\d\\d\\.\\d+\\]\\s?");

	private final String jobName;
	private final File configFile;
	private final String device, biapyVersion;
	/** This run in the History page (null until it starts). */
	private String historyId;
	/** Final metrics BiaPy prints after the RESULTS banner, for the History page (not shown here). */
	private final List<String> resultLines = new java.util.ArrayList<>();
	private final File jobFolder;
	private final List<String> command;
	private final Consumer<JobPanel> onStatusChange;
	private final Map<?, ?> cfg;
	private volatile Status status = Status.RUNNING;
	private ProcessRunner runner;
	private final long startTime = System.currentTimeMillis();
	private long endTime = 0;

	private final JLabel statusLabel = new JLabel();
	private final JLabel timeLabel = new JLabel();
	private final JLabel phaseLabel = new JLabel("Starting BiaPy . . .");
	private final boolean train;
	private final boolean test;
	private final JProgressBar trainBar = new JProgressBar();
	private final JLabel trainDetail = new JLabel(" ");
	private final JProgressBar testBar = new JProgressBar();
	private final JLabel testDetail = new JLabel(" ");
	private int currentEpoch = 0;
	private int testImages = 0;
	private final int testTotal;
	private boolean testStarted = false;
	/** The metrics of the finished job, above the progress (hidden until then). */
	private final JPanel results = new JPanel(new BorderLayout());
	/** The last averaged loss and metrics; wraps to the width of the progress column. */
	private final WrappingTextArea metricsLabel = new WrappingTextArea(" ");
	private boolean inResults = false;
	/** "Workflow info", which glows when the job finishes. */
	private final BeaconButton docs = new BeaconButton("Workflow info", null);
	private final JButton stopBtn = UiUtils.primaryButton("Stop", "Stop the job (asks first); what it saved so far stays in its folder");
	private final JButton closeBtn = UiUtils.secondaryButton("Close tab",
			"Close this tab (asks first; a running job is stopped). Its results stay in its folder");
	private final OutputBox console = new OutputBox("Show BiaPy output", "Hide BiaPy output", 220);
	private final TrainingMonitor monitor;
	/** Null when the job does not test. */
	private final TestPredictions testPredictions;
	/** Height of the box with the output, training predictions and curves and test predictions tabs. */
	private static final int TABS_HEIGHT = 400;
	private final Timer clock;
	private final JPanel content;

	public JobPanel(String jobName, File configFile, File jobFolder, String device, String biapyVersion, List<String> command,
			String note, Consumer<JobPanel> onStatusChange, Consumer<JobPanel> onClose) {
		super(new BorderLayout());
		this.jobName = jobName;
		this.configFile = configFile;
		this.device = device;
		this.biapyVersion = biapyVersion;
		this.jobFolder = jobFolder;
		this.command = command;
		this.onStatusChange = onStatusChange;
		this.cfg = readConfig(configFile);
		this.train = bool(get(cfg, "TRAIN", "ENABLE"));
		this.test = bool(get(cfg, "TEST", "ENABLE"));
		this.testTotal = test ? countTestImages(cfg) : 0;
		setBackground(BiaPyFiji.WHITE);

		// Header in two columns (70% / 30% of the width): on the left the name, status and time and below them the
		// summary; on the right the devices at the top and the buttons at the bottom, level with the summary's last line
		// The status and time on the baseline of the name: centering their boxes left them higher than the name
		JPanel titleRow = new JPanel(new java.awt.GridBagLayout());
		titleRow.setOpaque(false);
		java.awt.GridBagConstraints tc = new java.awt.GridBagConstraints();
		tc.anchor = java.awt.GridBagConstraints.BASELINE_LEADING;
		titleRow.add(UiUtils.title(jobName, 20f), tc);
		tc.insets = new java.awt.Insets(0, INDENT, 0, 0);
		statusLabel.setFont(statusLabel.getFont().deriveFont(Font.BOLD, 13f));
		titleRow.add(statusLabel, tc);
		timeLabel.setForeground(BiaPyFiji.GREY);
		titleRow.add(timeLabel, tc);
		tc.weightx = 1;
		titleRow.add(new JLabel(), tc);
		JPanel info = new JPanel();
		info.setLayout(new BoxLayout(info, BoxLayout.Y_AXIS));
		info.setOpaque(false);
		info.add(infoRow("Workflow", workflowText(cfg) + " &nbsp;<span style='color:#777777'>(BiaPy " + biapyVersion + ")</span>"));
		JPanel modelRow = modelRow(cfg);
		if (modelRow != null) info.add(modelRow);
		info.add(pathRow("Configuration", configFile.getAbsolutePath()));
		info.add(pathRow("Results", jobFolder.getAbsolutePath()));
		info.add(citeRow(configFile));
		if (note != null) info.add(infoRow("Note", note));
		JPanel left = new JPanel(new BorderLayout(0, 4));
		left.setOpaque(false);
		left.add(titleRow, BorderLayout.NORTH);
		left.add(info, BorderLayout.SOUTH);

		// The actions in a 2x2 grid, at the bottom of the right column (level with the last line of the summary)
		JPanel buttons = new JPanel(new java.awt.GridLayout(2, 2, 4, 4));
		buttons.setOpaque(false);
		JButton open = UiUtils.secondaryButton("Open folder", "Open the folder of the job, with its results, checkpoints and logs");
		open.addActionListener(e -> {
			if (jobFolder.exists()) UiUtils.openFolder(jobFolder);
			else UiUtils.info(this, "The job's folder does not exist yet:\n" + jobFolder);
		});
		String type = str(get(cfg, "PROBLEM", "TYPE"), "SEMANTIC_SEG");
		docs.setToolTipText("What the " + WORKFLOWS.getOrDefault(type, type).toLowerCase() + " workflow does, what this "
				+ "job creates and how to look at its results");
		docs.addActionListener(e -> WorkflowInfoDialog.show(this, configFile, type, jobFolder, jobName + "_" + RUN_ID,
				logFile(), docsUrl(type)));
		stopBtn.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
		// Little padding, so the labels fit in half of the column
		for (JButton b : new JButton[] { closeBtn, open, docs }) b.setMargin(new Insets(3, 4, 3, 4));
		buttons.add(stopBtn);
		buttons.add(closeBtn);
		buttons.add(open);
		buttons.add(docs);
		JPanel right = new JPanel(new BorderLayout());
		right.setOpaque(false);
		right.add(buttons, BorderLayout.SOUTH);
		JPanel header = new Columns(left, right, 0.7, 12);
		stopBtn.addActionListener(e -> {
			if (UiUtils.confirm(this, "Do you want to stop '" + jobName + "'?")) runner.cancel();
		});
		closeBtn.addActionListener(e -> {
			if (isRunning()) {
				if (!UiUtils.confirm(this, "'" + jobName + "' is still running.\nDo you want to stop it and close its tab?")) return;
				runner.cancel();
			} else if (!UiUtils.confirm(this, "Do you want to close the tab of '" + jobName + "'?\n"
					+ "Its results stay in its folder.")) {
				return;
			}
			docs.stopGlow();
			onClose.accept(this);
		});

		// Progress (one bar per phase requested) and, on the right, the devices
		JPanel progress = new JPanel(new GridBagLayout());
		progress.setOpaque(false);
		GridBagConstraints c = new GridBagConstraints();
		c.gridx = 0;
		c.gridwidth = 2;
		c.fill = GridBagConstraints.HORIZONTAL;
		c.weightx = 1;
		c.insets = new Insets(2, 0, 2, 0);
		phaseLabel.setFont(phaseLabel.getFont().deriveFont(Font.BOLD, 14f));
		phaseLabel.setForeground(BiaPyFiji.BLUE);
		c.gridy = 0;
		progress.add(phaseLabel, c);
		int row = 1;
		if (train) {
			int epochs = Integer.parseInt(str(get(cfg, "TRAIN", "EPOCHS"), "360"));
			trainBar.setMaximum(epochs);
			trainBar.setString("0 / " + epochs + " epochs");
			row = addPhase(progress, row, "Training", trainBar, trainDetail);
		}
		if (test) {
			if (testTotal > 0) {
				testBar.setMaximum(testTotal);
				testBar.setString("0 / " + testTotal + " images");
			} else {
				testBar.setString("waiting");
			}
			testDetail.setText(train ? "Starts after training" : " ");
			row = addPhase(progress, row, "Test", testBar, testDetail);
		}
		metricsLabel.setFont(new JLabel().getFont().deriveFont(Font.PLAIN, 12f));
		metricsLabel.setForeground(new Color(85, 85, 85));
		metricsLabel.setVisible(false); // shown with the first epoch's metrics
		c.gridx = 0;
		c.gridwidth = 2;
		c.gridy = row;
		progress.add(metricsLabel, c);

		JPanel devices = new JPanel();
		devices.setLayout(new BoxLayout(devices, BoxLayout.Y_AXIS));
		devices.setOpaque(false);
		JLabel devicesTitle = new JLabel("Devices");
		devicesTitle.setFont(devicesTitle.getFont().deriveFont(Font.BOLD, 12f));
		devicesTitle.setAlignmentX(CENTER_ALIGNMENT);
		DeviceBadge badge = new DeviceBadge(device, 0.95);
		badge.setAlignmentX(CENTER_ALIGNMENT);
		badge.setMaximumSize(badge.getPreferredSize());
		devices.add(devicesTitle);
		devices.add(javax.swing.Box.createVerticalStrut(2));
		devices.add(badge);
		JPanel devicesColumn = new JPanel(new BorderLayout());
		devicesColumn.setOpaque(false);
		devicesColumn.add(devices, BorderLayout.NORTH);
		JPanel progressBox = new Columns(progress, devicesColumn, 0.7, 12);
		progressBox.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(BiaPyFiji.LIGHT_BLUE, 2, true),
				BorderFactory.createEmptyBorder(8, 10, 8, 10)));

		// BiaPy's denoising is Noise2Void, except with NAFNet (supervised, trained with clean images)
		boolean noise2void = "DENOISING".equals(str(get(cfg, "PROBLEM", "TYPE"), ""))
				&& !"nafnet".equalsIgnoreCase(str(get(cfg, "MODEL", "ARCHITECTURE"), "unet"));
		monitor = new TrainingMonitor(jobFolder, jobName + "_" + RUN_ID, train,
				train && !"-1".equals(str(get(cfg, "TRAIN", "SAVE_TRAIN_PREDS_FREQ"), "-1")), noise2void);

		JButton log = UiUtils.secondaryButton("Open full log", "Complete output of this run, saved in the 'logs' folder of the job");
		log.addActionListener(e -> openLog());
		console.addRight(log);
		console.alwaysShown();
		console.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
		console.setOpaque(true);
		console.setBackground(BiaPyFiji.WHITE);
		String testPath = str(get(cfg, "DATA", "TEST", "PATH"), "");
		testPredictions = test ? new TestPredictions(new File(new File(jobFolder, "results"), jobName + "_" + RUN_ID),
				testPath.isEmpty() ? null : new File(testPath)) : null;

		// The output, the training predictions and curves, and the test predictions: a tab each
		javax.swing.JTabbedPane tabs = new javax.swing.JTabbedPane();
		// A frame around the tabs' content: FlatLaf (Fiji's look) draws none by default; others already do
		tabs.putClientProperty("JTabbedPane.hasFullBorder", Boolean.TRUE);
		tabs.addTab("BiaPy output", null, console, "The text BiaPy prints while it runs: its progress, warnings and errors");
		if (train) {
			tabs.addTab("Training predictions", null, scrolled(monitor.predictionsView()),
					"How the model predicts a few samples as it trains, to see whether it is learning the task");
			tabs.addTab("Training curves", null, scrolled(monitor.chartsView()),
					"The loss and the metrics after every epoch, for the training and validation data");
		}
		if (test)
			tabs.addTab("Test predictions", null, scrolled(testPredictions.view()),
					"The predictions of the test images, as BiaPy saves them");
		tabs.setPreferredSize(new java.awt.Dimension(0, TABS_HEIGHT));

		// Everything stacked; the tab scrolls if the window is small
		content = new WidthTrackingPanel();
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setBackground(BiaPyFiji.WHITE);
		content.setBorder(BorderFactory.createEmptyBorder(14, 18, 14, 18));
		// Each block keeps its natural height; otherwise BoxLayout stretches them to fill the tab and the
		// progress box showed blank bands. The space left goes to the glue at the bottom.
		results.setOpaque(false);
		results.setVisible(false);
		for (javax.swing.JComponent comp : new javax.swing.JComponent[] { header, results, progressBox, tabs }) {
			JPanel holder = new JPanel(new BorderLayout()) {
				private static final long serialVersionUID = 1L;

				@Override
				public java.awt.Dimension getMaximumSize() {
					return new java.awt.Dimension(Integer.MAX_VALUE, getPreferredSize().height);
				}
			};
			holder.setOpaque(false);
			holder.add(comp, BorderLayout.CENTER);
			holder.setAlignmentX(LEFT_ALIGNMENT);
			content.add(holder);
			if (comp != results) content.add(javax.swing.Box.createVerticalStrut(10)); // results has its own
		}
		content.add(javax.swing.Box.createVerticalGlue());
		JScrollPane scroll = new JScrollPane(content);
		scroll.setBorder(null);
		scroll.getViewport().setBackground(BiaPyFiji.WHITE);
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		add(scroll, BorderLayout.CENTER);

		clock = new Timer(1000, e -> updateTime());
		updateStatus();
	}

	/** A tab's content, at its top on a white background, scrolling when it doesn't fit. */
	private static JScrollPane scrolled(javax.swing.JComponent c) {
		JPanel top = new JPanel(new BorderLayout());
		top.setBackground(BiaPyFiji.WHITE);
		top.add(c, BorderLayout.NORTH);
		JScrollPane sp = new JScrollPane(top);
		sp.setBorder(null);
		sp.getViewport().setBackground(BiaPyFiji.WHITE);
		sp.getVerticalScrollBar().setUnitIncrement(16);
		return sp;
	}

	private static int addPhase(JPanel p, int row, String name, JProgressBar bar, JLabel detail) {
		GridBagConstraints c = new GridBagConstraints();
		c.gridy = row;
		c.insets = new Insets(4, 0, 0, 8);
		c.anchor = GridBagConstraints.WEST;
		JLabel l = new JLabel(name);
		l.setFont(l.getFont().deriveFont(Font.BOLD, 12f));
		l.setPreferredSize(new java.awt.Dimension(70, l.getPreferredSize().height));
		p.add(l, c);
		c.gridx = 1;
		c.weightx = 1;
		c.fill = GridBagConstraints.HORIZONTAL;
		c.insets = new Insets(4, 0, 0, 0);
		bar.setStringPainted(true);
		bar.setForeground(BiaPyFiji.BLUE);
		p.add(bar, c);
		c.gridy = row + 1;
		c.insets = new Insets(0, 0, 2, 0);
		detail.setFont(detail.getFont().deriveFont(Font.PLAIN, 11f));
		detail.setForeground(BiaPyFiji.GREY);
		// No blank row when there is nothing to say
		detail.addPropertyChangeListener("text", e -> {
			boolean shown = detail.getText() != null && !detail.getText().trim().isEmpty();
			if (shown == detail.isVisible()) return;
			detail.setVisible(shown);
			// The progress box changes height: every container up to the tab lays out again (their layouts cache sizes)
			for (java.awt.Container k = p; k != null; k = k.getParent()) k.invalidate();
			p.revalidate();
		});
		detail.setVisible(!detail.getText().trim().isEmpty());
		p.add(detail, c);
		return row + 2;
	}

	public String getJobName() {
		return jobName;
	}

	public Status getStatus() {
		return status;
	}

	public boolean isRunning() {
		return status == Status.RUNNING;
	}

	/** This run's id in {@link RunHistory}. */
	public String getHistoryId() {
		return historyId;
	}

	/** Starts BiaPy in the background, and a watcher of the files it writes. */
	public void start() {
		console.append("$ " + String.join(" ", command));
		historyId = RunHistory.add(historyEntry());
		File out = jobFolder.getParentFile();
		runner = new ProcessRunner(command, PythonEnv.environment(), out != null && out.isDirectory() ? out : null);
		clock.start();
		new Thread(() -> {
			int code = -1;
			try {
				code = runner.run(line -> {
					console.append(line);
					monitor.outputLine(line);
					SwingUtilities.invokeLater(() -> parse(line));
				});
			} catch (Exception e) {
				console.append("ERROR: " + e.getMessage());
			}
			final int fc = code;
			SwingUtilities.invokeLater(() -> finished(fc));
		}, "BiaPy-run-" + jobName).start();
		Thread watcher = new Thread(() -> {
			while (isRunning()) {
				monitor.refresh();
				if (testPredictions != null) testPredictions.refresh();
				try {
					Thread.sleep(3000);
				} catch (InterruptedException e) {
					return;
				}
			}
			monitor.refresh(); // last epoch
			if (testPredictions != null) testPredictions.refresh(); // last images
		}, "BiaPy-watch-" + jobName);
		watcher.setDaemon(true);
		watcher.start();
	}

	public void stop() {
		if (runner != null) runner.cancel();
	}

	/** This run's full log (null until BiaPy creates it). */
	private File logFile() {
		return monitor.getLogFile();
	}

	private void openLog() {
		File f = monitor.getLogFile();
		if (f == null || !f.isFile()) {
			UiUtils.info(this, "BiaPy has not created the log file of this run yet.");
			return;
		}
		try {
			java.awt.Desktop.getDesktop().open(f);
		} catch (Exception e) {
			ij.IJ.open(f.getAbsolutePath()); // ImageJ's text window
		}
	}

	private void finished(int code) {
		endTime = System.currentTimeMillis();
		clock.stop();
		updateTime();
		if (runner.isCancelled()) status = Status.STOPPED;
		else if (code == 0) status = Status.FINISHED;
		else status = Status.FAILED;
		if (status == Status.FINISHED) {
			phaseLabel.setText("Finished");
			phaseLabel.setForeground(BiaPyFiji.GREEN);
			// "And now what?": the button that explains the results lights up until it is clicked
			docs.setToolTipText("The job finished: see what it created and how to look at its results");
			docs.startGlow();
			showResults();
			if (test && testTotal <= 0) {
				testBar.setIndeterminate(false);
				testBar.setMaximum(Math.max(1, testImages));
				testBar.setValue(testBar.getMaximum());
				testBar.setString(testImages + " images");
			}
		} else if (status == Status.FAILED) {
			phaseLabel.setText("BiaPy ended with errors (exit code " + code + "). Check the output below.");
			phaseLabel.setForeground(BiaPyFiji.RED);
			console.setOutputVisible(true); // the error is in the output
		} else {
			phaseLabel.setText("Stopped by the user");
			phaseLabel.setForeground(BiaPyFiji.GREY);
		}
		if (testBar.isIndeterminate()) testBar.setIndeterminate(false);
		// Stop stays, disabled and gray
		stopBtn.setEnabled(false);
		stopBtn.setBackground(new Color(200, 200, 200));
		stopBtn.setCursor(java.awt.Cursor.getDefaultCursor());
		updateStatus();
		relayout();
		RunHistory.update(historyId, r -> {
			r.addProperty("status", status.name());
			r.addProperty("ended", endTime);
			// Epochs completed (the bar is full once training ends, also when it stops early)
			if (train) r.addProperty("epochs", status == Status.FINISHED ? currentEpoch : trainBar.getValue());
			com.google.gson.JsonArray res = new com.google.gson.JsonArray();
			for (String l : resultLines) res.add(l);
			r.add("results", res);
		});
	}

	/**
	 * The training metrics of the epoch kept and the test metrics (when the test images have ground truth), read from
	 * the files of this run.
	 */
	private void showResults() {
		File log = train ? monitor.getTrainingLog() : null;
		String trainHtml = log != null && log.isFile() ? ResultsBox.trainingHtml(ResultsBox.training(log)) : null;
		File resultsDir = new File(new File(jobFolder, "results"), jobName + "_" + RUN_ID);
		File csv = new File(resultsDir, "test_results_metrics.csv");
		// A previous run of the job may have left one
		boolean fresh = test && csv.isFile() && csv.lastModified() >= startTime;
		String testHtml = fresh ? ResultsBox.testHtml(ResultsBox.test(csv), testImages) : null;
		File predictions = test ? predictionsOf(resultsDir) : null;
		if (trainHtml == null && testHtml == null && predictions == null) return;
		JPanel body = new JPanel();
		body.setOpaque(false);
		body.setLayout(new javax.swing.BoxLayout(body, javax.swing.BoxLayout.Y_AXIS));
		if (trainHtml != null || testHtml != null) {
			javax.swing.JTabbedPane metrics = new javax.swing.JTabbedPane();
			metrics.setFont(metrics.getFont().deriveFont(Font.PLAIN, 12f));
			if (trainHtml != null) metrics.addTab("Training / validation", metricsTab(trainHtml));
			if (testHtml != null) metrics.addTab("Test", metricsTab(testHtml));
			metrics.setAlignmentX(LEFT_ALIGNMENT);
			body.add(metrics);
		}
		if (predictions != null) {
			JButton open = UiUtils.secondaryButton(predictionsLabel(), "Open " + predictions);
			open.addActionListener(e -> UiUtils.openFolder(predictions));
			JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
			row.setOpaque(false);
			row.add(open);
			row.setAlignmentX(LEFT_ALIGNMENT);
			body.add(javax.swing.Box.createVerticalStrut(8));
			body.add(row);
		}
		JLabel next = new JLabel("<html>Click <b>Workflow info</b> (above) to understand the results: what each folder "
				+ "holds, how to look at them in Fiji and the next steps.</html>");
		next.setFont(next.getFont().deriveFont(Font.PLAIN, 12f));
		next.setForeground(BiaPyFiji.GREY);
		next.setAlignmentX(LEFT_ALIGNMENT);
		body.add(javax.swing.Box.createVerticalStrut(6));
		body.add(next);
		JLabel title = new JLabel("Results");
		title.setFont(title.getFont().deriveFont(Font.BOLD, 13f));
		title.setForeground(BiaPyFiji.GREEN);
		JPanel box = new JPanel(new BorderLayout(0, 4));
		box.setOpaque(false);
		box.add(title, BorderLayout.NORTH);
		box.add(body, BorderLayout.CENTER);
		box.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(BiaPyFiji.GREEN, 2, true),
				BorderFactory.createEmptyBorder(8, 10, 8, 10)));
		results.removeAll();
		results.add(box, BorderLayout.CENTER);
		results.setBorder(BorderFactory.createEmptyBorder(0, 0, 10, 0));
		results.setVisible(true);
	}

	private static JPanel metricsTab(String html) {
		JLabel l = new JLabel("<html>" + html + "</html>");
		l.setFont(l.getFont().deriveFont(Font.PLAIN, 12f));
		JPanel p = new JPanel(new BorderLayout());
		p.setBackground(BiaPyFiji.WHITE);
		p.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
		p.add(l, BorderLayout.NORTH);
		return p;
	}

	/** The final output of the test: tracks, the predictions table (classification) or the most final image folder. */
	private File predictionsOf(File resultsDir) {
		File tracks = new File(resultsDir, "tracking");
		if (Boolean.TRUE.equals(get(cfg, "PROBLEM", "TRACKING", "ENABLE")) && tracks.isDirectory()) return tracks;
		if ("CLASSIFICATION".equals(type())) return resultsDir.isDirectory() ? resultsDir : null;
		return TestPredictions.finalOutput(resultsDir);
	}

	private String predictionsLabel() {
		if (Boolean.TRUE.equals(get(cfg, "PROBLEM", "TRACKING", "ENABLE"))) return "Tracked objects";
		switch (type()) {
		case "SEMANTIC_SEG": return "Predicted masks";
		case "INSTANCE_SEG": return "Predicted instances";
		case "DETECTION": return "Detected points";
		case "DENOISING": return "Denoised images";
		case "SUPER_RESOLUTION": return "High-resolution images";
		case "CLASSIFICATION": return "Predicted classes";
		default: return "Predicted images";
		}
	}

	private String type() {
		return str(get(cfg, "PROBLEM", "TYPE"), "SEMANTIC_SEG");
	}

	/** What the History page shows of this run (updated when it ends). */
	private com.google.gson.JsonObject historyEntry() {
		com.google.gson.JsonObject r = new com.google.gson.JsonObject();
		r.addProperty("name", jobName);
		r.addProperty("workflow", str(get(cfg, "PROBLEM", "TYPE"), "SEMANTIC_SEG"));
		r.addProperty("workflow_text", workflowName(cfg));
		String model = modelText(cfg);
		if (model != null) r.addProperty("biapy".equals(str(get(cfg, "MODEL", "SOURCE"), "biapy")) ? "checkpoint" : "model", model);
		r.addProperty("phases", train && test ? "Training and test" : train ? "Training" : test ? "Test" : "None");
		if (train) r.addProperty("epochs_total", trainBar.getMaximum());
		r.addProperty("config", configFile.getAbsolutePath());
		r.addProperty("folder", jobFolder.getAbsolutePath());
		r.addProperty("device", device);
		r.addProperty("biapy_version", biapyVersion);
		r.addProperty("started", startTime);
		r.addProperty("status", status.name());
		return r;
	}

	/** Some updates change the height of the progress box (e.g. the metrics line): lay the tab out again. */
	private void relayout() {
		for (java.awt.Container c = metricsLabel.getParent(); c != null && c != content; c = c.getParent()) c.invalidate();
		content.invalidate();
		content.revalidate();
		content.repaint();
	}

	private void updateStatus() {
		statusLabel.setText("\u25CF " + status.label);
		statusLabel.setForeground(status.color);
		// The row's layout keeps the sizes of its labels: "Finished" is wider than "Running"
		if (statusLabel.getParent() != null) statusLabel.getParent().invalidate();
		onStatusChange.accept(this);
	}

	private void updateTime() {
		long ms = (endTime > 0 ? endTime : System.currentTimeMillis()) - startTime;
		long s = ms / 1000;
		timeLabel.setText(String.format("%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60));
	}

	private void setTrainDone(int epochs) {
		trainBar.setValue(Math.min(epochs, trainBar.getMaximum()));
		trainBar.setString(epochs + " / " + trainBar.getMaximum() + " epochs");
	}

	/** Updates the progress view from one line of BiaPy output. */
	private void parse(String raw) {
		String line = TIMESTAMP.matcher(raw).replaceFirst("").trim();
		Matcher m;
		if ((m = PHASE.matcher(line)).find()) {
			String phase = m.group(1).trim();
			inResults = phase.equals("RESULTS");
			phaseLabel.setText(capitalize(phase));
			if (inResults && test && testTotal > 0) {
				testBar.setValue(testBar.getMaximum());
				testBar.setString(testTotal + " / " + testTotal + " images");
			}
			return;
		}
		if (train && (m = EPOCH.matcher(line)).find()) {
			currentEpoch = Integer.parseInt(m.group(1));
			setTrainDone(currentEpoch - 1);
			trainDetail.setText("Epoch " + currentEpoch + " running");
			return;
		}
		if (train && (m = ITER.matcher(line)).find()) {
			trainDetail.setText("Epoch " + m.group(1) + "  ·  iteration " + m.group(2) + " of " + m.group(3)
					+ "  ·  epoch ETA " + m.group(4));
			return;
		}
		if ((m = STATS.matcher(line)).find()) {
			String text = "Last " + (m.group(1).equals("Val") ? "validation" : "training") + ": " + m.group(2);
			boolean lines = !text.equals(metricsLabel.getText());
			metricsLabel.setText(text);
			if (!metricsLabel.isVisible() || lines) {
				metricsLabel.setVisible(true);
				relayout(); // its height may change with the text
			}
			if (train && currentEpoch > 0) setTrainDone(currentEpoch);
			return;
		}
		if (train && line.startsWith("Finished Training")) {
			// Also covers early stopping, which ends before TRAIN.EPOCHS
			trainBar.setValue(trainBar.getMaximum());
			trainBar.setString("Done (" + currentEpoch + " epochs)");
			trainDetail.setText(currentEpoch < trainBar.getMaximum() ? "Stopped early (patience reached)" : " ");
			return;
		}
		if (test && (m = IMAGE.matcher(line)).find()) {
			if (!testStarted) {
				testStarted = true;
				if (testTotal <= 0) testBar.setIndeterminate(true);
			}
			testImages++;
			if (testTotal > 0) {
				testBar.setValue(Math.min(testImages, testTotal));
				testBar.setString(testImages + " / " + testTotal + " images");
			}
			testDetail.setText("Processing " + m.group(1));
			return;
		}
		// Final metrics printed after the RESULTS banner, until the works to cite (the job's "Cite" link lists them)
		if (inResults && line.startsWith("If you use these results")) inResults = false;
		if (inResults && !line.isEmpty() && line.contains(":") && !line.startsWith("The values below")) resultLines.add(line);
	}

	private static String capitalize(String s) {
		String l = s.toLowerCase();
		return Character.toUpperCase(l.charAt(0)) + l.substring(1);
	}

	////////////////////////////
	// Configuration summary
	////////////////////////////
	private static final Map<String, String> WORKFLOWS = new HashMap<>();
	static {
		WORKFLOWS.put("SEMANTIC_SEG", "Semantic segmentation");
		WORKFLOWS.put("INSTANCE_SEG", "Instance segmentation");
		WORKFLOWS.put("DETECTION", "Detection");
		WORKFLOWS.put("DENOISING", "Denoising");
		WORKFLOWS.put("SUPER_RESOLUTION", "Super-resolution");
		WORKFLOWS.put("SELF_SUPERVISED", "Self-supervised learning");
		WORKFLOWS.put("CLASSIFICATION", "Classification");
		WORKFLOWS.put("IMAGE_TO_IMAGE", "Image to image");
	}

	private static Map<?, ?> readConfig(File configFile) {
		try (java.io.Reader r = new java.io.InputStreamReader(new java.io.FileInputStream(configFile),
				java.nio.charset.StandardCharsets.UTF_8)) {
			Object root = new org.yaml.snakeyaml.Yaml().load(r);
			return root instanceof Map ? (Map<?, ?>) root : new HashMap<>();
		} catch (Exception e) {
			return new HashMap<>();
		}
	}

	/** BiaPy's documentation of a workflow (PROBLEM.TYPE). */
	static String docsUrl(String type) {
		String page;
		switch (type) {
		case "INSTANCE_SEG": page = "instance_segmentation"; break;
		case "DETECTION": page = "detection"; break;
		case "DENOISING": page = "denoising"; break;
		case "SUPER_RESOLUTION": page = "super_resolution"; break;
		case "SELF_SUPERVISED": page = "self_supervision"; break;
		case "CLASSIFICATION": page = "classification"; break;
		case "IMAGE_TO_IMAGE": page = "image_to_image"; break;
		default: page = "semantic_segmentation";
		}
		return BiaPyFiji.URL_DOCS + "workflows/" + page + ".html";
	}

	/** "<b>Instance segmentation - 2D</b>". Missing keys take BiaPy's defaults. */
	static String workflowText(Map<?, ?> cfg) {
		return "<b>" + workflowName(cfg) + "</b>";
	}

	/** "Instance segmentation - 2D". */
	static String workflowName(Map<?, ?> cfg) {
		String type = str(get(cfg, "PROBLEM", "TYPE"), "SEMANTIC_SEG");
		return WORKFLOWS.getOrDefault(type, type) + " - " + str(get(cfg, "PROBLEM", "NDIM"), "2D");
	}

	/** The pretrained model or checkpoint the job starts from ("poisonous-spider (BioImage Model Zoo)"), or null. */
	static String modelText(Map<?, ?> cfg) {
		String source = str(get(cfg, "MODEL", "SOURCE"), "biapy");
		if (source.equals("bmz")) return str(get(cfg, "MODEL", "BMZ", "SOURCE_MODEL_ID"), "?") + " (BioImage Model Zoo)";
		if (source.equals("torchvision")) return str(get(cfg, "MODEL", "TORCHVISION_MODEL_NAME"), "?") + " (Torchvision)";
		if (bool(get(cfg, "MODEL", "LOAD_CHECKPOINT")))
			return str(get(cfg, "PATHS", "CHECKPOINT_FILE"), "the best checkpoint in the job's 'checkpoints' folder");
		return null;
	}

	/** Row under "Workflow" with the pretrained model or the checkpoint loaded, if any. */
	private static JPanel modelRow(Map<?, ?> cfg) {
		String model = modelText(cfg);
		if (model == null) return null;
		if (!"biapy".equals(str(get(cfg, "MODEL", "SOURCE"), "biapy"))) return infoRow("Pretrained model", model);
		return get(cfg, "PATHS", "CHECKPOINT_FILE") != null ? pathRow("Checkpoint", model) : infoRow("Checkpoint", model);
	}

	/**
	 * Number of images in DATA.TEST.PATH (in class subfolders for classification), or -1 when it cannot be
	 * known beforehand (e.g. Zarr/H5 files), in which case the test bar just shows the images processed.
	 */
	private static int countTestImages(Map<?, ?> cfg) {
		Object p = get(cfg, "DATA", "TEST", "PATH");
		if (p == null) return -1;
		File dir = new File(p.toString());
		File[] files = dir.listFiles(f -> !f.getName().startsWith("."));
		if (files == null) return -1;
		int n = 0;
		boolean subdirs = false;
		for (File f : files) {
			if (f.isFile()) n++;
			else if (f.isDirectory()) {
				if (f.getName().endsWith(".zarr") || f.getName().endsWith(".n5")) return -1;
				subdirs = true;
			}
		}
		if (n == 0 && subdirs) {
			for (File d : files) {
				File[] inner = d.listFiles(f -> f.isFile() && !f.getName().startsWith("."));
				if (inner != null) n += inner.length;
			}
		}
		return n > 0 ? n : -1;
	}

	private static Object get(Map<?, ?> m, String... keys) {
		Object cur = m;
		for (String k : keys) {
			if (!(cur instanceof Map)) return null;
			cur = ((Map<?, ?>) cur).get(k);
		}
		return cur;
	}

	private static String str(Object o, String def) {
		return o == null || o.toString().isEmpty() ? def : o.toString();
	}

	private static boolean bool(Object o) {
		return o != null && Boolean.parseBoolean(o.toString());
	}

	/** A path, shortened from its start ("...folder/file") when it doesn't fit; the whole path is the tooltip. */
	/** "Cite:" with a link that lists the works to cite for the job (BiaPy, its model, the methods it uses...). */
	private JPanel citeRow(File configFile) {
		JPanel row = infoRow("Cite", " ");
		row.remove(1);
		JLabel link = UiUtils.link("BiaPy and the works this job builds on",
				() -> CitationsDialog.show(this, configFile, jobName));
		link.setFont(link.getFont().deriveFont(Font.PLAIN, 12f));
		link.setToolTipText("The papers to cite if you use the results of this job, and why each of them");
		// Kept at its own width: in CENTER the label (and its click area) would fill the rest of the row
		JPanel holder = new JPanel(new BorderLayout());
		holder.setOpaque(false);
		holder.add(link, BorderLayout.WEST);
		row.add(holder, BorderLayout.CENTER);
		return row;
	}

	private static JPanel pathRow(String label, String path) {
		JPanel row = infoRow(label, " ");
		row.remove(1);
		row.add(new StartEllipsisLabel(path), BorderLayout.CENTER);
		return row;
	}

	/** Text that, when it doesn't fit, keeps its end: "...results/my_job". */
	static final class StartEllipsisLabel extends javax.swing.JComponent {
		private static final long serialVersionUID = 1L;
		private final String text;

		StartEllipsisLabel(String text) {
			this.text = text;
			setFont(new JLabel().getFont().deriveFont(Font.PLAIN, 12f));
			setToolTipText(text);
			java.awt.FontMetrics fm = getFontMetrics(getFont());
			// Small preferred width: the layout gives it what is left of the column
			setPreferredSize(new java.awt.Dimension(60, fm.getHeight()));
		}

		@Override
		protected void paintComponent(java.awt.Graphics g0) {
			java.awt.Graphics2D g = (java.awt.Graphics2D) g0.create();
			g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			g.setFont(getFont());
			g.setColor(getForeground());
			java.awt.FontMetrics fm = g.getFontMetrics();
			String s = text;
			if (fm.stringWidth(s) > getWidth()) {
				int k = 0;
				while (k < s.length() && fm.stringWidth("..." + s.substring(k)) > getWidth()) k++;
				s = "..." + s.substring(k);
			}
			g.drawString(s, 0, (getHeight() + fm.getAscent() - fm.getDescent()) / 2);
			g.dispose();
		}
	}

	/** Two columns sharing the width in a fixed ratio, both as tall as the taller. */
	private static final class Columns extends JPanel {
		private static final long serialVersionUID = 1L;
		private final java.awt.Component left, right;
		private final double ratio;
		private final int gap;

		Columns(java.awt.Component left, java.awt.Component right, double ratio, int gap) {
			super(null);
			this.left = left;
			this.right = right;
			this.ratio = ratio;
			this.gap = gap;
			setOpaque(false);
			add(left);
			add(right);
		}

		@Override
		public java.awt.Dimension getPreferredSize() {
			java.awt.Dimension l = left.getPreferredSize(), r = right.getPreferredSize();
			Insets in = getInsets();
			return new java.awt.Dimension(l.width + gap + r.width + in.left + in.right,
					Math.max(l.height, r.height) + in.top + in.bottom);
		}

		@Override
		public void doLayout() {
			Insets in = getInsets();
			int w = getWidth() - in.left - in.right, h = getHeight() - in.top - in.bottom;
			int lw = (int) Math.round((w - gap) * ratio);
			left.setBounds(in.left, in.top, lw, h);
			right.setBounds(in.left + lw + gap, in.top, w - lw - gap, h);
		}
	}

	/**
	 * "Label: value" row. Values are plain text (never wrap: a long text ends in "..." and is complete in the
	 * tooltip), except the short workflow summary, which uses HTML for the bold part.
	 */
	private static JPanel infoRow(String label, String value) {
		JPanel row = new JPanel(new BorderLayout(4, 0));
		row.setOpaque(false);
		JLabel name = new JLabel(label + ":");
		name.setFont(name.getFont().deriveFont(Font.PLAIN, 12f));
		name.setForeground(new Color(119, 119, 119));
		JLabel val = new JLabel(value.contains("<") ? "<html>" + value + "</html>" : value);
		val.setFont(val.getFont().deriveFont(Font.PLAIN, 12f));
		val.setToolTipText(value.replaceAll("<[^>]+>", "").replace("&nbsp;", " "));
		row.add(name, BorderLayout.WEST);
		row.add(val, BorderLayout.CENTER);
		row.setBorder(BorderFactory.createEmptyBorder(1, 0, 1, 0));
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
		return row;
	}
}
