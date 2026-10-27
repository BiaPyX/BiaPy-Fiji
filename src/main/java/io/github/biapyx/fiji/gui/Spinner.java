package io.github.biapyx.fiji.gui;

import java.awt.BasicStroke;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

import javax.swing.JComponent;
import javax.swing.Timer;

import io.github.biapyx.fiji.BiaPyFiji;

/** Small rotating arc shown while something is being checked in the background. */
public class Spinner extends JComponent {

	private static final long serialVersionUID = 1L;

	private int angle = 0;
	private final Timer timer = new Timer(40, e -> {
		angle = (angle + 12) % 360;
		repaint();
	});

	public Spinner(int size) {
		setPreferredSize(new Dimension(size, size));
		setMinimumSize(new Dimension(size, size));
		setMaximumSize(new Dimension(size, size));
		setVisible(false);
	}

	/** Shows the spinner and animates it, or hides it. */
	public void spin(boolean on) {
		setVisible(on);
		if (on) timer.start();
		else timer.stop();
	}

	@Override
	protected void paintComponent(Graphics g0) {
		Graphics2D g = (Graphics2D) g0.create();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		int s = Math.min(getWidth(), getHeight()) - 4;
		int x = (getWidth() - s) / 2, y = (getHeight() - s) / 2;
		g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.setColor(BiaPyFiji.LIGHT_BLUE);
		g.drawOval(x, y, s, s);
		g.setColor(BiaPyFiji.BLUE);
		g.drawArc(x, y, s, s, -angle, 100);
		g.dispose();
	}
}
