package io.github.biapyx.fiji.gui;

import java.awt.Dimension;

import javax.swing.JFrame;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

import com.google.gson.JsonObject;

import ij.IJ;
import io.github.biapyx.fiji.BiaPyFiji;

/**
 * The window of {@code Plugins > BiaPy > Apply model}, one per Fiji session: the "Installation" tab (the HOME page of
 * the main window, without its shortcuts) and the "Apply model" tab ({@link ApplyModelPanel}), which can only be used
 * once BiaPy is installed and works.
 */
public class ApplyModelWindow extends JFrame {

	private static final long serialVersionUID = 1L;
	private static final int INSTALL = 0, APPLY = 1;
	private static ApplyModelWindow window;

	private final ApplyModelPanel panel = new ApplyModelPanel();
	private final JTabbedPane tabs = new JTabbedPane();
	private boolean ready;

	public static void open() {
		SwingUtilities.invokeLater(() -> {
			if (window == null) window = new ApplyModelWindow();
			window.panel.refreshImages();
			window.setVisible(true);
			window.toFront();
		});
	}

	private ApplyModelWindow() {
		super("BiaPy - Apply model");
		setIconImage(BiaPyFiji.icon("images/biapy_logo.png").getImage());
		setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
		tabs.addTab("Installation", new HomePanel(null));
		tabs.addTab("Apply model", panel);
		setContentPane(tabs);
		// As small as its content: the Apply tab's steps
		pack();
		setSize(Math.max(680, getWidth()), getHeight());
		setMinimumSize(new Dimension(600, 0));
		setLocationRelativeTo(IJ.getInstance());
		tabs.setEnabledAt(APPLY, false); // until the environment is known to work
		EnvMonitor.addListener(this::envChanged);
		EnvMonitor.checkOnce();
		// The images open in Fiji change while the window is open
		addWindowFocusListener(new java.awt.event.WindowAdapter() {
			@Override
			public void windowGainedFocus(java.awt.event.WindowEvent e) {
				panel.refreshImages();
			}
		});
	}

	/** "Apply model" is unlocked once BiaPy works, and shown then; the installation tab otherwise. */
	private void envChanged(JsonObject info) {
		boolean now = "ok".equals(info.get("_state").getAsString()) && BiaPyFiji.wizardSupported(info);
		tabs.setEnabledAt(APPLY, now);
		tabs.setToolTipTextAt(APPLY, now ? null : "Install BiaPy first (Installation tab)");
		if (now != ready) tabs.setSelectedIndex(now ? APPLY : INSTALL);
		ready = now;
	}
}
