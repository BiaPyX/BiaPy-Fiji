package io.github.biapyx.fiji.gui;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingConstants;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.github.biapyx.fiji.BiaPyFiji;

/**
 * The "Job history" page: the last runs started from the plugin (see {@link RunHistory}), newest first and grouped by
 * day, so the user can find them again: what they were, when and how they ended, and where their results are.
 */
public class HistoryPanel extends JPanel {

	private static final long serialVersionUID = 1L;

	private static final Color CARD_BORDER = new Color(218, 226, 236);
	private static final Color CARD_HOVER = new Color(248, 251, 255);
	private static final Color TEXT_GREY = new Color(110, 110, 110);
	private static final Color INTERRUPTED = new Color(185, 125, 50);
	private static final int ACCENT = 5;
	private static final String RUNNING = JobPanel.Status.RUNNING.name();

	private final MainPanel main;
	private final JPanel list = new WidthTrackingPanel();
	private final JLabel stats = new JLabel(" ");
	private final JTextField search = new JTextField(16);
	private final JButton clear = UiUtils.secondaryButton("Clear history",
			"Remove all the runs from this list (asks first). Their results stay in their folders");

	public HistoryPanel(MainPanel main) {
		super(new BorderLayout(0, 12));
		this.main = main;
		setBackground(BiaPyFiji.WHITE);
		setBorder(BorderFactory.createEmptyBorder(20, 24, 12, 24));

		JPanel titleRow = new JPanel(new BorderLayout(10, 0));
		titleRow.setOpaque(false);
		titleRow.add(UiUtils.title("Job history", 22f), BorderLayout.WEST);
		JPanel tools = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
		tools.setOpaque(false);
		// FlatLaf (Fiji's look) shows the placeholder; other looks ignore it
		search.putClientProperty("JTextField.placeholderText", "Search runs");
		search.putClientProperty("JTextField.showClearButton", Boolean.TRUE);
		search.setToolTipText("Show only the runs whose name, workflow, model, folder or status contain this text");
		search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
			@Override
			public void insertUpdate(javax.swing.event.DocumentEvent e) {
				refresh();
			}

			@Override
			public void removeUpdate(javax.swing.event.DocumentEvent e) {
				refresh();
			}

			@Override
			public void changedUpdate(javax.swing.event.DocumentEvent e) {
				refresh();
			}
		});
		clear.addActionListener(e -> {
			int n = RunHistory.runs().size();
			if (UiUtils.confirmWarning(this, "Clear history", "Remove the " + n + (n == 1 ? " run" : " runs")
					+ " from the history?\nThis cannot be undone. Their results stay in their folders.", "Clear history"))
				RunHistory.clear();
		});
		tools.add(search);
		tools.add(clear);
		titleRow.add(tools, BorderLayout.EAST);
		JLabel subtitle = new JLabel("The last runs started from this plugin, newest first, to find them and their results again.");
		subtitle.setFont(subtitle.getFont().deriveFont(Font.PLAIN, 12f));
		subtitle.setForeground(TEXT_GREY);
		stats.setFont(stats.getFont().deriveFont(Font.PLAIN, 12f));
		JPanel header = new JPanel(new BorderLayout(0, 4));
		header.setOpaque(false);
		header.add(titleRow, BorderLayout.NORTH);
		header.add(subtitle, BorderLayout.CENTER);
		header.add(stats, BorderLayout.SOUTH);
		add(header, BorderLayout.NORTH);

		list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
		list.setBackground(BiaPyFiji.WHITE);
		list.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 4));
		JScrollPane scroll = new JScrollPane(list, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(null);
		scroll.getViewport().setBackground(BiaPyFiji.WHITE);
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		add(scroll, BorderLayout.CENTER);

		RunHistory.addListener(this::refresh);
		refresh();
	}

	/** Rebuilds the list (after a change, or to update the times when the page is shown). */
	public void refresh() {
		List<JsonObject> all = RunHistory.runs();
		String q = search.getText().trim().toLowerCase(Locale.ROOT);
		List<JsonObject> shown = new ArrayList<>();
		for (JsonObject r : all) if (q.isEmpty() || haystack(r).contains(q)) shown.add(r);

		stats.setText(statsText(all));
		clear.setEnabled(!all.isEmpty());
		list.removeAll();
		if (shown.isEmpty()) {
			list.add(emptyState(all.isEmpty()));
		} else {
			String day = null;
			for (JsonObject r : shown) {
				long started = number(r, "started");
				String d = dayText(started);
				if (!d.equals(day)) {
					day = d;
					list.add(dayHeader(d, list.getComponentCount() == 0));
				}
				list.add(card(r));
				list.add(Box.createVerticalStrut(8));
			}
		}
		list.add(Box.createVerticalGlue());
		list.revalidate();
		list.repaint();
	}

	private static String haystack(JsonObject r) {
		StringBuilder sb = new StringBuilder();
		for (String k : new String[] { "name", "workflow_text", "model", "checkpoint", "folder", "config", "device" }) {
			String v = RunHistory.string(r, k);
			if (v != null) sb.append(v).append('\n');
		}
		sb.append(statusLabel(RunHistory.status(r)));
		return sb.toString().toLowerCase(Locale.ROOT);
	}

	/** "12 runs  ● 9 finished  ● 2 failed ...", each status in its color. */
	private static String statsText(List<JsonObject> all) {
		if (all.isEmpty()) return " ";
		java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
		for (String s : new String[] { "RUNNING", "FINISHED", "FAILED", "STOPPED", "INTERRUPTED" }) counts.put(s, 0);
		for (JsonObject r : all) counts.merge(RunHistory.status(r), 1, Integer::sum);
		StringBuilder sb = new StringBuilder("<html><b>").append(all.size()).append(all.size() == 1 ? " run" : " runs").append("</b>");
		for (java.util.Map.Entry<String, Integer> e : counts.entrySet()) {
			if (e.getValue() == 0) continue;
			sb.append(" &nbsp;&nbsp;<span style='color:").append(hex(statusColor(e.getKey()))).append("'>●</span> ")
					.append(e.getValue()).append(' ').append(statusLabel(e.getKey()).toLowerCase(Locale.ROOT));
		}
		return sb.append("</html>").toString();
	}

	private JPanel emptyState(boolean noRuns) {
		JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setOpaque(false);
		p.setBorder(BorderFactory.createEmptyBorder(50, 0, 0, 0));
		JLabel icon = new JLabel(new ClockIcon(56, new Color(190, 205, 225)));
		JLabel title = new JLabel(noRuns ? "No runs yet" : "No runs match \"" + search.getText().trim() + "\"");
		title.setFont(title.getFont().deriveFont(Font.BOLD, 16f));
		JLabel text = new JLabel(noRuns ? "Every job started from Run Workflow will be listed here." : "Try another search.");
		text.setForeground(TEXT_GREY);
		p.add(icon);
		p.add(Box.createVerticalStrut(10));
		p.add(title);
		p.add(Box.createVerticalStrut(4));
		p.add(text);
		if (noRuns) {
			JButton go = UiUtils.primaryButton("Go to Run Workflow", "Choose a configuration and run it");
			go.addActionListener(e -> main.showPage(MainPanel.RUN));
			p.add(Box.createVerticalStrut(14));
			p.add(go);
		}
		for (Component c : p.getComponents()) ((javax.swing.JComponent) c).setAlignmentX(CENTER_ALIGNMENT);
		p.setAlignmentX(LEFT_ALIGNMENT);
		return p;
	}

	private static JComponentRow dayHeader(String day, boolean first) {
		JLabel l = new JLabel(day.toUpperCase(Locale.ROOT));
		l.setFont(l.getFont().deriveFont(Font.BOLD, 11f));
		l.setForeground(BiaPyFiji.BLUE);
		JComponentRow row = new JComponentRow(new BorderLayout());
		row.add(l, BorderLayout.WEST);
		row.setBorder(BorderFactory.createEmptyBorder(first ? 2 : 10, 2, 6, 0));
		return row;
	}

	/** One run: name and status, what it was, the details, and its folder and actions. */
	private JComponentRow card(JsonObject r) {
		String id = RunHistory.string(r, "id");
		String status = RunHistory.status(r);
		Color color = statusColor(status);
		Card card = new Card(color);

		// Name, status and time
		JPanel top = new JPanel(new BorderLayout(10, 0));
		top.setOpaque(false);
		JPanel nameRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
		nameRow.setOpaque(false);
		JLabel name = new JLabel(str(r, "name", "?"));
		name.setFont(name.getFont().deriveFont(Font.BOLD, 15f));
		nameRow.add(name);
		nameRow.add(Box.createHorizontalStrut(10));
		nameRow.add(new Pill(statusLabel(status), color));
		top.add(nameRow, BorderLayout.WEST);
		JLabel when = new JLabel(timeText(r, status));
		when.setFont(when.getFont().deriveFont(Font.PLAIN, 12f));
		when.setForeground(TEXT_GREY);
		when.setToolTipText("Started " + new SimpleDateFormat("EEEE d MMMM yyyy, HH:mm:ss", Locale.ENGLISH)
				.format(new Date(number(r, "started"))));
		top.add(when, BorderLayout.EAST);

		// What it was: workflow, phases, device, BiaPy version
		JPanel chips = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
		chips.setOpaque(false);
		chips.add(new Chip(str(r, "workflow_text", "?"), new Color(226, 239, 255), new Color(25, 95, 190), null));
		chips.add(new Chip(str(r, "phases", "?"), new Color(240, 240, 240), new Color(70, 70, 70), null));
		String device = str(r, "device", "");
		if (!device.isEmpty()) {
			// "GPU 0" (its name in the tooltip), "CPU", "GPUs 0, 1 (distributed)"...
			String first = device.split("\n")[0].replaceFirst(":.*", "");
			chips.add(new Chip(first, new Color(240, 240, 240), new Color(70, 70, 70), device.replace("\n", "; ")));
		}
		chips.add(new Chip("BiaPy " + str(r, "biapy_version", "?"), new Color(240, 240, 240), new Color(70, 70, 70), null));
		((FlowLayout) chips.getLayout()).setHgap(6);
		JPanel chipsRow = new JPanel(new BorderLayout());
		chipsRow.setOpaque(false);
		chipsRow.add(chips, BorderLayout.WEST);
		// The flow starts with a gap: aligned with the name
		chipsRow.setBorder(BorderFactory.createEmptyBorder(0, -6, 0, 0));

		// Details: model, epochs, results
		JPanel details = new JPanel(new GridLayout(0, 1, 0, 2));
		details.setOpaque(false);
		String model = RunHistory.string(r, "model");
		if (model != null) details.add(detail("Model", model, null));
		String checkpoint = RunHistory.string(r, "checkpoint");
		if (checkpoint != null) details.add(pathDetail("Checkpoint", checkpoint));
		if (r.has("epochs_total")) {
			long total = number(r, "epochs_total");
			details.add(detail("Training", r.has("epochs") ? number(r, "epochs") + " of " + total + " epochs" : total + " epochs planned", null));
		}
		List<String> results = new ArrayList<>();
		if (r.has("results") && r.get("results").isJsonArray())
			for (JsonElement e : r.getAsJsonArray("results")) results.add(e.getAsString().trim());
		if (!results.isEmpty())
			details.add(detail("Results", String.join("   ·   ", results), "<html>" + String.join("<br>", escape(results)) + "</html>"));
		details.add(pathDetail("Folder", str(r, "folder", "?")));

		JPanel info = new JPanel();
		info.setLayout(new BoxLayout(info, BoxLayout.Y_AXIS));
		info.setOpaque(false);
		for (javax.swing.JComponent c : new javax.swing.JComponent[] { top, chipsRow, details }) {
			c.setAlignmentX(LEFT_ALIGNMENT);
			info.add(c);
			info.add(Box.createVerticalStrut(7));
		}
		info.remove(info.getComponentCount() - 1);

		// Actions
		File folder = new File(str(r, "folder", ""));
		File config = new File(str(r, "config", ""));
		JPanel actions = new JPanel(new GridLayout(0, 1, 0, 4));
		actions.setOpaque(false);
		if (main.isJobOpen(id)) {
			JButton show = UiUtils.primaryButton("Show run", "Go to this run's tab, under BiaPy runs in the left bar");
			show.setBorder(BorderFactory.createEmptyBorder(5, 12, 5, 12));
			show.addActionListener(e -> main.showJob(id));
			actions.add(show);
		}
		JButton open = UiUtils.secondaryButton("Open folder", folder.isDirectory() ? "Open the job's folder: " + folder
				: "The job's folder no longer exists: " + folder);
		open.setEnabled(folder.isDirectory());
		open.addActionListener(e -> UiUtils.openFolder(folder));
		actions.add(open);
		JButton again = UiUtils.secondaryButton("Run again", config.isFile()
				? "Fill Run Workflow with this configuration, output folder and job name: " + config
				: "Its configuration file no longer exists: " + config);
		again.setEnabled(config.isFile() && !RUNNING.equals(status));
		if (RUNNING.equals(status)) again.setToolTipText("It is still running");
		again.addActionListener(e -> {
			File out = folder.getParentFile();
			main.getRunPanel().prepareRun(config, out != null ? out : folder, str(r, "name", ""));
			main.showPage(MainPanel.RUN);
		});
		actions.add(again);
		JPanel actionsColumn = new JPanel(new BorderLayout());
		actionsColumn.setOpaque(false);
		actionsColumn.add(actions, BorderLayout.NORTH);

		// At the top, at its height, when the buttons make the card taller
		JPanel infoColumn = new JPanel(new BorderLayout());
		infoColumn.setOpaque(false);
		infoColumn.add(info, BorderLayout.NORTH);
		card.add(infoColumn, BorderLayout.CENTER);
		card.add(actionsColumn, BorderLayout.EAST);
		JComponentRow row = new JComponentRow(new BorderLayout());
		row.add(card, BorderLayout.CENTER);
		return row;
	}

	/** A path, shortened from its start when it doesn't fit ("...folder/file"); complete in the tooltip. */
	private static JPanel pathDetail(String label, String path) {
		JPanel row = detail(label, " ", null);
		row.remove(1);
		row.add(new JobPanel.StartEllipsisLabel(path), BorderLayout.CENTER);
		return row;
	}

	private static JPanel detail(String label, String value, String tooltip) {
		JPanel row = new JPanel(new BorderLayout(4, 0));
		row.setOpaque(false);
		JLabel l = new JLabel(label + ":");
		l.setFont(l.getFont().deriveFont(Font.PLAIN, 12f));
		l.setForeground(TEXT_GREY);
		// Plain text: ends in "..." when it doesn't fit, complete in the tooltip
		JLabel v = new JLabel(value);
		v.setFont(v.getFont().deriveFont(Font.PLAIN, 12f));
		v.setToolTipText(tooltip != null ? tooltip : value);
		v.setMinimumSize(new Dimension(20, v.getPreferredSize().height));
		row.add(l, BorderLayout.WEST);
		row.add(v, BorderLayout.CENTER);
		return row;
	}

	///////////
	// Texts //
	///////////
	static String statusLabel(String status) {
		switch (status) {
		case "RUNNING": return "Running";
		case "FINISHED": return "Finished";
		case "FAILED": return "Failed";
		case "STOPPED": return "Stopped";
		default: return "Interrupted";
		}
	}

	static Color statusColor(String status) {
		try {
			return JobPanel.Status.valueOf(status).color;
		} catch (Exception e) {
			return INTERRUPTED;
		}
	}

	/** "Today", "Yesterday" or "Monday, 19 October 2026". */
	static String dayText(long time) {
		Calendar day = Calendar.getInstance(), today = Calendar.getInstance();
		day.setTimeInMillis(time);
		if (sameDay(day, today)) return "Today";
		today.add(Calendar.DAY_OF_YEAR, -1);
		if (sameDay(day, today)) return "Yesterday";
		return new SimpleDateFormat("EEEE, d MMMM yyyy", Locale.ENGLISH).format(new Date(time));
	}

	private static boolean sameDay(Calendar a, Calendar b) {
		return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
	}

	/** "11:42  ·  took 12 min" (or "started at 11:42" while it runs). */
	private static String timeText(JsonObject r, String status) {
		long started = number(r, "started"), ended = number(r, "ended");
		String at = new SimpleDateFormat("HH:mm", Locale.ENGLISH).format(new Date(started));
		if (RUNNING.equals(status)) return "started at " + at + "  ·  " + duration(System.currentTimeMillis() - started) + " so far";
		if (ended <= 0) return "started at " + at;
		return at + "  ·  took " + duration(ended - started);
	}

	/** "45 s", "12 min", "2 h 05 min". */
	static String duration(long ms) {
		long s = Math.max(0, ms / 1000);
		if (s < 60) return s + " s";
		if (s < 3600) return (s / 60) + " min";
		return String.format(Locale.ENGLISH, "%d h %02d min", s / 3600, (s / 60) % 60);
	}

	private static String str(JsonObject r, String key, String def) {
		String v = RunHistory.string(r, key);
		return v == null || v.isEmpty() ? def : v;
	}

	private static long number(JsonObject r, String key) {
		try {
			return r.has(key) ? r.get(key).getAsLong() : 0;
		} catch (Exception e) {
			return 0;
		}
	}

	private static List<String> escape(List<String> lines) {
		List<String> out = new ArrayList<>();
		for (String l : lines) out.add(l.replace("&", "&amp;").replace("<", "&lt;"));
		return out;
	}

	private static String hex(Color c) {
		return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
	}

	////////////////
	// Components //
	////////////////
	/** A full-width row of the list, as tall as its content. */
	private static final class JComponentRow extends JPanel {
		private static final long serialVersionUID = 1L;

		JComponentRow(java.awt.LayoutManager layout) {
			super(layout);
			setOpaque(false);
			setAlignmentX(LEFT_ALIGNMENT);
		}

		@Override
		public Dimension getMaximumSize() {
			return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
		}
	}

	/** Rounded white card with a stripe of the run's status color on its left; highlighted under the mouse. */
	private static final class Card extends JPanel {
		private static final long serialVersionUID = 1L;
		private final Color accent;
		private boolean hover;

		Card(Color accent) {
			super(new BorderLayout(14, 0));
			this.accent = accent;
			setOpaque(false);
			setBorder(BorderFactory.createEmptyBorder(11, 12 + ACCENT, 11, 12));
			// Children (buttons, labels) get the events: hovering is followed with the global mouse position
			java.awt.event.AWTEventListener l = e -> {
				if (!isShowing()) return;
				java.awt.event.MouseEvent me = (java.awt.event.MouseEvent) e;
				java.awt.Point p = javax.swing.SwingUtilities.convertPoint(me.getComponent(), me.getPoint(), this);
				boolean in = contains(p);
				if (in != hover) {
					hover = in;
					repaint();
				}
			};
			addHierarchyListener(e -> {
				if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.DISPLAYABILITY_CHANGED) == 0) return;
				if (isDisplayable()) java.awt.Toolkit.getDefaultToolkit().addAWTEventListener(l, java.awt.AWTEvent.MOUSE_MOTION_EVENT_MASK
						| java.awt.AWTEvent.MOUSE_EVENT_MASK);
				else java.awt.Toolkit.getDefaultToolkit().removeAWTEventListener(l);
			});
		}

		@Override
		protected void paintComponent(Graphics g0) {
			Graphics2D g = (Graphics2D) g0.create();
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			RoundRectangle2D shape = new RoundRectangle2D.Double(0.5, 0.5, getWidth() - 1.5, getHeight() - 1.5, 12, 12);
			g.setColor(hover ? CARD_HOVER : BiaPyFiji.WHITE);
			g.fill(shape);
			g.clip(shape);
			g.setColor(accent);
			g.fillRect(0, 0, ACCENT, getHeight());
			g.setClip(null);
			g.setStroke(new BasicStroke(1f));
			g.setColor(hover ? BiaPyFiji.BLUE : CARD_BORDER);
			g.draw(shape);
			g.dispose();
		}
	}

	/** Small rounded label: the status in its color on a light tint of it. */
	private static final class Pill extends JLabel {
		private static final long serialVersionUID = 1L;
		private final Color color;

		Pill(String text, Color color) {
			super(text);
			this.color = color;
			setFont(getFont().deriveFont(Font.BOLD, 11f));
			// Darker than the status color, so light ones (the yellow of running) stay readable
			setForeground(new Color((int) (color.getRed() * 0.7), (int) (color.getGreen() * 0.7), (int) (color.getBlue() * 0.7)));
			setBorder(BorderFactory.createEmptyBorder(2, 9, 2, 9));
		}

		@Override
		protected void paintComponent(Graphics g0) {
			Graphics2D g = (Graphics2D) g0.create();
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 45));
			g.fillRoundRect(0, 0, getWidth(), getHeight(), getHeight(), getHeight());
			g.dispose();
			super.paintComponent(g0);
		}
	}

	/** Small rounded tag (workflow, phases, device...). */
	private static final class Chip extends JLabel {
		private static final long serialVersionUID = 1L;
		private final Color background;

		Chip(String text, Color background, Color foreground, String tooltip) {
			super(text, SwingConstants.CENTER);
			this.background = background;
			// Whole sizes only: a fractional one (11.5) spaces the letters unevenly on Windows ("Sem antic")
			setFont(getFont().deriveFont(Font.PLAIN, 12f));
			setForeground(foreground);
			setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
			setToolTipText(tooltip != null ? tooltip : text);
		}

		@Override
		protected void paintComponent(Graphics g0) {
			Graphics2D g = (Graphics2D) g0.create();
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setColor(background);
			g.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
			g.dispose();
			super.paintComponent(g0);
		}
	}

	/** Clock with a counterclockwise arrow, as the History icon of the left bar. */
	private static final class ClockIcon implements javax.swing.Icon {
		private final int size;
		private final Color color;

		ClockIcon(int size, Color color) {
			this.size = size;
			this.color = color;
		}

		@Override
		public void paintIcon(Component c, Graphics g0, int x, int y) {
			Graphics2D g = (Graphics2D) g0.create();
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.translate(x, y);
			double k = size / 24.0;
			g.scale(k, k);
			g.setColor(color);
			g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			java.awt.geom.Path2D p = new java.awt.geom.Path2D.Double();
			p.moveTo(12, 8);
			p.lineTo(12, 12);
			p.lineTo(14, 14);
			g.draw(p);
			// Dial open at the left (clockwise from 173.6 to 199.7 degrees), then the arrow's head
			g.draw(new java.awt.geom.Arc2D.Double(3, 3, 18, 18, 173.6, -333.9, java.awt.geom.Arc2D.OPEN));
			p = new java.awt.geom.Path2D.Double();
			p.moveTo(3.05, 20);
			p.lineTo(3.05, 15);
			p.lineTo(8.05, 15);
			g.draw(p);
			g.dispose();
		}

		@Override
		public int getIconWidth() {
			return size;
		}

		@Override
		public int getIconHeight() {
			return size;
		}
	}
}
