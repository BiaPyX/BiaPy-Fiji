package io.github.biapyx.fiji.gui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.io.File;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import com.google.gson.JsonObject;

import io.github.biapyx.fiji.BiaPyFiji;
import io.github.biapyx.fiji.env.PythonBridge;

/**
 * Window of where the models downloaded are (BiaPy's folder, of the zoo models "Apply model" keeps, and bioimageio's, of the
 * zoo models of the jobs) and how much space they take, to open them. Deleting them is left to the user.
 */
class ModelFoldersDialog extends JPanel {

	private static final long serialVersionUID = 1L;
	private static final String[] WHAT = { "Zoo models kept by 'Apply model' (BiaPy)",
			"Zoo models used by the jobs (bioimageio)" };
	private static final String[] KEYS = { "biapy", "bioimageio" };

	private final JLabel[] labels = { new JLabel(" "), new JLabel(" ") };
	private final JLabel[] paths = { new JLabel(" "), new JLabel(" ") };
	private final JButton[] open = { UiUtils.secondaryButton("Open folder", "Open the folder of BiaPy's models"),
			UiUtils.secondaryButton("Open folder", "Open the folder of bioimageio's downloads") };
	private final File[] folders = new File[2];
	private final Spinner spinner = new Spinner(18);
	private final JLabel status = new JLabel(" ");
	private final JButton refresh = UiUtils.secondaryButton("Refresh", "Measure the folders again");

	/** Opens the window over {@code parent}'s window. */
	static void show(Component parent) {
		ModelFoldersDialog content = new ModelFoldersDialog();
		JDialog d = new JDialog(SwingUtilities.getWindowAncestor(parent), "BiaPy - Downloaded models",
				java.awt.Dialog.ModalityType.MODELESS);
		d.setContentPane(content);
		d.pack();
		d.setSize(Math.max(d.getWidth(), 640), d.getHeight());
		d.setLocationRelativeTo(parent);
		d.setVisible(true);
		content.measure();
	}

	ModelFoldersDialog() {
		JPanel body = this;
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setBackground(BiaPyFiji.WHITE);
		body.setBorder(BorderFactory.createEmptyBorder(14, 16, 12, 16));
		body.add(UiUtils.title("Downloaded models", 16f));
		for (int i = 0; i < 2; i++) {
			int which = i;
			labels[i].setText(WHAT[i]);
			paths[i].setFont(paths[i].getFont().deriveFont(Font.PLAIN, 12f));
			paths[i].setForeground(BiaPyFiji.GREY);
			open[i].setEnabled(false);
			open[i].addActionListener(e -> UiUtils.openFolder(folders[which]));
			JPanel text = new JPanel(new GridLayout(2, 1));
			text.setOpaque(false);
			text.add(labels[i]);
			text.add(paths[i]);
			JPanel button = new JPanel(new java.awt.GridBagLayout()); // centered vertically
			button.setOpaque(false);
			button.add(open[i]);
			JPanel row = new JPanel(new BorderLayout(10, 0));
			row.setOpaque(false);
			row.add(text, BorderLayout.CENTER);
			row.add(button, BorderLayout.EAST);
			body.add(Box.createVerticalStrut(8));
			body.add(row);
		}
		JLabel note = new JLabel("<html>To free disk space, delete these folders while BiaPy is not running.<br>The "
				+ "models are downloaded again when they are needed.</html>");
		note.setFont(note.getFont().deriveFont(Font.PLAIN, 12f));
		note.setForeground(BiaPyFiji.GREY);
		body.add(Box.createVerticalStrut(10));
		body.add(note);

		status.setFont(status.getFont().deriveFont(Font.PLAIN, 12f));
		status.setForeground(BiaPyFiji.GREY);
		JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
		left.setOpaque(false);
		left.add(spinner);
		left.add(status);
		JButton close = UiUtils.secondaryButton("Close", "Close this window");
		close.addActionListener(e -> SwingUtilities.getWindowAncestor(this).dispose());
		refresh.addActionListener(e -> measure());
		JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
		right.setOpaque(false);
		right.add(refresh);
		right.add(close);
		JPanel buttons = new JPanel(new BorderLayout());
		buttons.setOpaque(false);
		buttons.add(left, BorderLayout.CENTER);
		buttons.add(right, BorderLayout.EAST);
		body.add(Box.createVerticalStrut(12));
		body.add(buttons);
		for (Component c : body.getComponents()) ((javax.swing.JComponent) c).setAlignmentX(Component.LEFT_ALIGNMENT);
	}

	/** The folders (from Python) and their sizes, in the background. */
	void measure() {
		refresh.setEnabled(false);
		spinner.spin(true);
		status.setText("Measuring the folders . . .");
		new Thread(() -> {
			String error = null;
			String[] texts = new String[2];
			try {
				JsonObject f = new PythonBridge().call("cache_folders", null, null);
				for (int i = 0; i < 2; i++) {
					folders[i] = new File(f.get(KEYS[i]).getAsString());
					long size = folderSize(folders[i]);
					texts[i] = WHAT[i] + ": " + (size == 0 ? "empty" : ApplyModelPanel.megabytes(size));
				}
			} catch (Exception e) {
				error = "BiaPy could not tell where its models are: " + e.getMessage();
			}
			String fe = error;
			SwingUtilities.invokeLater(() -> {
				spinner.spin(false);
				refresh.setEnabled(true);
				status.setText(fe == null ? " " : fe);
				status.setForeground(fe == null ? BiaPyFiji.GREY : BiaPyFiji.RED);
				if (fe != null) return;
				for (int i = 0; i < 2; i++) {
					labels[i].setText(texts[i]);
					paths[i].setText(folders[i].getAbsolutePath());
					paths[i].setToolTipText(folders[i].getAbsolutePath());
					open[i].setEnabled(folders[i].isDirectory());
				}
			});
		}, "BiaPy-model-folders").start();
	}

	/** Bytes in a folder and its subfolders (0 if it does not exist). */
	static long folderSize(File folder) {
		try (java.util.stream.Stream<java.nio.file.Path> s = java.nio.file.Files.walk(folder.toPath())) {
			return s.filter(java.nio.file.Files::isRegularFile).mapToLong(p -> p.toFile().length()).sum();
		} catch (Exception e) {
			return 0;
		}
	}
}
