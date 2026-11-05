package io.github.biapyx.fiji.gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.github.biapyx.fiji.BiaPyFiji;

/**
 * Follows the files BiaPy writes while training: the training log (one JSON line per epoch) for the loss
 * and metric charts, and the TRAIN.SAVE_TRAIN_PREDS_FREQ previews (input, ground truth and the latest
 * predictions, laid out as samples.json describes, see {@link TrainingPreview}). Several runs with the same
 * job name share the "logs" and "train_pred_samples" folders, so only files created by this run are used.
 */
public class TrainingMonitor extends JPanel {

	private static final long serialVersionUID = 1L;
	private static final int TILE = 96;
	/** How many of the latest predictions are shown. */
	private static final int MAX_PREDS = 5;
	/** How many of the tracked samples are shown (image workflows). */
	private static final int MAX_SAMPLES = 3;

	private final File logsDir;
	private File previewDir;
	private final String jobId;
	/** The preview files of previous runs with the same job name (BiaPy overwrites them as this run goes). */
	private final FileSnapshot before;
	private final boolean previews;
	private final Set<String> logsBefore = new HashSet<>();

	private volatile File logFile;
	private File trainingLog;
	private long trainingLogModified = -1;
	private final Map<String, Long> shown = new LinkedHashMap<>();

	/** The loss chart, then one per metric (per channel in instance segmentation), as BiaPy logs them. */
	private final JPanel charts = new JPanel(new GridLayout(1, 1, 10, 10));
	private final Map<String, LineChart> chartsByName = new LinkedHashMap<>();
	private static final int CHART_HEIGHT = 210;
	private static final int CHARTS_PER_ROW = 3;
	private static final Color TRAIN_COLOR = BiaPyFiji.BLUE, VAL_COLOR = new Color(240, 130, 30);
	/** Loss name BiaPy prints ("Loss function: Dice + cross-entropy"), null until found in the log. */
	private volatile String lossName;
	private static final java.util.regex.Pattern LOSS_LINE = java.util.regex.Pattern.compile("Loss function: (.+)$");
	/** Frame of the prediction tiles. */
	private static final Color TILE_FRAME = new Color(190, 190, 190);
	private final JPanel grid = new JPanel(new GridBagLayout());
	private final JPanel sampleBar = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0));
	/** What samples.json describes (null until BiaPy writes it). */
	private volatile TrainingPreview preview;
	/** Rendered panels per file ("input/0.tif", "pred_epoch_0005/0.tif") and the file times they come from. */
	private final Map<String, List<ImageIcon>> icons = new ConcurrentHashMap<>();
	private final Map<String, Long> iconTimes = new ConcurrentHashMap<>();
	/** Classification: predicted class and its score per epoch folder and sample. */
	private final Map<String, double[][]> scores = new ConcurrentHashMap<>();
	private String shownState = "";
	private JComponent predictionsView, chartsView;
	/** Why the predictions can't be shown, above them (hidden when there is no problem). */
	private final JLabel problem = new JLabel();

	private void showProblem(String text) {
		SwingUtilities.invokeLater(() -> {
			problem.setText("<html>" + text.replace("&", "&amp;").replace("<", "&lt;") + "</html>");
			problem.setVisible(true);
		});
	}

	/**
	 * A line of BiaPy's output: its warnings about the training predictions (e.g. when it can't save them) are shown
	 * in the predictions tab.
	 */
	public void outputLine(String line) {
		if (previews && line.contains("WARNING") && (line.contains("SAVE_TRAIN_PREDS") || line.contains("training prediction")))
			showProblem("BiaPy: " + line.replaceFirst("^\\[[^\\]]*\\]\\s*", ""));
	}

	/**
	 * @param jobFolder the job folder (output folder / job name)
	 * @param jobId BiaPy's job identifier, i.e. "jobname_runid"
	 * @param training whether the job trains (charts and previews are only for training)
	 * @param previews whether TRAIN.SAVE_TRAIN_PREDS_FREQ is enabled in the configuration
	 * @param noise2void whether the job is a Noise2Void denoising (its help explains it)
	 */
	public TrainingMonitor(File jobFolder, String jobId, boolean training, boolean previews, boolean noise2void) {
		super(new BorderLayout(0, 8));
		this.logsDir = new File(jobFolder, "logs");
		this.previewDir = new File(new File(new File(jobFolder, "results"), jobId), "train_pred_samples");
		this.jobId = jobId;
		this.before = new FileSnapshot(previewDir);
		this.previews = previews;
		setOpaque(false);
		// Logs already there belong to previous runs with the same name
		String[] existing = logsDir.list();
		if (existing != null) for (String f : existing) logsBefore.add(f);

		if (!training) {
			setVisible(false);
			return;
		}
		JPanel previewRow = new JPanel(new BorderLayout(0, 4));
		previewRow.setOpaque(false);
		if (previews) {
			// Sample selector, then a grid: a row per panel (e.g. a channel), input | ground truth | latest
			// predictions (newest first)
			JPanel box = new JPanel(new BorderLayout(0, 6));
			box.setOpaque(false);
			sampleBar.setOpaque(false);
			grid.setOpaque(false);
			grid.add(tile());
			JScrollPane scroll = new JScrollPane(grid, JScrollPane.VERTICAL_SCROLLBAR_NEVER,
					JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED) {
				private static final long serialVersionUID = 1L;

				@Override
				public Dimension getPreferredSize() {
					// As tall as the grid, plus the scroll bar when it is wider than the space available
					Dimension d = grid.getPreferredSize();
					boolean bar = getWidth() > 0 && d.width > getViewport().getWidth();
					return new Dimension(100, d.height + (bar ? getHorizontalScrollBar().getPreferredSize().height : 0) + 2);
				}
			};
			scroll.setBorder(null);
			scroll.setOpaque(false);
			scroll.getViewport().setOpaque(false);
			scroll.getHorizontalScrollBar().setUnitIncrement(16);
			scroll.addComponentListener(new java.awt.event.ComponentAdapter() {
				@Override
				public void componentResized(java.awt.event.ComponentEvent e) {
					scroll.revalidate();
				}
			});
			box.add(sampleBar, BorderLayout.NORTH);
			box.add(scroll, BorderLayout.CENTER);
			previewRow.add(box, BorderLayout.CENTER);
			problem.setForeground(BiaPyFiji.RED);
			problem.setFont(problem.getFont().deriveFont(Font.PLAIN, 12f));
			problem.setVisible(false);
			JPanel top = new JPanel(new BorderLayout(0, 4));
			top.setOpaque(false);
			// Level with the sample bar's text (its FlowLayout's gap)
			top.add(helpRow(6, "Want to know which samples these are, or how to follow more of them?",
					"About these predictions", "Which samples these are, what to look for and how to configure them",
					b -> TrainingHelp.predictions(b, MAX_SAMPLES, noise2void)), BorderLayout.NORTH);
			top.add(problem, BorderLayout.SOUTH);
			previewRow.add(top, BorderLayout.NORTH);
		} else {
			WrappingTextArea hint = new WrappingTextArea("The training predictions are not enabled in the configuration "
					+ "(TRAIN.SAVE_TRAIN_PREDS_FREQ). With them, this tab shows how the model predicts a few samples as it "
					+ "trains.");
			hint.setForeground(BiaPyFiji.GREY);
			hint.setFont(new JLabel().getFont().deriveFont(Font.PLAIN, 12f));
			hint.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
			previewRow.add(hint, BorderLayout.NORTH);
			previewRow.add(helpRow(0, null, "How to enable them", "What the training predictions show and how to enable them",
					b -> TrainingHelp.predictions(b, MAX_SAMPLES, noise2void)), BorderLayout.CENTER);
		}
		previewRow.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		predictionsView = previewRow;

		charts.setOpaque(false);
		LineChart loss = new LineChart("Loss");
		loss.setInfo(MetricInfo.describeLoss(null));
		chartsByName.put("loss", loss);
		charts.add(loss);
		charts.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		charts.setPreferredSize(new Dimension(0, CHART_HEIGHT + 16));
		// Above the charts: where to learn how to read them
		JPanel help = helpRow(8, "Want to understand these curves, e.g. whether the model needs more training or is overfitting?",
				"How to read them", "What the loss and the metrics show, and what to change when the training does not go well",
				b -> TrainingHelp.curves(b, noise2void));
		help.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 0)); // level with the charts' titles
		JPanel chartsBox = new JPanel(new BorderLayout());
		chartsBox.setOpaque(false);
		chartsBox.add(help, BorderLayout.NORTH);
		chartsBox.add(charts, BorderLayout.CENTER);
		chartsView = chartsBox;
	}

	/** A grey question (if any) and a button that opens its explanation, on a row, {@code gap} pixels apart. */
	private static JPanel helpRow(int gap, String question, String button, String tooltip,
			java.util.function.Consumer<javax.swing.JButton> open) {
		JPanel row = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, gap, 0));
		row.setOpaque(false);
		if (question != null) {
			JLabel q = new JLabel(question);
			q.setFont(q.getFont().deriveFont(Font.PLAIN, 12f));
			q.setForeground(BiaPyFiji.GREY);
			row.add(q);
		}
		javax.swing.JButton b = UiUtils.secondaryButton(button, tooltip);
		b.addActionListener(e -> open.accept(b));
		row.add(b);
		return row;
	}

	/** The latest predictions of the training samples (shown by the job panel, e.g. in a tab). */
	public JComponent predictionsView() {
		return predictionsView;
	}

	/** The loss chart and one chart per metric. */
	public JComponent chartsView() {
		return chartsView;
	}


	/** Full text log of this run (null until BiaPy creates it). */
	public File getLogFile() {
		if (logFile == null) findLogFiles();
		return logFile;
	}

	/** Reads the files written by BiaPy since the last call. Call it from a background thread. */
	public void refresh() {
		findLogFiles();
		if (!isVisible()) return;
		findLossName();
		refreshCharts();
		if (previews) {
			try {
				refreshPreviews();
			} catch (Throwable t) {
				// Shown in the tab instead of an empty grid, and logged for a bug report
				ij.IJ.log("[BiaPy] Could not show the training predictions: " + t);
				java.io.StringWriter sw = new java.io.StringWriter();
				t.printStackTrace(new java.io.PrintWriter(sw));
				ij.IJ.log(sw.toString());
				showProblem("Could not show the training predictions: " + t);
			}
		}
	}

	/** This run's log is the new "<jobId>_log_<runId>_<timestamp>.txt"; its timestamp names the JSON log. */
	private void findLogFiles() {
		if (logFile != null) return;
		File[] files = logsDir.listFiles();
		if (files == null) return;
		for (File f : files) {
			String n = f.getName();
			if (logsBefore.contains(n) || !n.startsWith(jobId + "_log_") || !n.endsWith(".txt")) continue;
			String rest = n.substring((jobId + "_log_").length(), n.length() - 4); // "<runId>_<timestamp>"
			int us = rest.indexOf('_');
			if (us < 0) continue;
			logFile = f;
			trainingLog = new File(logsDir, jobId + "_log_" + rest.substring(us + 1) + "_training_log.json");
			return;
		}
	}

	private void refreshCharts() {
		if (trainingLog == null || !trainingLog.isFile() || trainingLog.lastModified() == trainingLogModified) return;
		trainingLogModified = trainingLog.lastModified();
		Map<String, List<Double>> values = new LinkedHashMap<>();
		try {
			for (String line : Files.readAllLines(trainingLog.toPath(), StandardCharsets.UTF_8)) {
				if (line.trim().isEmpty()) continue;
				JsonObject o = JsonParser.parseString(line).getAsJsonObject();
				for (Map.Entry<String, JsonElement> e : o.entrySet()) {
					if (e.getKey().equals("epoch") || !e.getValue().isJsonPrimitive()) continue;
					values.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue().getAsDouble());
				}
			}
		} catch (Exception e) {
			return; // probably being written; next refresh
		}
		// A chart per quantity, "train_X" and "test_X" in it (BiaPy calls validation "test" in this file); the
		// loss first, the learning rate is not plotted
		Map<String, List<LineChart.Series>> byChart = new LinkedHashMap<>();
		byChart.put("loss", new ArrayList<>());
		for (String key : values.keySet()) {
			if (!key.startsWith("train_")) continue;
			String name = key.substring(6);
			if (name.equals("lr")) continue;
			List<LineChart.Series> s = byChart.computeIfAbsent(name, k -> new ArrayList<>());
			LineChart.Series train = new LineChart.Series("train", TRAIN_COLOR, false);
			train.values.addAll(values.get(key));
			s.add(train);
			List<Double> val = values.get("test_" + name);
			if (val != null) {
				LineChart.Series v = new LineChart.Series("val", VAL_COLOR, true);
				v.values.addAll(val);
				s.add(v);
			}
		}
		SwingUtilities.invokeLater(() -> showCharts(byChart));
	}

	/** Updates the charts, adding one for each new quantity. */
	private void showCharts(Map<String, List<LineChart.Series>> byChart) {
		if (!chartsByName.keySet().containsAll(byChart.keySet())) {
			for (String name : byChart.keySet())
				chartsByName.computeIfAbsent(name, n -> {
					LineChart c = new LineChart(n);
					c.setInfo(MetricInfo.describe(n));
					return c;
				});
			charts.removeAll();
			int n = chartsByName.size(), cols = Math.min(CHARTS_PER_ROW, n), rows = (n + cols - 1) / cols;
			charts.setLayout(new GridLayout(rows, cols, 10, 10));
			for (LineChart c : chartsByName.values()) charts.add(c);
			charts.setPreferredSize(new Dimension(0, rows * CHART_HEIGHT + 16));
			charts.revalidate();
		}
		for (Map.Entry<String, List<LineChart.Series>> e : byChart.entrySet())
			chartsByName.get(e.getKey()).setSeries(e.getValue());
		String loss = lossName;
		if (loss != null) chartsByName.get("loss").setTitle("Loss: " + loss);
		chartsByName.get("loss").setInfo(MetricInfo.describeLoss(loss));
	}

	/** The loss BiaPy uses, from the line it prints when it builds it (BiaPy versions before it don't). */
	private void findLossName() {
		if (lossName != null || logFile == null || !logFile.isFile()) return;
		try (java.io.BufferedReader r = Files.newBufferedReader(logFile.toPath(), StandardCharsets.UTF_8)) {
			String line;
			while ((line = r.readLine()) != null) {
				java.util.regex.Matcher m = LOSS_LINE.matcher(line.trim());
				if (m.find()) {
					lossName = m.group(1).trim();
					String name = lossName;
					SwingUtilities.invokeLater(() -> {
						chartsByName.get("loss").setTitle("Loss: " + name);
						chartsByName.get("loss").setInfo(MetricInfo.describeLoss(name));
					});
					return;
				}
			}
		} catch (Exception e) {
			// Being written, or not UTF-8: next refresh
		}
	}

	/** Renders again every image shown (e.g. with another contrast). */
	private synchronized void rerender() {
		icons.clear();
		iconTimes.clear();
		shownState = "";
		refreshPreviews();
	}

	private synchronized void refreshPreviews() {
		if (preview == null && !readPreview()) return;
		TrainingPreview p = preview;
		// Every tracked sample, one under the other
		int shownSample = 0;
		List<Integer> wanted = new ArrayList<>();
		for (int i = 0; i < Math.min(p.samples.size(), MAX_SAMPLES); i++) wanted.add(i);

		StringBuilder state = new StringBuilder().append(shownSample);
		for (int i : wanted) {
			String f = p.samples.get(i).file;
			state.append(load("input", f, p.input)).append(load("gt", f, p.gt));
		}

		// Latest predictions of this run (older "pred_epoch_*" folders may come from a previous run)
		File[] dirs = previewDir.listFiles((d, n) -> n.startsWith("pred_epoch_"));
		List<String> epochs = new ArrayList<>();
		if (dirs != null) {
			List<File> recent = new ArrayList<>();
			// By the files in them: when a job name is reused, BiaPy overwrites the files of the existing folders,
			// which keeps the folders' own modification times
			for (File d : dirs) if (before.hasNew(d)) recent.add(d);
			recent.sort((x, y) -> y.getName().compareTo(x.getName()));
			for (File d : recent) {
				if (epochs.size() == MAX_PREDS) break;
				boolean ready = p.classification ? readScores(d) : true;
				if (!p.classification)
					for (int i : wanted) ready &= load(d.getName(), p.samples.get(i).file, p.pred) != '-';
				if (ready) epochs.add(d.getName());
			}
		}
		state.append(epochs);
		if (state.toString().equals(shownState)) return;
		shownState = state.toString();
		SwingUtilities.invokeLater(() -> showGrid(p, shownSample, epochs));
	}

	/**
	 * Reads samples.json once BiaPy has written it for this run. Older BiaPy versions write it without
	 * panels, or not at all: then the first channel of "0.tif" is shown, once predictions appear.
	 */
	private boolean readPreview() {
		File f = new File(previewDir, "samples.json");
		TrainingPreview p = null;
		if (before.isNew(f)) {
			try {
				p = TrainingPreview.parse(JsonParser.parseString(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8))
						.getAsJsonObject());
			} catch (Exception e) {
				return false; // being written: next refresh
			}
		} else if (!f.exists()) {
			// BiaPy versions that don't write samples.json: once this run's first predictions are there
			File[] dirs = previewDir.listFiles((d, n) -> n.startsWith("pred_epoch_"));
			boolean ours = false;
			if (dirs != null) for (File d : dirs) ours |= before.hasNew(d);
			if (!ours) return false;
			p = TrainingPreview.legacy(java.util.Collections.singletonList("0.tif"));
		} else {
			return false; // samples.json of a previous run with the same job name: BiaPy rewrites it soon
		}
		if (p.samples.isEmpty()) return false;
		preview = p;
		TrainingPreview fp = p;
		SwingUtilities.invokeLater(() -> showSampleBar(fp));
		return true;
	}

	/**
	 * Renders "<folder>/<file>" into its panels when it is new or changed. Returns 'y' if rendered, 'n' if
	 * there is nothing to show (no panels) and '-' if not available yet.
	 */
	private char load(String folder, String file, List<TrainingPreview.Panel> panels) {
		if (panels.isEmpty()) return 'n';
		String key = folder + "/" + file;
		File f = new File(new File(previewDir, folder), file);
		if (!before.isNew(f)) return '-';
		Long last = iconTimes.get(key);
		if (last != null && last == f.lastModified()) return 'y';
		List<ImageIcon> rendered = preview.render(f, panels, TILE);
		if (rendered == null) return icons.containsKey(key) ? 'y' : '-';
		icons.put(key, rendered);
		iconTimes.put(key, f.lastModified());
		// A changed file must change the state so the grid is rebuilt
		shownState = "";
		return 'y';
	}

	/** Classification: reads "<epoch folder>/scores.json" ({predicted class, its score} per sample). */
	private boolean readScores(File dir) {
		if (scores.containsKey(dir.getName())) return true;
		File f = new File(dir, "scores.json");
		if (!f.isFile()) return false;
		try {
			JsonArray a = JsonParser.parseString(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8))
					.getAsJsonObject().getAsJsonArray("samples");
			double[][] out = new double[a.size()][];
			for (int i = 0; i < out.length; i++) {
				JsonObject o = a.get(i).getAsJsonObject();
				int c = o.get("pred_class").getAsInt();
				JsonArray sc = o.getAsJsonArray("scores");
				out[i] = new double[] { c, c < sc.size() ? sc.get(c).getAsDouble() : Double.NaN };
			}
			scores.put(dir.getName(), out);
			return true;
		} catch (Exception e) {
			return false; // being written
		}
	}

	/** "Sample: <image>", or a selector when several samples are tracked (all are shown in classification). */
	private void showSampleBar(TrainingPreview p) {
		sampleBar.removeAll();
		JLabel title = new JLabel(p.classification ? "Samples" : "Sample:");
		title.setFont(title.getFont().deriveFont(Font.BOLD, 12f));
		sampleBar.add(title);
		if (!p.classification) title.setText(p.samples.size() == 1 ? "Sample" : "Samples");
		if (!p.classification) {
			javax.swing.JCheckBox auto = new javax.swing.JCheckBox("Auto contrast", p.autoContrast);
			auto.setOpaque(false);
			auto.setFont(auto.getFont().deriveFont(Font.PLAIN, 12f));
			auto.setToolTipText("<html>On: each image is shown from its lowest to its highest values (a few saturated), as "
					+ "Fiji's <i>Auto</i> contrast,<br>so low probabilities (e.g. thin contours) are visible too. "
					+ "Off: probabilities are shown from 0 (black) to 1 (white).<br>The values shown are in each "
					+ "image's tooltip.</html>");
			auto.addActionListener(e -> {
				p.autoContrast = auto.isSelected();
				new Thread(this::rerender, "BiaPy-preview-contrast").start();
			});
			sampleBar.add(javax.swing.Box.createHorizontalStrut(16));
			sampleBar.add(auto);
		}
		sampleBar.revalidate();
		sampleBar.repaint();
	}

	private static String sampleName(TrainingPreview p, int i) {
		TrainingPreview.Sample s = p.samples.get(i);
		return s.image != null ? s.image : s.file;
	}

	/** Rebuilds the grid: a header row, then a row per panel (or per sample in classification). */
	private void showGrid(TrainingPreview p, int shownSample, List<String> epochs) {
		grid.removeAll();
		GridBagConstraints c = new GridBagConstraints();
		c.insets = new Insets(0, 0, 6, 8);
		c.anchor = GridBagConstraints.CENTER;
		boolean hasGt = p.classification || !p.gt.isEmpty();
		List<String> headers = new ArrayList<>(), infos = new ArrayList<>();
		headers.add(p.columnTitle("input", "Input"));
		infos.add(p.columnInfo("input"));
		if (hasGt) {
			headers.add(p.columnTitle("gt", "Ground truth"));
			infos.add(p.columnInfo("gt"));
		}
		if (epochs.isEmpty() && (p.classification || !p.pred.isEmpty())) headers.add("Prediction");
		for (String e : epochs) headers.add("Epoch " + e.substring("pred_epoch_".length()).replaceFirst("^0+(?=\\d)", ""));
		c.gridy = 0;
		for (int i = 0; i < headers.size(); i++) {
			c.gridx = i + 1;
			grid.add(caption(headers.get(i), i < infos.size() ? infos.get(i) : null), c);
		}

		if (p.classification) {
			for (int s = 0; s < p.samples.size(); s++) {
				TrainingPreview.Sample sample = p.samples.get(s);
				c.gridy = s + 1;
				int x = 0;
				c.gridx = x++;
				grid.add(rowLabel(sampleName(p, s)), c);
				c.gridx = x++;
				grid.add(iconTile(icons.get("input/" + sample.file), 0), c);
				c.gridx = x++;
				grid.add(sample.gtClass == null ? textTile("?", null) : textTile(p.className(sample.gtClass), null), c);
				if (epochs.isEmpty()) {
					c.gridx = x++;
					grid.add(tile(), c);
				}
				for (String e : epochs) {
					double[][] sc = scores.get(e);
					c.gridx = x++;
					if (sc == null || s >= sc.length) {
						grid.add(tile(), c);
						continue;
					}
					int cls = (int) sc[s][0];
					String text = p.className(cls) + (Double.isNaN(sc[s][1]) ? "" : String.format("<br>%.0f%%", 100 * sc[s][1]));
					Color color = sample.gtClass == null ? null : sample.gtClass == cls ? BiaPyFiji.GREEN : BiaPyFiji.RED;
					grid.add(textTile(text, color), c);
				}
			}
		} else {
			boolean labels = p.rows.size() > 1 || !p.rows.get(0).label.isEmpty();
			int y = 1;
			for (int s = 0; s < Math.min(p.samples.size(), MAX_SAMPLES); s++) {
				String file = p.samples.get(s).file;
				// The sample's image name above its rows
				c.gridy = y++;
				c.gridx = 1;
				c.gridwidth = headers.size();
				c.anchor = GridBagConstraints.WEST;
				grid.add(sampleTitle(sampleName(p, s)), c);
				c.gridwidth = 1;
				c.anchor = GridBagConstraints.CENTER;
				for (int r = 0; r < p.rows.size(); r++) {
					TrainingPreview.Row row = p.rows.get(r);
					c.gridy = y++;
					int x = 0;
					c.gridx = x++;
					grid.add(labels ? rowLabel(row.label) : new JLabel(), c);
					c.gridx = x++;
					grid.add(iconTile(icons.get("input/" + file), row.input), c);
					if (hasGt) {
						c.gridx = x++;
						grid.add(iconTile(icons.get("gt/" + file), row.gt), c);
					}
					if (epochs.isEmpty() && !p.pred.isEmpty()) {
						c.gridx = x++;
						grid.add(row.pred >= 0 ? tile() : emptyTile(), c);
					}
					for (String e : epochs) {
						c.gridx = x++;
						grid.add(iconTile(icons.get(e + "/" + file), row.pred), c);
					}
				}
			}
		}
		// Keep everything to the left
		c.gridx = headers.size() + 1;
		c.gridy = 0;
		c.weightx = 1;
		grid.add(new JLabel(), c);
		grid.revalidate();
		grid.repaint();
	}

	/** Tile with the given panel of a rendered file, "waiting" if not rendered yet, empty if index &lt; 0. */
	private static JComponent iconTile(List<ImageIcon> rendered, int index) {
		if (index < 0) return emptyTile();
		if (rendered == null || index >= rendered.size()) return tile();
		JLabel t = tile();
		t.setIcon(rendered.get(index));
		t.setText(null);
		t.setToolTipText(rendered.get(index).getDescription());
		return t;
	}

	/** Size of a tile: the image and its 1 px frame. */
	private static final Dimension TILE_SIZE = new Dimension(TILE + 2, TILE + 2);

	private static JLabel textTile(String html, Color color) {
		JLabel t = tile();
		t.setText("<html><div style='text-align:center'>" + html + "</div></html>");
		t.setFont(t.getFont().deriveFont(Font.BOLD, 13f));
		t.setForeground(color == null ? Color.DARK_GRAY : color);
		t.setBorder(BorderFactory.createLineBorder(color == null ? BiaPyFiji.LIGHT_BLUE : color, 2, true));
		return t;
	}

	private static JLabel emptyTile() {
		JLabel l = new JLabel();
		l.setPreferredSize(TILE_SIZE);
		return l;
	}

	/** The name of a sample's image, in blue above its tiles (also used by the test predictions). */
	static JLabel sampleTitle(String name) {
		JLabel l = new JLabel(name);
		l.setFont(l.getFont().deriveFont(Font.BOLD, 12f));
		l.setForeground(BiaPyFiji.BLUE);
		l.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
		return l;
	}

	private static JLabel rowLabel(String text) {
		JLabel l = new JLabel("<html><div style='width:70px;text-align:right'>" + text + "</div></html>");
		l.setFont(l.getFont().deriveFont(Font.BOLD, 12f));
		return l;
	}

	/** A framed square, "waiting . . ." in its center until its image is shown. */
	private static JLabel tile() {
		JLabel l = new JLabel("waiting . . .", SwingConstants.CENTER);
		l.setVerticalAlignment(SwingConstants.CENTER);
		l.setForeground(BiaPyFiji.GREY);
		l.setFont(l.getFont().deriveFont(Font.PLAIN, 11f));
		l.setBorder(BorderFactory.createLineBorder(TILE_FRAME, 1));
		l.setPreferredSize(TILE_SIZE);
		l.setMinimumSize(TILE_SIZE);
		return l;
	}

	/** Column title; with an explanation, followed by an (i) that shows it (also its tooltip). */
	private static JLabel caption(String text, String info) {
		JLabel l = new JLabel(text, SwingConstants.CENTER);
		l.setFont(l.getFont().deriveFont(Font.PLAIN, 12f));
		if (info != null) {
			l.setIcon(LineChart.infoIcon(13));
			l.setHorizontalTextPosition(SwingConstants.LEFT);
			l.setIconTextGap(5);
			l.setToolTipText("<html><div style='width:320px'>" + info + "</div></html>");
			l.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
			l.addMouseListener(new java.awt.event.MouseAdapter() {
				@Override
				public void mouseClicked(java.awt.event.MouseEvent e) {
					LineChart.showInfo(l, text, info);
				}
			});
		}
		return l;
	}
}
