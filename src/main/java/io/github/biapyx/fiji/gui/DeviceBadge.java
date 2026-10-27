package io.github.biapyx.fiji.gui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JComponent;

import io.github.biapyx.fiji.BiaPyFiji;

/**
 * The device a run uses, drawn as small figures: a processor for the CPU, a graphics card per GPU and a chip for
 * Apple silicon. The full description (e.g. the name of each GPU) shows when the mouse is over them.
 */
public class DeviceBadge extends JComponent {

	private static final long serialVersionUID = 1L;
	private static final int ICON = 38, GAP = 8, CAPTION = 14;
	private static final Color INK = new Color(60, 70, 90), FILL = new Color(232, 241, 255), ACCENT = BiaPyFiji.BLUE;

	enum Kind { CPU, GPU, APPLE }

	private final Kind kind;
	/** Caption under each figure, e.g. "GPU 0". */
	private final List<String> captions = new ArrayList<>();

	/**
	 * @param description the device as the run tab describes it: "CPU", "MPS : Apple Silicon", "GPU 0 : name (8 GB)"
	 *            or "GPUs 0, 1 (distributed)" followed by a line per GPU
	 */
	/** Size of the figures relative to their design size (38 px). */
	private final double scale;

	public DeviceBadge(String description) {
		this(description, 1);
	}

	public DeviceBadge(String description, double scale) {
		this.scale = scale;
		String[] lines = description.split("\n");
		String first = lines[0].trim();
		if (first.startsWith("GPU")) {
			kind = Kind.GPU;
			if (first.startsWith("GPUs")) {
				for (String id : first.replaceAll("^GPUs\\s*|\\s*\\(.*$", "").split(","))
					if (!id.trim().isEmpty()) captions.add("GPU " + id.trim());
			} else {
				captions.add(first.replaceAll("\\s*:.*$", ""));
			}
		} else if (first.startsWith("MPS")) {
			kind = Kind.APPLE;
			captions.add("Apple GPU");
		} else {
			kind = Kind.CPU;
			captions.add("CPU");
		}
		StringBuilder tip = new StringBuilder("<html><b>Device</b>");
		if (kind == Kind.CPU) tip.append("<br>CPU: ").append(cpuName());
		else for (int i = kind == Kind.GPU && first.startsWith("GPUs") ? 1 : 0; i < lines.length; i++)
			tip.append("<br>").append(lines[i].trim());
		if (kind == Kind.GPU && first.startsWith("GPUs")) tip.append("<br><i>").append(captions.size()).append(" GPUs, distributed training</i>");
		setToolTipText(tip.append("</html>").toString());
		int n = captions.size();
		setPreferredSize(new Dimension((int) Math.ceil((n * slot() + (n - 1) * GAP + 4) * scale),
				(int) Math.ceil((ICON + CAPTION + 2) * scale)));
	}

	/** Width of a figure with its caption. */
	private int slot() {
		FontMetrics fm = getFontMetrics(captionFont());
		int w = ICON;
		for (String c : captions) w = Math.max(w, fm.stringWidth(c) + 4);
		return w;
	}

	private Font captionFont() {
		return (getFont() != null ? getFont() : new javax.swing.JLabel().getFont()).deriveFont(Font.PLAIN, 10f);
	}

	/** Processor name, from the operating system (or the architecture and cores if it can't be told). */
	static String cpuName() {
		String name = null;
		try {
			String os = System.getProperty("os.name", "").toLowerCase();
			if (os.contains("win")) {
				name = System.getenv("PROCESSOR_IDENTIFIER");
			} else if (os.contains("mac")) {
				Process p = new ProcessBuilder("sysctl", "-n", "machdep.cpu.brand_string").start();
				name = new String(p.getInputStream().readAllBytes()).trim();
			} else {
				for (String l : java.nio.file.Files.readAllLines(java.nio.file.Paths.get("/proc/cpuinfo")))
					if (l.startsWith("model name")) {
						name = l.substring(l.indexOf(':') + 1).trim();
						break;
					}
			}
		} catch (Exception e) {
			// Below
		}
		int cores = Runtime.getRuntime().availableProcessors();
		return (name == null || name.isEmpty() ? System.getProperty("os.arch") : name) + " (" + cores + " threads)";
	}

	@Override
	protected void paintComponent(Graphics g0) {
		Graphics2D g = (Graphics2D) g0.create();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.scale(scale, scale);
		int slot = slot();
		int total = captions.size() * slot + (captions.size() - 1) * GAP;
		int x = (int) Math.round((getWidth() / scale - total) / 2); // centered
		for (String caption : captions) {
			int ix = x + (slot - ICON) / 2;
			switch (kind) {
			case GPU:
				drawGpu(g, ix, 1);
				break;
			case APPLE:
				drawChip(g, ix, 1, "M", false);
				break;
			default:
				drawChip(g, ix, 1, "CPU", true);
			}
			g.setFont(captionFont());
			FontMetrics fm = g.getFontMetrics();
			g.setColor(INK);
			g.drawString(caption, x + (slot - fm.stringWidth(caption)) / 2, ICON + CAPTION - 2);
			x += slot + GAP;
		}
		g.dispose();
	}

	/** A processor: a square die with pins on every side, or a rounded chip without them (Apple silicon). */
	private static void drawChip(Graphics2D g, int x, int y, String label, boolean pins) {
		int m = 7, s = ICON - 2 * m;
		if (pins) {
			g.setColor(INK);
			g.setStroke(new BasicStroke(1.6f));
			for (int i = 0; i < 4; i++) {
				int p = x + m + 4 + i * (s - 8) / 3;
				g.drawLine(p, y + 1, p, y + m);
				g.drawLine(p, y + m + s, p, y + ICON - 1);
				int q = y + m + 4 + i * (s - 8) / 3;
				g.drawLine(x + 1, q, x + m, q);
				g.drawLine(x + m + s, q, x + ICON - 1, q);
			}
		}
		RoundRectangle2D body = pins ? new RoundRectangle2D.Double(x + m, y + m, s, s, 4, 4)
				: new RoundRectangle2D.Double(x + 3, y + 3, ICON - 6, ICON - 6, 10, 10);
		g.setColor(FILL);
		g.fill(body);
		g.setColor(pins ? INK : ACCENT);
		g.setStroke(new BasicStroke(1.8f));
		g.draw(body);
		g.setFont(g.getFont().deriveFont(Font.BOLD, pins ? 8f : 15f));
		FontMetrics fm = g.getFontMetrics();
		g.setColor(pins ? INK : ACCENT);
		g.drawString(label, x + (ICON - fm.stringWidth(label)) / 2, y + (ICON + fm.getAscent() - fm.getDescent()) / 2);
		g.setFont(g.getFont().deriveFont(Font.PLAIN, 10f));
	}

	/** A graphics card: a board with a fan, its bracket on the left and the connector at the bottom. */
	private static void drawGpu(Graphics2D g, int x, int y) {
		int top = y + 7, h = ICON - 16;
		g.setStroke(new BasicStroke(1.8f));
		g.setColor(INK);
		g.drawLine(x + 2, top - 3, x + 2, top + h + 5); // bracket
		RoundRectangle2D board = new RoundRectangle2D.Double(x + 5, top, ICON - 7, h, 5, 5);
		g.setColor(FILL);
		g.fill(board);
		g.setColor(INK);
		g.draw(board);
		g.setColor(ACCENT);
		g.fillRect(x + 12, top + h, ICON - 22, 4); // connector
		// Fan with blades
		double cx = x + 5 + (ICON - 7) * 0.62, cy = top + h / 2.0, r = h / 2.0 - 2.5;
		g.setColor(INK);
		g.draw(new Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r));
		g.setStroke(new BasicStroke(1.4f));
		for (int i = 0; i < 4; i++) {
			double a = i * Math.PI / 2 + 0.4;
			g.drawLine((int) Math.round(cx), (int) Math.round(cy), (int) Math.round(cx + Math.cos(a) * (r - 1)),
					(int) Math.round(cy + Math.sin(a) * (r - 1)));
		}
		g.setColor(ACCENT);
		g.fillRect(x + 9, top + 4, 4, h - 8); // memory
	}
}
