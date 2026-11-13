package io.github.biapyx.fiji.gui;

import java.awt.Dimension;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

import javax.swing.JFrame;
import javax.swing.WindowConstants;

import ij.IJ;
import io.github.biapyx.fiji.BiaPyFiji;

/** The single BiaPy window; all its content lives in {@link MainPanel}. */
public class MainWindow extends JFrame {

	private static final long serialVersionUID = 1L;

	private final MainPanel panel = new MainPanel();

	public MainWindow() {
		super("BiaPy");
		setIconImage(BiaPyFiji.icon("images/biapy_logo.png").getImage());
		setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
		addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosing(WindowEvent e) {
				if (panel.anyJobRunning() && !UiUtils.confirm(MainWindow.this,
						"BiaPy runs are still going on. Closing the window will NOT stop them\n(open BiaPy again to follow them). Close anyway?"))
					return;
				setVisible(false);
			}
		});
		setContentPane(panel);
		// Opens at the minimum size; users enlarge it if they want
		Dimension min = new Dimension(950, 650);
		setMinimumSize(min);
		setSize(min);
		setLocationRelativeTo(IJ.getInstance());
		EnvMonitor.checkOnce();
	}

	public MainPanel getMainPanel() {
		return panel;
	}
}
