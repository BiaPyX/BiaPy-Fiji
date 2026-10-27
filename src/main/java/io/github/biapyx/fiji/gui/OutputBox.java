package io.github.biapyx.fiji.gui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultCaret;
import javax.swing.text.Document;
import javax.swing.text.Element;

import io.github.biapyx.fiji.BiaPyFiji;

/**
 * Raw output of a process (installation, BiaPy run) in a light text box that can be shown or hidden with
 * a link. Text can be appended from any thread.
 * <p>
 * It always shows the last lines: the user can scroll up while holding the scroll bar, and when it is released it
 * goes back to the end (if new text arrived meanwhile, so the output of a finished process can be read freely).
 * Scrolling up with the mouse wheel stops it for {@link #WHEEL_PAUSE_MS}. Only the last {@link #MAX_LINES} lines
 * are kept and long lines are cut, so the box stays light and its horizontal scroll bar fits what it shows (the
 * full output is in the log files).
 */
public class OutputBox extends JPanel {

	private static final long serialVersionUID = 1L;
	/** Lines kept (about 150 kB for lines of 75 characters). */
	static final int MAX_LINES = 1000;
	/** Longer lines are cut (e.g. BiaPy lists every training image in one line). */
	static final int MAX_LINE_CHARS = 300;
	private static final String CUT = " [... line cut, the full log has it]";
	private static final String EARLIER = "[... earlier lines are not shown here, the full log has them]\n";
	/** After scrolling with the wheel, how long the box waits before going back to the end. */
	static final int WHEEL_PAUSE_MS = 5000;

	private final JLabel toggle = new JLabel();
	private final JPanel topRow = new JPanel(new BorderLayout());
	private final JTextArea text = new JTextArea();
	private final JScrollPane scroll = new JScrollPane(text);
	private final String showText;
	private final String hideText;

	// Only used on the EDT
	private boolean holding = false; // a mouse button is pressed on the scroll bar
	private boolean pending = false; // new text arrived while the user was looking elsewhere
	private boolean trimmed = false; // earlier lines were dropped (the first line says so)
	private final Timer wheelPause = new Timer(WHEEL_PAUSE_MS, e -> toEndIfPending());

	/**
	 * @param showText link text while hidden, e.g. "Show details"
	 * @param hideText link text while shown
	 * @param height preferred height of the text box (it can grow if the layout gives more space)
	 */
	public OutputBox(String showText, String hideText, int height) {
		super(new BorderLayout(0, 4));
		this.showText = showText;
		this.hideText = hideText;
		setOpaque(false);

		toggle.setForeground(BiaPyFiji.BLUE);
		toggle.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		toggle.addMouseListener(new MouseAdapter() {
			@Override
			public void mousePressed(MouseEvent e) {
				setOutputVisible(!scroll.isVisible());
			}
		});
		topRow.setOpaque(false);
		topRow.add(toggle, BorderLayout.WEST);
		add(topRow, BorderLayout.NORTH);

		text.setEditable(false);
		text.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
		// Scrolling is done here, not by the caret (which stops following once the user clicks the text)
		((DefaultCaret) text.getCaret()).setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
		// The scroll bar is free while it is held (thumb, track or arrows), and goes back to the end when released
		MouseAdapter hold = new MouseAdapter() {
			@Override
			public void mousePressed(MouseEvent e) {
				holding = true;
			}

			@Override
			public void mouseReleased(MouseEvent e) {
				holding = false;
				toEndIfPending();
			}
		};
		JScrollBar bar = scroll.getVerticalScrollBar();
		bar.addMouseListener(hold);
		for (Component c : bar.getComponents()) c.addMouseListener(hold); // the arrow buttons
		// Scrolling up with the wheel pauses it; back at the end, it follows again at once
		wheelPause.setRepeats(false);
		scroll.addMouseWheelListener(e -> SwingUtilities.invokeLater(() -> {
			if (atEnd()) wheelPause.stop();
			else wheelPause.restart();
		}));
		scroll.setPreferredSize(new Dimension(0, height));
		scroll.setBorder(BorderFactory.createLineBorder(BiaPyFiji.LIGHT_BLUE));
		add(scroll, BorderLayout.CENTER);
		setOutputVisible(false);
	}

	/** Output always visible, without the show/hide link (e.g. in a tab of its own). */
	public void alwaysShown() {
		setOutputVisible(true);
		toggle.setVisible(false);
	}

	/** Adds a component (e.g. a button) at the right end of the show/hide row. */
	public void addRight(javax.swing.JComponent c) {
		topRow.add(c, BorderLayout.EAST);
	}

	public void setOutputVisible(boolean visible) {
		scroll.setVisible(visible);
		toggle.setText(visible ? hideText : showText);
		toggle.setToolTipText(visible ? "Hide the text BiaPy prints while it runs" : "Show the text BiaPy prints while it runs");
		revalidate();
		repaint();
	}

	public boolean isOutputVisible() {
		return scroll.isVisible();
	}

	/** Appends text, dropping empty lines (progress redraws and line-break chunks produce many). */
	public void append(String chunk) {
		if (chunk == null) return;
		StringBuilder sb = new StringBuilder();
		for (String line : chunk.split("[\\r\\n]+")) {
			if (line.trim().isEmpty()) continue;
			line = line.replaceAll("\\s+$", "");
			if (line.length() > MAX_LINE_CHARS) line = line.substring(0, MAX_LINE_CHARS) + CUT;
			sb.append(line).append("\n");
		}
		if (sb.length() == 0) return;
		SwingUtilities.invokeLater(() -> {
			text.append(sb.toString());
			dropEarlyLines();
			pending = true;
			if (!holding && !wheelPause.isRunning()) toEnd();
		});
	}

	/** Keeps the last {@link #MAX_LINES} lines, after a first line that says that earlier ones were dropped. */
	private void dropEarlyLines() {
		Document doc = text.getDocument();
		Element root = doc.getDefaultRootElement();
		// The last element is the empty line after the final "\n"
		int lines = root.getElementCount() - 1 - (trimmed ? 1 : 0);
		if (lines <= MAX_LINES) return;
		try {
			if (!trimmed) {
				doc.insertString(0, EARLIER, null);
				trimmed = true;
			}
			int from = root.getElement(1).getStartOffset();
			int to = root.getElement(1 + lines - MAX_LINES).getStartOffset();
			doc.remove(from, to - from);
		} catch (BadLocationException e) {
			// Ignore
		}
	}

	private void toEndIfPending() {
		if (pending && !holding && !wheelPause.isRunning()) toEnd();
	}

	private void toEnd() {
		scroll.validate(); // the new size, now, to scroll to the real end
		JScrollBar bar = scroll.getVerticalScrollBar();
		bar.setValue(bar.getMaximum());
		pending = false;
	}

	/** The scroll bar is at the end (within a line, so a wheel stop just above it counts). */
	private boolean atEnd() {
		JScrollBar bar = scroll.getVerticalScrollBar();
		return bar.getValue() + bar.getVisibleAmount() >= bar.getMaximum() - text.getFontMetrics(text.getFont()).getHeight();
	}

	public void clear() {
		SwingUtilities.invokeLater(() -> {
			text.setText("");
			trimmed = false;
			pending = false;
		});
	}
}
