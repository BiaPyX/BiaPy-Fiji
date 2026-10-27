package io.github.biapyx.fiji.gui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

import javax.swing.JButton;
import javax.swing.Timer;

import io.github.biapyx.fiji.BiaPyFiji;

/**
 * A secondary button that can glow like a beacon, a ring that slowly lights up and fades along its edge, to say "now
 * look here" (e.g. "Workflow info" when a job finishes). It glows until it is clicked or {@link #stopGlow()} is called.
 */
class BeaconButton extends JButton {

	private static final long serialVersionUID = 1L;
	/** BiaPy's blue. */
	private static final Color LIGHT = BiaPyFiji.BLUE;
	/** One pulse, in ms. */
	private static final int PERIOD_MS = 1600;

	private final Timer timer = new Timer(40, e -> repaint());
	private long start;

	BeaconButton(String text, String tooltip) {
		super(text);
		setFocusPainted(false);
		setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		setToolTipText(tooltip);
		addActionListener(e -> stopGlow());
	}

	void startGlow() {
		start = System.currentTimeMillis();
		timer.start();
	}

	void stopGlow() {
		timer.stop();
		repaint();
	}

	boolean isGlowing() {
		return timer.isRunning();
	}

	/** How lit the beacon is, from 0 (off) to 1, at the given time since it started. */
	static float intensity(long elapsedMs) {
		return (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * (elapsedMs % PERIOD_MS) / PERIOD_MS));
	}

	@Override
	protected void paintComponent(Graphics g) {
		super.paintComponent(g);
		if (!timer.isRunning()) return;
		paintGlow((Graphics2D) g.create(), intensity(System.currentTimeMillis() - start));
	}

	/** The ring and a light tint inside the edge, with ``level`` from 0 to 1 (package visible for tests). */
	void paintGlow(Graphics2D g2, float level) {
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		int w = getWidth(), h = getHeight(), arc = 10;
		g2.setColor(new Color(LIGHT.getRed(), LIGHT.getGreen(), LIGHT.getBlue(), Math.round(45 * level)));
		g2.fillRoundRect(1, 1, w - 2, h - 2, arc, arc);
		g2.setStroke(new BasicStroke(2.5f));
		g2.setColor(new Color(LIGHT.getRed(), LIGHT.getGreen(), LIGHT.getBlue(), Math.round(70 + 185 * level)));
		g2.drawRoundRect(2, 2, w - 5, h - 5, arc, arc);
		g2.dispose();
	}
}
