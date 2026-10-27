package io.github.biapyx.fiji.gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.util.Base64;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.github.biapyx.fiji.BiaPyFiji;

/**
 * Window of a data check in the Wizard. It opens when the check starts, with a progress bar and the step
 * being done, and then shows a few raw images next to their targets (paired as BiaPy will pair them) and the
 * problems found (e.g. targets of another kind than the workflow expects), so the user can confirm that the
 * right data was selected. The images come from the bridge's "preview_data" result.
 */
public class DataPreviewDialog {

	private static final Color WARNING_BG = new Color(255, 244, 229);
	private static final Color WARNING_FG = new Color(176, 92, 0);
	/** Minimum width of the texts; otherwise they are as wide as the images. */
	private static final int MIN_TEXT_WIDTH = 420;
	private static final int ERROR_WIDTH = 520;
	/** Longer errors scroll. */
	private static final int ERROR_MAX_HEIGHT = 420;

	private final Window owner;
	private final JDialog dialog;
	private final JPanel center = new JPanel(new BorderLayout());
	private final JLabel status = new JLabel("Preparing BiaPy (the first time, it takes a few seconds) . . .");
	/** Files checked out of the total, under the progress bar. */
	private final JLabel count = new JLabel(" ");
	private final javax.swing.JProgressBar bar = new javax.swing.JProgressBar(0, 100);
	private final JButton ok = UiUtils.primaryButton("Looks right",
			"The images were read as expected: use this folder");
	private final JButton change = UiUtils.secondaryButton("Choose another folder",
			"Close this window without using the folder");
	private boolean accepted = false;
	private boolean finished = false;
	private Runnable onCancel;

	/**
	 * Opens the window of a check (call {@link #waitForUser()} to show it), or returns null when headless.
	 *
	 * @param title what is checked, e.g. "Training data"
	 * @param folder the folder being checked
	 */
	public static DataPreviewDialog open(Component parent, String title, String folder) {
		if (java.awt.GraphicsEnvironment.isHeadless()) return null;
		return new DataPreviewDialog(parent, title, folder);
	}

	private DataPreviewDialog(Component parent, String title, String folder) {
		owner = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
		dialog = new JDialog(owner, "BiaPy - " + title, java.awt.Dialog.ModalityType.APPLICATION_MODAL);
		dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
		dialog.addWindowListener(new java.awt.event.WindowAdapter() {
			@Override
			public void windowClosing(java.awt.event.WindowEvent e) {
				close(false);
			}
		});

		JPanel checking = checkingPanel(folder, bar, count, status);
		center.setBackground(BiaPyFiji.WHITE);
		center.add(checking, BorderLayout.CENTER);

		ok.setEnabled(false);
		ok.addActionListener(e -> close(true));
		change.addActionListener(e -> close(false));
		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 8));
		buttons.setBackground(BiaPyFiji.WHITE);
		buttons.add(change);
		buttons.add(ok);

		JPanel root = new JPanel(new BorderLayout());
		root.setBackground(BiaPyFiji.WHITE);
		root.add(center, BorderLayout.CENTER);
		root.add(buttons, BorderLayout.SOUTH);
		dialog.setContentPane(root);
		dialog.getRootPane().setDefaultButton(ok);
		dialog.pack();
		dialog.setLocationRelativeTo(owner);
	}

	/** Called if the user leaves before the check finishes (e.g. to stop it). */
	public void setOnCancel(Runnable r) {
		onCancel = r;
	}

	/** Step being done, shown under the progress bar (which moves meanwhile: its duration is unknown). */
	public void step(String text) {
		status.setText(text);
		bar.setStringPainted(false);
		bar.setIndeterminate(true);
	}

	/**
	 * Progress over the files of the folder.
	 *
	 * @param name file just checked, or null when all were read and the data is being analyzed
	 * @param unit what the files are, e.g. "images"
	 */
	public void progress(int done, int total, String name, String unit) {
		showProgress(bar, count, status, done, total, name, unit);
	}

	/** What is shown while the folder is checked: the folder, the progress bar, the files checked and the step. */
	static JPanel checkingPanel(String folder, javax.swing.JProgressBar bar, JLabel count, JLabel status) {
		JPanel checking = new JPanel();
		checking.setLayout(new BoxLayout(checking, BoxLayout.Y_AXIS));
		checking.setBackground(BiaPyFiji.WHITE);
		checking.setBorder(BorderFactory.createEmptyBorder(20, 24, 12, 24));
		checking.add(centered(text("<center>Checking the data in<br><i>" + escape(folder) + "</i></center>", MIN_TEXT_WIDTH,
				Font.PLAIN, Color.BLACK)));
		checking.add(javax.swing.Box.createVerticalStrut(14));
		// Moving until the files are counted (BiaPy is loaded first), then from 0 to 100%
		bar.setIndeterminate(true);
		bar.setPreferredSize(new Dimension(MIN_TEXT_WIDTH, 18));
		checking.add(centered(bar));
		checking.add(javax.swing.Box.createVerticalStrut(8));
		count.setFont(count.getFont().deriveFont(Font.BOLD, 12f));
		checking.add(centered(count));
		checking.add(javax.swing.Box.createVerticalStrut(2));
		status.setForeground(BiaPyFiji.GREY);
		status.setFont(status.getFont().deriveFont(Font.PLAIN, 12f));
		// As wide as the progress bar whatever the file name, so the window doesn't change size
		status.setHorizontalAlignment(SwingConstants.CENTER);
		status.setPreferredSize(new Dimension(MIN_TEXT_WIDTH, status.getPreferredSize().height));
		checking.add(centered(status));
		return checking;
	}

	static void showProgress(javax.swing.JProgressBar bar, JLabel count, JLabel status, int done, int total, String name,
			String unit) {
		bar.setValue(total > 0 ? (int) Math.round(100.0 * done / total) : 0);
		// Counting the files: from 0 to 100%; analyzing them afterwards takes an unknown time: moving
		bar.setIndeterminate(name == null);
		bar.setStringPainted(name != null); // "37%"
		count.setText(done + "/" + total + " " + unit + " checked");
		status.setText(name == null ? "Analyzing the data (sizes, intensities, objects) . . ." : abbreviate(name));
	}

	private static String abbreviate(String name) {
		return name.length() <= 60 ? name : name.substring(0, 28) + "..." + name.substring(name.length() - 29);
	}

	/** Replaces the progress by the images and the problems found. */
	public void showPreview(String intro, JsonObject preview) {
		finished = true;
		JPanel content = content(intro, preview);
		JScrollPane sp = new JScrollPane(content);
		sp.setBorder(null);
		sp.getVerticalScrollBar().setUnitIncrement(16);
		center.removeAll();
		center.add(sp, BorderLayout.CENTER);
		ok.setEnabled(true);
		Dimension pref = content.getPreferredSize();
		// Room for the vertical scroll bar and the buttons
		dialog.setSize(new Dimension(Math.min(pref.width + 36, 900), Math.min(pref.height + 90, 760)));
		dialog.setLocationRelativeTo(owner);
		dialog.revalidate();
		dialog.repaint();
	}

	/** Replaces the progress by an error (the data can't be used). */
	public void showError(String message) {
		finished = true;
		JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setBackground(BiaPyFiji.WHITE);
		p.setBorder(BorderFactory.createEmptyBorder(16, 20, 8, 20));
		// A text area, not an HTML label: it also breaks long words such as paths, so nothing is cut
		javax.swing.JTextArea text = new javax.swing.JTextArea(message);
		text.setLineWrap(true);
		text.setWrapStyleWord(true);
		text.setEditable(false);
		text.setOpaque(false);
		text.setBorder(null);
		text.setForeground(BiaPyFiji.RED);
		text.setFont(new JLabel().getFont().deriveFont(Font.PLAIN, 12f));
		text.setSize(ERROR_WIDTH, Short.MAX_VALUE); // the height of the wrapped text is then computed for this width
		int height = text.getPreferredSize().height;
		text.setPreferredSize(new Dimension(ERROR_WIDTH, height));
		if (height > ERROR_MAX_HEIGHT) {
			JScrollPane sp = new JScrollPane(text);
			sp.setBorder(null);
			sp.setOpaque(false);
			sp.getViewport().setOpaque(false);
			sp.setPreferredSize(new Dimension(ERROR_WIDTH + 20, ERROR_MAX_HEIGHT));
			p.add(sp);
		} else {
			p.add(text);
		}
		center.removeAll();
		center.add(p, BorderLayout.CENTER);
		change.setText("Close");
		change.setToolTipText("Close this window");
		ok.setVisible(false);
		dialog.pack();
		dialog.setLocationRelativeTo(owner);
		dialog.revalidate();
		dialog.repaint();
	}

	/** Shows the window until the user closes it. @return true if the user accepted the data */
	public boolean waitForUser() {
		dialog.setVisible(true); // blocks until closed (the check goes on meanwhile)
		return accepted;
	}

	private void close(boolean accept) {
		accepted = accept && finished;
		if (!finished && onCancel != null) onCancel.run();
		dialog.dispose();
	}

	/**
	 * Headless runs (e.g. the test harnesses): prints the problems found and, with the system property
	 * "biapy.preview.dir", saves the preview as a PNG.
	 */
	public static void report(String title, String intro, JsonObject preview) {
		for (JsonElement w : preview.getAsJsonArray("warnings"))
			System.out.println("[BiaPy data preview warning] " + w.getAsString());
		String dir = System.getProperty("biapy.preview.dir");
		if (dir != null)
			save(content(intro, preview), new File(dir, "preview_" + title.replaceAll("\\W+", "_").toLowerCase() + ".png"));
	}

	static JPanel content(String intro, JsonObject preview) {
		JPanel grid = grid(preview);
		int width = Math.max(MIN_TEXT_WIDTH, grid.getPreferredSize().width);
		JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setBackground(BiaPyFiji.WHITE);
		p.setBorder(BorderFactory.createEmptyBorder(12, 14, 8, 14));
		p.add(centered(text(intro, width, Font.PLAIN, Color.BLACK)));
		p.add(javax.swing.Box.createVerticalStrut(10));

		StringBuilder warnings = new StringBuilder();
		for (JsonElement w : preview.getAsJsonArray("warnings")) warnings.append("<li>").append(escape(w.getAsString())).append("</li>");
		if (warnings.length() > 0) {
			JLabel l = text("<b>Please check:</b><ul style='margin-left:16px'>" + warnings + "</ul>", width - 18, Font.PLAIN, WARNING_FG);
			JPanel box = new JPanel(new BorderLayout());
			box.setBackground(WARNING_BG);
			box.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(WARNING_FG),
					BorderFactory.createEmptyBorder(6, 8, 6, 8)));
			box.add(l);
			p.add(centered(box));
		} else {
			p.add(centered(text("<center><b>No problems found</b> in the files inspected.</center>", width, Font.PLAIN, BiaPyFiji.GREEN)));
		}
		for (JsonElement n : preview.getAsJsonArray("notes"))
			p.add(centered(text("<center>" + escape(n.getAsString()) + "</center>", width, Font.PLAIN, BiaPyFiji.GREY)));
		p.add(javax.swing.Box.createVerticalStrut(10));
		p.add(centered(grid));
		return p;
	}

	/** One row per sample: raw image and target, each with its file name under it. */
	private static JPanel grid(JsonObject preview) {
		JPanel g = new JPanel(new GridBagLayout());
		g.setOpaque(false);
		GridBagConstraints c = new GridBagConstraints();
		c.insets = new Insets(4, 6, 4, 6);
		c.anchor = GridBagConstraints.NORTH;
		boolean hasTargets = false;
		for (JsonElement e : preview.getAsJsonArray("samples"))
			if (e.getAsJsonObject().has("target_name")) hasTargets = true;
		String[] columns = { "Raw image", "Target" };
		if (preview.has("columns"))
			for (int i = 0; i < 2 && i < preview.getAsJsonArray("columns").size(); i++)
				columns[i] = preview.getAsJsonArray("columns").get(i).getAsString();
		c.gridy = 0;
		for (int col = 0; col < (hasTargets ? 2 : 1); col++) {
			c.gridx = col;
			JLabel h = new JLabel(columns[col]);
			h.setFont(h.getFont().deriveFont(Font.BOLD, 13f));
			g.add(h, c);
		}
		for (JsonElement e : preview.getAsJsonArray("samples")) {
			JsonObject s = e.getAsJsonObject();
			c.gridy++;
			c.gridx = 0;
			g.add(cell(s, "raw"), c);
			if (hasTargets) {
				c.gridx = 1;
				g.add(cell(s, "target"), c);
			}
		}
		return g;
	}

	/** Image of a preview (base64 PNG) scaled to fit in {@code maxSide} pixels, or null. */
	public static ImageIcon thumbnail(String base64Png, int maxSide) {
		try {
			BufferedImage bi = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(base64Png)));
			double f = Math.min(1.0, maxSide / (double) Math.max(bi.getWidth(), bi.getHeight()));
			if (f >= 1.0) return new ImageIcon(bi);
			return new ImageIcon(bi.getScaledInstance(Math.max(1, (int) (bi.getWidth() * f)),
					Math.max(1, (int) (bi.getHeight() * f)), java.awt.Image.SCALE_SMOOTH));
		} catch (Exception e) {
			return null;
		}
	}

	private static JPanel cell(JsonObject s, String prefix) {
		JPanel cell = new JPanel(new BorderLayout(0, 3));
		cell.setOpaque(false);
		JLabel img = new JLabel();
		img.setVerticalAlignment(SwingConstants.TOP);
		if (s.has(prefix + "_png")) {
			try {
				BufferedImage bi = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(s.get(prefix + "_png").getAsString())));
				img.setIcon(new ImageIcon(bi));
				img.setBorder(BorderFactory.createLineBorder(BiaPyFiji.LIGHT_BLUE));
			} catch (Exception ex) {
				img.setText("(image not readable)");
			}
		} else if (s.has(prefix + "_text")) {
			img.setText(s.get(prefix + "_text").getAsString());
			img.setFont(img.getFont().deriveFont(Font.BOLD, 14f));
		} else {
			img.setText("-");
		}
		// In its own row so that its border fits the image, not the (maybe wider) caption
		JPanel imgRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
		imgRow.setOpaque(false);
		imgRow.add(img);
		cell.add(imgRow, BorderLayout.CENTER);
		if (s.has(prefix + "_name")) {
			String name = s.get(prefix + "_name").getAsString();
			JLabel caption = new JLabel(name.length() > 40 ? "..." + name.substring(name.length() - 37) : name);
			caption.setToolTipText(name);
			caption.setFont(caption.getFont().deriveFont(Font.PLAIN, 11f));
			caption.setForeground(BiaPyFiji.GREY);
			caption.setHorizontalAlignment(SwingConstants.CENTER);
			cell.add(caption, BorderLayout.SOUTH);
		}
		return cell;
	}

	/** HTML label wrapped to {@code width}, as high as the wrapped text. */
	private static JLabel text(String html, int width, int style, Color color) {
		JLabel l = new JLabel("<html>" + html + "</html>");
		l.setFont(l.getFont().deriveFont(style, 12f));
		l.setForeground(color);
		javax.swing.text.View view = (javax.swing.text.View) l.getClientProperty(javax.swing.plaf.basic.BasicHTML.propertyKey);
		if (view != null) {
			view.setSize(width, 0);
			l.setPreferredSize(new Dimension(width, (int) Math.ceil(view.getPreferredSpan(javax.swing.text.View.Y_AXIS))));
		}
		return l;
	}

	private static Component centered(Component c) {
		JPanel row = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 2));
		row.setOpaque(false);
		row.add(c);
		row.setAlignmentX(Component.CENTER_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
		return row;
	}

	private static String escape(String s) {
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	/** Renders the content to a PNG (headless runs, e.g. the test harnesses). */
	private static void save(JPanel content, File f) {
		try {
			content.setSize(content.getPreferredSize());
			layoutAll(content);
			BufferedImage img = new BufferedImage(content.getWidth(), content.getHeight(), BufferedImage.TYPE_INT_RGB);
			content.printAll(img.getGraphics());
			f.getParentFile().mkdirs();
			ImageIO.write(img, "png", f);
		} catch (Exception e) {
			System.out.println("[BiaPy data preview] could not save " + f + ": " + e);
		}
	}

	private static void layoutAll(Component c) {
		c.doLayout();
		if (c instanceof java.awt.Container)
			for (Component ch : ((java.awt.Container) c).getComponents()) layoutAll(ch);
	}
}
