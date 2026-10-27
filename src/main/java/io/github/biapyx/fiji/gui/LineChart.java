package io.github.biapyx.fiji.gui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JPanel;

import io.github.biapyx.fiji.BiaPyFiji;

/** Minimal line chart (value per epoch) for the training curves, drawn with Java2D. */
public class LineChart extends JPanel {

	private static final long serialVersionUID = 1L;

	/** One curve: solid for training, dashed for validation. */
	public static class Series {
		final String name;
		final Color color;
		final boolean dashed;
		final List<Double> values = new ArrayList<>();

		public Series(String name, Color color, boolean dashed) {
			this.name = name;
			this.color = color;
			this.dashed = dashed;
		}
	}

	private String title;
	private List<Series> series = new ArrayList<>();

	/** Explanation shown with the (i) next to the title (none if null). */
	private String info;
	private static final int INFO_SIZE = 16;

	public LineChart(String title) {
		setTitle(title);
		setBackground(BiaPyFiji.WHITE);
		setPreferredSize(new Dimension(300, 200));
		java.awt.event.MouseAdapter mouse = new java.awt.event.MouseAdapter() {
			@Override
			public void mouseClicked(java.awt.event.MouseEvent e) {
				if (info != null && infoBounds().contains(e.getPoint())) showInfo();
			}

			@Override
			public void mouseMoved(java.awt.event.MouseEvent e) {
				boolean over = info != null && infoBounds().contains(e.getPoint());
				setCursor(java.awt.Cursor.getPredefinedCursor(over ? java.awt.Cursor.HAND_CURSOR : java.awt.Cursor.DEFAULT_CURSOR));
			}
		};
		addMouseListener(mouse);
		addMouseMotionListener(mouse);
	}

	/** HTML explanation of what the chart shows, opened with the (i) next to the title. */
	public void setInfo(String html) {
		this.info = html;
		repaint();
	}

	private java.awt.Rectangle infoBounds() {
		return new java.awt.Rectangle(getWidth() - INFO_SIZE - 8, 4, INFO_SIZE, INFO_SIZE);
	}

	private void showInfo() {
		showInfo(this, title, info);
	}

	/** The explanation opened with an (i): its HTML in a dialog with the given title. */
	static void showInfo(java.awt.Component parent, String title, String html) {
		javax.swing.JLabel text = new javax.swing.JLabel(UiUtils.htmlPage("<div style='width:340px'>" + html + "</div>"));
		javax.swing.JOptionPane.showMessageDialog(parent, text, title, javax.swing.JOptionPane.INFORMATION_MESSAGE);
	}

	/** The (i) as an icon of the given size, e.g. next to a label. */
	static javax.swing.Icon infoIcon(int size) {
		return new javax.swing.Icon() {
			@Override
			public void paintIcon(java.awt.Component c, Graphics g, int x, int y) {
				Graphics2D g2 = (Graphics2D) g.create();
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				drawInfoIcon(g2, new java.awt.Rectangle(x, y, size, size));
				g2.dispose();
			}

			@Override
			public int getIconWidth() {
				return size;
			}

			@Override
			public int getIconHeight() {
				return size;
			}
		};
	}

	/**
	 * The (i): a blue circle with the "i" drawn as a dot and a stem, both on the circle's vertical axis. Shapes and not
	 * a letter: a font's "i" carries its own side bearings and hinting, and each system's font left it off-center.
	 */
	private static void drawInfoIcon(Graphics2D g, java.awt.Rectangle r) {
		Graphics2D g2 = (Graphics2D) g.create();
		g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
		double d = r.width, cx = r.x + d / 2.0, top = r.y;
		g2.setColor(BiaPyFiji.BLUE);
		g2.fill(new java.awt.geom.Ellipse2D.Double(r.x, top, d, d));
		g2.setColor(BiaPyFiji.WHITE);
		double dot = d * 0.17, stem = d * 0.15;
		g2.fill(new java.awt.geom.Ellipse2D.Double(cx - dot / 2, top + d * 0.2, dot, dot));
		g2.fill(new java.awt.geom.RoundRectangle2D.Double(cx - stem / 2, top + d * 0.43, stem, d * 0.37, stem, stem));
		g2.dispose();
	}

	/** Title above the plot; shortened with "..." when it doesn't fit (the whole title is the tooltip). */
	public void setTitle(String title) {
		this.title = title;
		setToolTipText(title);
		repaint();
	}

	public void setSeries(List<Series> series) {
		this.series = series;
		repaint();
	}

	@Override
	protected void paintComponent(Graphics g0) {
		super.paintComponent(g0);
		Graphics2D g = (Graphics2D) g0.create();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		int w = getWidth(), h = getHeight();
		Font base = getFont().deriveFont(Font.PLAIN, 11f);
		g.setFont(base.deriveFont(Font.BOLD, 12f));
		g.setColor(Color.DARK_GRAY);
		g.drawString(fit(title, g.getFontMetrics(), w - 16 - (info != null ? INFO_SIZE + 10 : 0)), 8, 16);
		if (info != null) {
			java.awt.Rectangle r = infoBounds();
			drawInfoIcon(g, r);
			g.setColor(Color.DARK_GRAY);
		}
		g.setFont(base);
		FontMetrics fm = g.getFontMetrics();

		int left = 48, right = 10, top = 26, bottom = 40;
		int pw = w - left - right, ph = h - top - bottom;
		if (pw < 20 || ph < 20) return;

		int n = 0;
		double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
		for (Series s : series) {
			n = Math.max(n, s.values.size());
			for (Double v : s.values) {
				if (v == null || v.isNaN() || v.isInfinite()) continue;
				min = Math.min(min, v);
				max = Math.max(max, v);
			}
		}
		if (n == 0 || min == Double.POSITIVE_INFINITY) {
			// Placeholder centred in the whole chart (the axes margins are not needed yet)
			g.setColor(new Color(225, 225, 225));
			g.drawRect(10, top, w - 20, ph);
			g.setColor(BiaPyFiji.GREY);
			String msg = "Waiting for the first epoch . . .";
			g.drawString(msg, (w - fm.stringWidth(msg)) / 2, top + ph / 2 + fm.getAscent() / 2);
			g.dispose();
			return;
		}
		g.setColor(new Color(225, 225, 225));
		g.drawRect(left, top, pw, ph);
		if (max - min < 1e-9) {
			max += 0.5;
			min -= 0.5;
		}
		double pad = (max - min) * 0.05;
		min -= pad;
		max += pad;

		// Horizontal grid with value labels
		for (int i = 0; i <= 4; i++) {
			int y = top + ph - i * ph / 4;
			double v = min + i * (max - min) / 4;
			g.setColor(new Color(235, 235, 235));
			g.drawLine(left + 1, y, left + pw - 1, y);
			g.setColor(BiaPyFiji.GREY);
			String s = format(v);
			g.drawString(s, left - 4 - fm.stringWidth(s), y + fm.getAscent() / 2 - 1);
		}
		// Epoch labels (first, middle, last)
		int[] ticks = n == 1 ? new int[] { 0 } : new int[] { 0, (n - 1) / 2, n - 1 };
		for (int t : ticks) {
			int x = xFor(t, n, left, pw);
			String s = String.valueOf(t + 1);
			g.drawString(s, x - fm.stringWidth(s) / 2, top + ph + fm.getAscent() + 2);
		}
		g.drawString("epoch", left + pw - fm.stringWidth("epoch"), top + ph + 2 * fm.getHeight());

		// Curves
		for (Series s : series) {
			g.setColor(s.color);
			g.setStroke(s.dashed ? new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[] { 5f, 4f }, 0f)
					: new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			int px = -1, py = -1;
			for (int i = 0; i < s.values.size(); i++) {
				Double v = s.values.get(i);
				if (v == null || v.isNaN() || v.isInfinite()) continue;
				int x = xFor(i, n, left, pw);
				int y = top + ph - (int) Math.round((v - min) / (max - min) * ph);
				if (px >= 0) g.drawLine(px, py, x, y);
				else if (s.values.size() == 1) g.fillOval(x - 3, y - 3, 6, 6);
				px = x;
				py = y;
			}
		}

		// Legend under the plot
		g.setStroke(new BasicStroke(1.8f));
		int lx = left, ly = top + ph + 2 * fm.getHeight();
		for (Series s : series) {
			int sw = fm.stringWidth(s.name);
			if (lx + 26 + sw > left + pw - fm.stringWidth("epoch") - 10) break;
			g.setColor(s.color);
			if (s.dashed) {
				g.drawLine(lx, ly - 4, lx + 5, ly - 4);
				g.drawLine(lx + 9, ly - 4, lx + 14, ly - 4);
			} else {
				g.drawLine(lx, ly - 4, lx + 14, ly - 4);
			}
			g.setColor(Color.DARK_GRAY);
			g.drawString(s.name, lx + 18, ly);
			lx += 26 + sw;
		}
		g.dispose();
	}

	private static String fit(String s, FontMetrics fm, int width) {
		if (fm.stringWidth(s) <= width) return s;
		while (s.length() > 1 && fm.stringWidth(s + "...") > width) s = s.substring(0, s.length() - 1);
		return s + "...";
	}

	private static int xFor(int i, int n, int left, int pw) {
		return n <= 1 ? left + pw / 2 : left + (int) Math.round((double) i / (n - 1) * pw);
	}

	private static String format(double v) {
		double a = Math.abs(v);
		if (a != 0 && (a < 0.01 || a >= 10000)) return String.format("%.1e", v);
		if (a < 1) return String.format("%.3f", v);
		if (a < 100) return String.format("%.2f", v);
		return String.format("%.0f", v);
	}
}
