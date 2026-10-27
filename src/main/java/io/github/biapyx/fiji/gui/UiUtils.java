package io.github.biapyx.fiji.gui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.io.File;
import java.net.URI;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JEditorPane;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.event.HyperlinkEvent;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.text.html.HTMLEditorKit;

import ij.IJ;
import io.github.biapyx.fiji.BiaPyFiji;

/** Small helpers to keep the look of the panels consistent with BiaPy-GUI. */
public final class UiUtils {

	private UiUtils() {}

	public static JButton primaryButton(String text) {
		JButton b = new JButton(text);
		b.setBackground(BiaPyFiji.BLUE);
		b.setForeground(BiaPyFiji.WHITE);
		b.setOpaque(true);
		b.setBorderPainted(false);
		b.setFocusPainted(false);
		b.setFont(b.getFont().deriveFont(Font.BOLD, 13f));
		b.setBorder(BorderFactory.createEmptyBorder(8, 18, 8, 18));
		b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		return b;
	}

	public static JButton secondaryButton(String text) {
		JButton b = new JButton(text);
		b.setFocusPainted(false);
		b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		return b;
	}

	/** A primary button with a tooltip explaining what it does. */
	public static JButton primaryButton(String text, String tooltip) {
		JButton b = primaryButton(text);
		b.setToolTipText(tooltip);
		return b;
	}

	/** A secondary button with a tooltip explaining what it does. */
	public static JButton secondaryButton(String text, String tooltip) {
		JButton b = secondaryButton(text);
		b.setToolTipText(tooltip);
		return b;
	}

	public static JLabel title(String text, float size) {
		JLabel l = new JLabel(text);
		l.setFont(l.getFont().deriveFont(Font.BOLD, size));
		return l;
	}

	public static JLabel link(String text, String url) {
		return link(text, () -> openUrl(url));
	}

	/** Text shown as a link that runs the given action when clicked. */
	public static JLabel link(String text, Runnable action) {
		JLabel l = new JLabel("<html><a href=''>" + text + "</a></html>");
		l.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		l.addMouseListener(new java.awt.event.MouseAdapter() {
			@Override
			public void mouseClicked(java.awt.event.MouseEvent e) {
				action.run();
			}
		});
		return l;
	}

	public static void openUrl(String url) {
		try {
			if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
				Desktop.getDesktop().browse(new URI(url));
				return;
			}
		} catch (Exception e) {
			// Fall back to ImageJ's launcher
		}
		IJ.runPlugIn("ij.plugin.BrowserLauncher", url);
	}

	public static void openFolder(File folder) {
		try {
			Desktop.getDesktop().open(folder);
		} catch (Exception e) {
			IJ.log("Could not open " + folder + ": " + e.getMessage());
		}
	}

	/**
	 * HTML kit that loads images synchronously: they are small files inside the plugin, and loading them in
	 * the background made the text jump when each image arrived (and left them blank in offscreen renders).
	 */
	private static class SyncImagesKit extends HTMLEditorKit {
		private static final long serialVersionUID = 1L;

		@Override
		public javax.swing.text.ViewFactory getViewFactory() {
			javax.swing.text.ViewFactory base = super.getViewFactory();
			return elem -> {
				javax.swing.text.View v = base.create(elem);
				if (v instanceof javax.swing.text.html.ImageView) ((javax.swing.text.html.ImageView) v).setLoadsSynchronously(true);
				return v;
			};
		}
	}

	/** Read-only HTML pane whose links open in the browser. */
	/**
	 * Style of every window that explains something (help, the workflow confirmation, citations, the charts' (i)...),
	 * so they all look the same: 12px text, an optional title (h1) and section headings (h2). A heading has more
	 * space above than below, so it reads as part of the text that follows it.
	 */
	private static final String HTML_STYLE = "body { font-family: sans-serif; font-size: 12px; margin: 6px }"
			+ " h1 { font-size: 15px; font-weight: bold; margin-top: 0; margin-bottom: 8px }"
			+ " h2 { font-size: 13px; font-weight: bold; color: #2f6fb5; margin-top: 16px; margin-bottom: 4px }"
			+ " p { margin-top: 0; margin-bottom: 6px }"
			+ " ul { margin-top: 0; margin-bottom: 6px }"
			+ " li { margin-bottom: 4px }"
			+ " .muted { color: #777777 }"
			+ " .small { font-size: 11px; color: #555555 }";

	/** An HTML page (for {@link #htmlPane} or a message) with the shared style; {@code body} is its HTML content. */
	public static String htmlPage(String body) {
		return "<html><head><style>" + HTML_STYLE + "</style></head><body>" + body + "</body></html>";
	}

	/**
	 * A window with an explanation (HTML body, shown with {@link #htmlPage}) and a Close button. It does not block.
	 *
	 * @return its text, to change it later (e.g. once something it shows is known)
	 */
	public static JEditorPane helpWindow(Component parent, String title, String body) {
		JDialog d = new JDialog(SwingUtilities.getWindowAncestor(parent), title, java.awt.Dialog.ModalityType.MODELESS);
		JEditorPane text = htmlPane(htmlPage(body));
		text.setCaretPosition(0);
		JScrollPane scroll = new JScrollPane(text);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		scroll.setPreferredSize(new Dimension(620, 520));
		JButton close = secondaryButton("Close", "Close this window");
		close.addActionListener(e -> d.dispose());
		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
		buttons.add(close);
		JPanel content = new JPanel(new BorderLayout(0, 10));
		content.setBorder(BorderFactory.createEmptyBorder(8, 14, 12, 14));
		content.add(scroll, BorderLayout.CENTER);
		content.add(buttons, BorderLayout.SOUTH);
		d.setContentPane(content);
		d.pack();
		d.setLocationRelativeTo(parent);
		d.setVisible(true);
		return text;
	}

	public static JEditorPane htmlPane(String html) {
		JEditorPane p = new JEditorPane();
		p.setEditorKit(new SyncImagesKit());
		p.setEditable(false);
		p.setOpaque(false);
		p.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
		p.setText(html);
		p.addHyperlinkListener(e -> {
			if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED && e.getURL() != null) openUrl(e.getURL().toString());
		});
		return p;
	}

	private static final String PREF_LAST_DIR = "biapy.last_dir";

	/**
	 * Folder where a file chooser opens: the field's current path if it exists, otherwise the last folder
	 * visited in any BiaPy chooser.
	 */
	private static File startDir(String current) {
		if (current != null && !current.trim().isEmpty()) {
			File f = new File(current.trim());
			if (f.isDirectory()) return f;
			if (f.getParentFile() != null && f.getParentFile().isDirectory()) return f.getParentFile();
		}
		File last = new File(ij.Prefs.get(PREF_LAST_DIR, System.getProperty("user.home")));
		return last.isDirectory() ? last : null;
	}

	private static void remember(File selected) {
		File dir = selected.isDirectory() ? selected : selected.getParentFile();
		if (dir != null) ij.Prefs.set(PREF_LAST_DIR, dir.getAbsolutePath());
	}

	public static File chooseDirectory(Component parent, String current) {
		JFileChooser fc = new JFileChooser(startDir(current));
		fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
		if (fc.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION) return null;
		remember(fc.getSelectedFile());
		return fc.getSelectedFile();
	}

	public static File chooseFile(Component parent, String current, String description, String... extensions) {
		JFileChooser fc = new JFileChooser(startDir(current));
		if (extensions.length > 0) fc.setFileFilter(new FileNameExtensionFilter(description, extensions));
		if (fc.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION) return null;
		remember(fc.getSelectedFile());
		return fc.getSelectedFile();
	}

	/**
	 * Dialog text that wraps at a fixed width. Plain text in JOptionPane does not wrap, and long lines were
	 * cut on Windows with display scaling.
	 */
	private static Object wrap(String msg) {
		String html = msg.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>");
		return "<html><body style='width:420px'>" + html + "</body></html>";
	}

	// In headless mode (automated UI tests) dialogs are printed and confirmations accepted
	private static boolean headless() {
		return java.awt.GraphicsEnvironment.isHeadless();
	}

	public static void info(Component parent, String msg) {
		if (headless()) {
			System.out.println("[BiaPy info] " + msg);
			return;
		}
		JOptionPane.showMessageDialog(parent, wrap(msg), "BiaPy", JOptionPane.INFORMATION_MESSAGE);
	}

	public static void error(Component parent, String msg) {
		if (headless()) {
			System.out.println("[BiaPy error] " + msg);
			return;
		}
		JOptionPane.showMessageDialog(parent, wrap(msg), "BiaPy", JOptionPane.ERROR_MESSAGE);
	}

	/** Asks the user to pick one of the options; returns its index, or -1 if the dialog was closed. */
	public static int choose(Component parent, String msg, String... options) {
		if (headless()) {
			System.out.println("[BiaPy choose -> " + options[0] + "] " + msg);
			return 0;
		}
		return JOptionPane.showOptionDialog(parent, wrap(msg), "BiaPy", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE,
				null, options, options[0]);
	}

	/** Confirmation with an HTML message (not escaped) and custom button texts. */
	public static boolean confirmHtml(Component parent, String title, String html, String yes, String no) {
		if (headless()) {
			System.out.println("[BiaPy confirm -> " + yes + "] " + html.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim());
			return true;
		}
		Object[] options = { yes, no };
		return JOptionPane.showOptionDialog(parent, html, title, JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE,
				null, options, options[0]) == 0;
	}

	/**
	 * Confirmation of something that can't be undone: a warning with the action's button and "Cancel", the default
	 * one (Enter or a click by mistake keeps everything).
	 */
	public static boolean confirmWarning(Component parent, String title, String msg, String yes) {
		if (headless()) {
			System.out.println("[BiaPy confirm -> " + yes + "] " + msg);
			return true;
		}
		Object[] options = { yes, "Cancel" };
		return JOptionPane.showOptionDialog(parent, wrap(msg), title, JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE,
				null, options, options[1]) == 0;
	}

	public static boolean confirm(Component parent, String msg) {
		if (headless()) {
			System.out.println("[BiaPy confirm -> yes] " + msg);
			return true;
		}
		return JOptionPane.showConfirmDialog(parent, wrap(msg), "BiaPy", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION;
	}
}
