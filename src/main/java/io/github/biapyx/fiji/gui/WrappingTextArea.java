package io.github.biapyx.fiji.gui;

import java.awt.Container;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Insets;

import javax.swing.JTextArea;

/**
 * Non-editable text that wraps to the width of its parent and reports the height of the wrapped text. A plain
 * JTextArea computes its preferred height with the width it had before being laid out, so in a layout it
 * often shows just its first line.
 */
public class WrappingTextArea extends JTextArea {

	private static final long serialVersionUID = 1L;

	public WrappingTextArea(String text) {
		super(text);
		setLineWrap(true);
		setWrapStyleWord(true);
		setEditable(false);
		setFocusable(false);
		setOpaque(false);
		setBorder(null);
	}

	/**
	 * Height of the text wrapped (by words, as it is painted) to the width of the parent, counted here: the text
	 * area's own count depends on the size it had in the last layout, and a line added since then was cut.
	 */
	@Override
	public Dimension getPreferredSize() {
		Container p = getParent();
		if (p == null || p.getWidth() <= 0) return super.getPreferredSize();
		Insets pin = p.getInsets(), in = getInsets();
		int w = p.getWidth() - pin.left - pin.right;
		FontMetrics fm = getFontMetrics(getFont());
		int lines = 0;
		for (String paragraph : getText().split("\n", -1)) lines += wrappedLines(paragraph, fm, w - in.left - in.right);
		return new Dimension(w, Math.max(1, lines) * fm.getHeight() + in.top + in.bottom);
	}

	/** Never squeezed below the height of its text (e.g. by a GridBagLayout short of space). */
	@Override
	public Dimension getMinimumSize() {
		return getPreferredSize();
	}

	private static int wrappedLines(String text, FontMetrics fm, int width) {
		int lines = 1, x = 0;
		for (String word : text.split("(?<=\\s)")) {
			int ww = fm.stringWidth(word);
			if (x > 0 && x + fm.stringWidth(word.trim()) > width) {
				lines++;
				x = 0;
			}
			x += ww;
		}
		return lines;
	}
}
