package io.github.biapyx.fiji.gui;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;

import com.google.gson.JsonObject;

import io.github.biapyx.fiji.BiaPyFiji;

/**
 * Content of the single BiaPy window. Like BiaPy-GUI it has the logo and version in the top-left corner,
 * the navigation tabs below it and the content of the selected tab on the right. Everything happens
 * inside this panel; no extra windows are opened apart from file choosers and short confirmations.
 */
public class MainPanel extends JPanel {

	private static final long serialVersionUID = 1L;

	public static final String HOME = "HOME";
	public static final String WIZARD = "WIZARD";
	public static final String RUN = "RUN WORKFLOW";
	public static final String HISTORY = "HISTORY";

	private final CardLayout cards = new CardLayout();
	private final JPanel content = new JPanel(cards);
	private final Map<String, JPanel> navItems = new LinkedHashMap<>();

	private final HomePanel homePanel;
	private final WizardPanel wizardPanel;
	private final RunPanel runPanel;
	private final HistoryPanel historyPanel;


	public MainPanel() {
		super(new BorderLayout());
		homePanel = new HomePanel(this::showPage);
		wizardPanel = new WizardPanel(this);
		runPanel = new RunPanel(this);
		content.add(homePanel, HOME);
		content.add(wizardPanel, WIZARD);
		content.add(runPanel, RUN);
		historyPanel = new HistoryPanel(this);
		content.add(historyPanel, HISTORY);
		content.setBackground(BiaPyFiji.WHITE);

		add(buildSidebar(), BorderLayout.WEST);
		add(content, BorderLayout.CENTER);
		showPage(HOME);
	}

	private JPanel buildSidebar() {
		JPanel sidebar = new JPanel(new BorderLayout());
		sidebar.setBackground(BiaPyFiji.BLUE);
		sidebar.setPreferredSize(new Dimension(190, 0));
		JPanel side = new JPanel();
		side.setLayout(new BoxLayout(side, BoxLayout.Y_AXIS));
		side.setBackground(BiaPyFiji.BLUE);
		sidebar.add(side, BorderLayout.NORTH);

		JPanel logoBox = new JPanel();
		logoBox.setLayout(new BoxLayout(logoBox, BoxLayout.Y_AXIS));
		logoBox.setBackground(BiaPyFiji.WHITE);
		logoBox.setBorder(BorderFactory.createEmptyBorder(14, 8, 10, 8));
		JLabel logo = new JLabel(BiaPyFiji.icon("images/biapy_logo.png", 120));
		logo.setAlignmentX(Component.CENTER_ALIGNMENT);
		JLabel version = new JLabel(versionText(null), SwingConstants.CENTER);
		// The BiaPy version installed (or the one selected to install)
		addEnvListener(info -> version.setText(versionText(info)));
		version.setFont(version.getFont().deriveFont(Font.PLAIN, 11f));
		version.setForeground(BiaPyFiji.GREY);
		version.setAlignmentX(Component.CENTER_ALIGNMENT);
		logoBox.add(logo);
		logoBox.add(Box.createVerticalStrut(6));
		logoBox.add(version);
		logoBox.setMaximumSize(new Dimension(Integer.MAX_VALUE, logoBox.getPreferredSize().height));
		logoBox.setAlignmentX(Component.LEFT_ALIGNMENT);
		side.add(logoBox);
		side.add(Box.createVerticalStrut(10));

		side.add(navItem(HOME, "images/home.png", "Install BiaPy and check the Python environment and the GPUs"));
		side.add(navItem(WIZARD, "images/wizard.png", "Create a configuration by answering a few questions about your data and task"));
		side.add(navItem(RUN, "images/run.png", "Run a configuration and follow its jobs"));

		sidebar.add(buildRunsSection(), BorderLayout.CENTER);
		// At the bottom of the bar, below the runs of this session
		JPanel bottom = new JPanel(new BorderLayout(0, 6));
		bottom.setOpaque(false);
		bottom.setBorder(BorderFactory.createEmptyBorder(6, 0, 10, 0));
		JPanel line = new JPanel();
		line.setBackground(new Color(170, 205, 255));
		line.setPreferredSize(new Dimension(0, 2));
		JPanel lineBox = new JPanel(new BorderLayout());
		lineBox.setOpaque(false);
		lineBox.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 12));
		lineBox.add(line);
		bottom.add(lineBox, BorderLayout.NORTH);
		bottom.add(navItem(HISTORY, "images/history.png", "The last runs, also of previous sessions: when they ran, how they "
				+ "ended and where their results are"), BorderLayout.CENTER);
		sidebar.add(bottom, BorderLayout.SOUTH);
		return sidebar;
	}

	/////////////////////////////
	// "BiaPy runs" in the sidebar
	/////////////////////////////
	private final JPanel runsSection = new JPanel(new BorderLayout());
	private final JPanel runsList = new JPanel();
	private final JLabel runsTitle = new JLabel();
	private final Map<JobPanel, JLabel> jobDots = new LinkedHashMap<>();
	private final Map<JobPanel, String> jobPages = new LinkedHashMap<>();
	private int jobCounter = 0;

	private JPanel buildRunsSection() {
		runsSection.setOpaque(false);
		// Separator: thin light line, then a small caps title
		JPanel sep = new JPanel(new BorderLayout(0, 6));
		sep.setOpaque(false);
		sep.setBorder(BorderFactory.createEmptyBorder(16, 12, 6, 12));
		JPanel line = new JPanel();
		line.setBackground(new Color(170, 205, 255));
		line.setPreferredSize(new Dimension(0, 2));
		sep.add(line, BorderLayout.NORTH);
		runsTitle.setForeground(BiaPyFiji.WHITE);
		runsTitle.setFont(runsTitle.getFont().deriveFont(Font.BOLD, 11f));
		sep.add(runsTitle, BorderLayout.CENTER);
		runsSection.add(sep, BorderLayout.NORTH);

		runsList.setLayout(new BoxLayout(runsList, BoxLayout.Y_AXIS));
		runsList.setBackground(BiaPyFiji.BLUE);
		JPanel holder = new JPanel(new BorderLayout());
		holder.setBackground(BiaPyFiji.BLUE);
		holder.add(runsList, BorderLayout.NORTH);
		javax.swing.JScrollPane scroll = new javax.swing.JScrollPane(holder,
				javax.swing.JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, javax.swing.JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(null);
		scroll.getViewport().setBackground(BiaPyFiji.BLUE);
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		runsSection.add(scroll, BorderLayout.CENTER);
		runsSection.setVisible(false);
		return runsSection;
	}

	/** Starts a BiaPy run in a new tab under "BiaPy runs" and shows it. */
	public JobPanel startJob(String jobName, java.io.File config, java.io.File jobFolder, String device,
			java.util.List<String> command) {
		return startJob(jobName, config, jobFolder, device, command, null);
	}

	/** @param note optional remark shown in the run's header (e.g. a setting changed for this platform) */
	public JobPanel startJob(String jobName, java.io.File config, java.io.File jobFolder, String device,
			java.util.List<String> command, String note) {
		String page = "JOB-" + (++jobCounter);
		JobPanel job = new JobPanel(jobName, config, jobFolder, device, biapyVersion(), command, note, this::jobStatusChanged,
				j -> closeJob(j, page));
		content.add(job, page);

		JPanel item = new JPanel(new BorderLayout(6, 0));
		item.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 8));
		JLabel dot = new JLabel("\u25CF");
		dot.setFont(dot.getFont().deriveFont(14f));
		JLabel name = new JLabel(jobName);
		name.setFont(name.getFont().deriveFont(Font.PLAIN, 13f));
		item.add(dot, BorderLayout.WEST);
		item.add(name, BorderLayout.CENTER);
		item.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
		item.setAlignmentX(Component.LEFT_ALIGNMENT);
		item.setToolTipText(jobName + " - " + jobFolder.getAbsolutePath());
		registerNav(page, item, name);
		jobDots.put(job, dot);
		jobPages.put(job, page);
		runsList.add(item);
		runsSection.setVisible(true);
		jobStatusChanged(job);
		showPage(page);
		job.start();
		return job;
	}

	private void jobStatusChanged(JobPanel job) {
		JLabel dot = jobDots.get(job);
		if (dot != null) dot.setForeground(job.getStatus().color);
		long running = jobDots.keySet().stream().filter(JobPanel::isRunning).count();
		runsTitle.setText("BIAPY RUNS" + (running > 0 ? "  (" + running + " running)" : ""));
	}

	private void closeJob(JobPanel job, String page) {
		JPanel item = navItems.remove(page);
		runsList.remove(item);
		jobDots.remove(job);
		jobPages.remove(job);
		content.remove(job);
		historyPanel.refresh(); // its card no longer offers to show it
		runsSection.setVisible(!jobDots.isEmpty());
		jobStatusChanged(job);
		if (page.equals(currentPage)) showPage(RUN);
		runsList.revalidate();
		runsList.repaint();
	}

	/** Whether the run with this history id still has its tab open. */
	public boolean isJobOpen(String historyId) {
		return jobPages.keySet().stream().anyMatch(j -> historyId != null && historyId.equals(j.getHistoryId()));
	}

	/** Shows the tab of the run with this history id, if still open. */
	public void showJob(String historyId) {
		for (Map.Entry<JobPanel, String> e : jobPages.entrySet())
			if (historyId != null && historyId.equals(e.getKey().getHistoryId())) showPage(e.getValue());
	}

	/** True while any BiaPy run started from this window is still running. */
	public boolean anyJobRunning() {
		return jobDots.keySet().stream().anyMatch(JobPanel::isRunning);
	}

	/** Running job with this name, if any (to avoid two runs writing in the same folder). */
	public JobPanel findRunningJob(String jobName, java.io.File jobFolder) {
		for (JobPanel j : jobDots.keySet())
			if (j.isRunning() && j.getJobName().equals(jobName)) return j;
		return null;
	}

	/** The tab images of BiaPy-GUI already contain the icon and the text. */
	private JPanel navItem(String page, String image, String tooltip) {
		JPanel item = new JPanel(new BorderLayout());
		item.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 0));
		item.setBackground(BiaPyFiji.BLUE);
		// The label gets the row's mouse listener too (registerNav), so its tooltip does not swallow the clicks
		JLabel l = new JLabel(BiaPyFiji.icon(image, 34));
		l.setToolTipText(tooltip);
		item.add(l, BorderLayout.CENTER);
		l.setHorizontalAlignment(SwingConstants.LEFT);
		item.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		item.setMaximumSize(new Dimension(Integer.MAX_VALUE, 46));
		item.setAlignmentX(Component.LEFT_ALIGNMENT);
		registerNav(page, item, l);
		return item;
	}


	/** Makes the whole row clickable, with hover highlight, and registers it as a page of the sidebar. */
	private void registerNav(String page, JPanel item, JLabel label) {
		item.setBackground(BiaPyFiji.BLUE);
		item.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		MouseAdapter ma = new MouseAdapter() {
			// mousePressed instead of mouseClicked: a click with a tiny mouse movement is still a click
			@Override
			public void mousePressed(MouseEvent e) {
				showPage(page);
			}

			@Override
			public void mouseEntered(MouseEvent e) {
				if (!page.equals(currentPage)) item.setBackground(BiaPyFiji.LIGHT_BLUE);
			}

			@Override
			public void mouseExited(MouseEvent e) {
				item.setBackground(page.equals(currentPage) ? BiaPyFiji.WHITE : BiaPyFiji.BLUE);
			}
		};
		item.addMouseListener(ma);
		label.addMouseListener(ma);
		navItems.put(page, item);
	}

	private String currentPage;

	public void showPage(String page) {
		currentPage = page;
		if (HISTORY.equals(page)) historyPanel.refresh(); // e.g. "Today" may be "Yesterday" now
		cards.show(content, page);
		for (Map.Entry<String, JPanel> e : navItems.entrySet()) {
			Color bg = e.getKey().equals(page) ? BiaPyFiji.WHITE : BiaPyFiji.BLUE;
			e.getValue().setBackground(bg);
		}
	}

	public WizardPanel getWizardPanel() {
		return wizardPanel;
	}

	public RunPanel getRunPanel() {
		return runPanel;
	}

	/** Information about the Python environment (BiaPy/torch versions and GPUs), or null if unknown. */
	public JsonObject getEnvInfo() {
		return EnvMonitor.info();
	}

	/** BiaPy version of the environment in use (the one runs use), or "?" if unknown. */
	public String biapyVersion() {
		JsonObject info = EnvMonitor.info();
		return info != null && info.has("biapy_version") && !info.get("biapy_version").isJsonNull()
				? info.get("biapy_version").getAsString() : "?";
	}

	private static String versionText(JsonObject info) {
		String biapy = info != null && "ok".equals(info.get("_state").getAsString()) && info.has("biapy_version")
				&& !info.get("biapy_version").isJsonNull() ? info.get("biapy_version").getAsString()
						: io.github.biapyx.fiji.env.BiaPyVersions.isDev(io.github.biapyx.fiji.env.BiaPyVersions.selected()) ? "dev"
								: io.github.biapyx.fiji.env.BiaPyVersions.selected();
		return "<html><center>Version " + biapy + "<br>Fiji plugin: " + BiaPyFiji.PLUGIN_VERSION + "</center></html>";
	}

	public void addEnvListener(Consumer<JsonObject> l) {
		EnvMonitor.addListener(l);
	}

	/** See {@link EnvMonitor#refresh()}. */
	public void refreshEnvInfo() {
		EnvMonitor.refresh();
	}

	/** Last known state of the Python environment: "checking", "ok", "broken" or "missing". */
	public String getEnvState() {
		return EnvMonitor.state();
	}
}
