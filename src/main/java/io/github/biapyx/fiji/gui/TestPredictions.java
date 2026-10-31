package io.github.biapyx.fiji.gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;

import io.github.biapyx.fiji.BiaPyFiji;

/**
 * The latest predictions of the test phase, next to their input images, from the files BiaPy writes in the job's
 * results folder (the instances, the post-processed or binarized masks... whichever is the final output of the
 * workflow).
 */
public class TestPredictions {

	/** How many of the latest predictions are shown. */
	private static final int SHOWN = 5;
	private static final int TILE = 150;
	private static final Color FRAME = new Color(190, 190, 190);
	/** Output folders of the test, the most final first, and what they hold. */
	private static final String[][] OUTPUTS = { { "per_image_instances", "Instances" },
			{ "full_image_instances", "Instances" }, { "per_image_post_processing", "Post-processed" },
			{ "full_image_post_processing", "Post-processed" }, { "per_image_binarized", "Binarized" },
			{ "full_image_binarized", "Binarized" }, { "per_image_local_max_check", "Detected points" },
			{ "per_image", "Prediction" }, { "full_image", "Prediction" } };

	private final File resultsDir;
	private final File inputDir;
	/** Predictions of previous runs with the same job name. */
	private final FileSnapshot before;
	private final JPanel grid = new JPanel(new GridBagLayout());
	private final JComponent view;
	private final TrainingPreview renderer = TrainingPreview.legacy(java.util.Collections.singletonList("0.tif"));
	private final Map<String, List<ImageIcon>> rendered = new HashMap<>();
	private String shown = "";

	/**
	 * @param resultsDir BiaPy's results folder of the run ("results/<job id>")
	 * @param inputDir the test images (DATA.TEST.PATH), or null
	 */
	public TestPredictions(File resultsDir, File inputDir) {
		this.resultsDir = resultsDir;
		this.inputDir = inputDir;
		this.before = new FileSnapshot(resultsDir);
		grid.setOpaque(false);
		JPanel p = new JPanel(new BorderLayout());
		p.setOpaque(false);
		p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		p.add(message("The predictions of the test images will appear here when the test starts."), BorderLayout.NORTH);
		view = p; // the job panel puts it in a scrolling tab
		this.content = p;
	}

	private final JPanel content;

	public JComponent view() {
		return view;
	}

	/** Shows the latest predictions written since the run started. Call it from a background thread. */
	public void refresh() {
		for (String[] out : OUTPUTS) {
			File dir = new File(resultsDir, out[0]);
			File[] files = dir.listFiles((d, n) -> !n.startsWith("."));
			if (files == null) continue;
			List<File> recent = new ArrayList<>();
			for (File f : files) if (before.isNew(f)) recent.add(f);
			if (recent.isEmpty()) continue;
			recent.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
			List<File> latest = recent.subList(0, Math.min(SHOWN, recent.size()));
			String state = out[0] + latest + recent.size();
			if (state.equals(shown)) return;
			List<Object[]> rows = new ArrayList<>();
			for (File f : latest) {
				List<ImageIcon> pred = render(f, "Instances".equals(out[1]) ? "labels" : "gray");
				if (pred == null) continue; // being written
				File in = input(f.getName());
				rows.add(new Object[] { f.getName(), in == null ? null : render(in, "gray"), pred });
			}
			shown = state;
			int total = recent.size();
			String kind = out[1];
			// Large 3D images predicted by chunks (TEST.BY_CHUNKS) are saved as Zarr, which this tab can't show
			if (rows.isEmpty() && latest.stream().anyMatch(f -> f.isDirectory() && f.getName().endsWith(".zarr"))) {
				SwingUtilities.invokeLater(() -> {
					content.removeAll();
					WrappingTextArea note = new WrappingTextArea("The test images were predicted by chunks, as they are "
							+ "large, so the results are saved as Zarr files, which this tab can't show. They are in the '"
							+ dir.getName() + "' folder of the results: open them in Fiji with File > Import > "
							+ "HDF5/N5/Zarr/OME-NGFF. They are OME-Zarr 0.5 images by default (Zarr format 3), which Fiji "
							+ "reads since the latest release of its N5 plugins: update Fiji (Help > Update...) if it "
							+ "can't open them (see 'Workflow info').");
					note.setForeground(BiaPyFiji.GREY);
					note.setFont(new JLabel().getFont().deriveFont(Font.PLAIN, 12f));
					content.add(note, BorderLayout.NORTH);
					content.revalidate();
					content.repaint();
				});
				return;
			}
			SwingUtilities.invokeLater(() -> show(rows, kind, total));
			return;
		}
	}

	private List<ImageIcon> render(File f, String mode) {
		String key = f.getAbsolutePath() + ":" + f.lastModified();
		List<ImageIcon> icons = rendered.get(key);
		if (icons != null) return icons;
		TrainingPreview.Panel panel = new TrainingPreview.Panel();
		panel.mode = mode;
		icons = renderer.render(f, Arrays.asList(panel), TILE);
		if (icons != null) rendered.put(key, icons);
		return icons;
	}

	/** The test image a prediction comes from (same name, any extension). */
	private File input(String predName) {
		if (inputDir == null) return null;
		String base = predName.replaceFirst("\\.[^.]+$", "");
		File[] files = inputDir.listFiles((d, n) -> n.replaceFirst("\\.[^.]+$", "").equals(base));
		return files == null || files.length == 0 ? null : files[0];
	}

	private void show(List<Object[]> rows, String kind, int total) {
		content.removeAll();
		content.add(message("Latest " + rows.size() + " of the " + total + " test images predicted so far (newest first)."),
				BorderLayout.NORTH);
		grid.removeAll();
		GridBagConstraints c = new GridBagConstraints();
		c.insets = new Insets(4, 0, 6, 10);
		c.gridy = 0;
		String[] headers = { "", "Input", kind };
		for (int i = 0; i < headers.length; i++) {
			c.gridx = i;
			grid.add(caption(headers[i]), c);
		}
		for (Object[] row : rows) {
			c.gridy++;
			c.gridx = 0;
			c.anchor = GridBagConstraints.EAST;
			JLabel name = new JLabel("<html><div style='width:120px;text-align:right'>" + row[0] + "</div></html>");
			name.setFont(name.getFont().deriveFont(Font.PLAIN, 11f));
			grid.add(name, c);
			c.anchor = GridBagConstraints.CENTER;
			c.gridx = 1;
			grid.add(tile(icon(row[1])), c);
			c.gridx = 2;
			grid.add(tile(icon(row[2])), c);
		}
		c.gridx = 3;
		c.weightx = 1;
		grid.add(new JLabel(), c);
		JPanel holder = new JPanel(new BorderLayout());
		holder.setOpaque(false);
		holder.add(grid, BorderLayout.WEST);
		content.add(holder, BorderLayout.CENTER);
		content.revalidate();
		content.repaint();
	}

	@SuppressWarnings("unchecked")
	private static ImageIcon icon(Object icons) {
		List<ImageIcon> l = (List<ImageIcon>) icons;
		return l == null || l.isEmpty() ? null : l.get(0);
	}

	private static JLabel tile(ImageIcon icon) {
		JLabel l = new JLabel(icon == null ? "not found" : null, icon, SwingConstants.CENTER);
		l.setForeground(BiaPyFiji.GREY);
		l.setBorder(BorderFactory.createLineBorder(FRAME, 1));
		if (icon != null) l.setToolTipText(icon.getDescription());
		l.setPreferredSize(new Dimension(TILE + 2, TILE + 2));
		return l;
	}

	private static JLabel caption(String text) {
		JLabel l = new JLabel(text, SwingConstants.CENTER);
		l.setFont(l.getFont().deriveFont(Font.BOLD, 12f));
		return l;
	}

	private static JLabel message(String text) {
		JLabel l = new JLabel(text);
		l.setForeground(BiaPyFiji.GREY);
		l.setFont(l.getFont().deriveFont(Font.PLAIN, 12f));
		l.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
		return l;
	}
}
