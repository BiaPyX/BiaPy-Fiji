package io.github.biapyx.fiji.gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.Timer;

import io.github.biapyx.fiji.BiaPyFiji;

/**
 * Holds a component (e.g. a primary button, whose own fill would hide {@link BeaconButton}'s ring) and glows around
 * it: a halo that slowly lights up and fades, with the same pulse as BeaconButton, to say "now click here".
 */
class BeaconFrame extends JPanel {

	private static final long serialVersionUID = 1L;
	/** Width of the halo around the component. */
	private static final int HALO = 7;

	private final Timer timer = new Timer(40, e -> repaint());
	private long start;

	BeaconFrame(JComponent content) {
		super(new BorderLayout());
		setOpaque(false);
		setBorder(BorderFactory.createEmptyBorder(HALO, HALO, HALO, HALO));
		add(content, BorderLayout.CENTER);
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

	@Override
	protected void paintComponent(Graphics g) {
		super.paintComponent(g);
		if (!timer.isRunning()) return;
		paintGlow((Graphics2D) g.create(), BeaconButton.intensity(System.currentTimeMillis() - start));
	}

	/** The halo, fading outwards, with ``level`` from 0 to 1 (package visible for tests). */
	void paintGlow(Graphics2D g2, float level) {
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		Color c = BiaPyFiji.BLUE;
		int w = getWidth(), h = getHeight();
		for (int i = 0; i < HALO; i++) {
			// i = 0 is the outer ring, the faintest
			float strength = (i + 1f) / HALO;
			g2.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.round(level * 230 * strength)));
			g2.drawRoundRect(i, i, w - 1 - 2 * i, h - 1 - 2 * i, 2 * (HALO - i) + 6, 2 * (HALO - i) + 6);
		}
		g2.dispose();
	}
}
