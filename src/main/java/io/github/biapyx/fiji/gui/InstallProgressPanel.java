package io.github.biapyx.fiji.gui;

import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.GridLayout;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;

import io.github.biapyx.fiji.BiaPyFiji;
import io.github.biapyx.fiji.env.BiaPyEnvManager;

/**
 * Installation progress: one bar for the whole installation and one for the step being done. The raw
 * output is hidden behind "Show details", which is mostly useful when something fails.
 */
public class InstallProgressPanel extends JPanel implements BiaPyEnvManager.Listener {

	private static final long serialVersionUID = 1L;

	private final JLabel stepLabel = new JLabel(" ");
	private final JProgressBar overall = new JProgressBar(0, 1000);
	private final JLabel taskLabel = new JLabel(" ");
	private final JProgressBar task = new JProgressBar(0, 1000);
	private final OutputBox details = new OutputBox("Show details", "Hide details", 150);
	private int stepIndex = 0;
	private int totalSteps = 1;

	public InstallProgressPanel() {
		super(new BorderLayout(0, 6));
		setOpaque(false);

		JPanel bars = new JPanel(new GridLayout(0, 1, 0, 3));
		bars.setOpaque(false);
		stepLabel.setFont(stepLabel.getFont().deriveFont(Font.BOLD));
		overall.setStringPainted(true);
		overall.setForeground(BiaPyFiji.BLUE);
		taskLabel.setForeground(BiaPyFiji.GREY);
		task.setForeground(BiaPyFiji.BLUE);
		bars.add(stepLabel);
		bars.add(overall);
		bars.add(taskLabel);
		bars.add(task);
		add(bars, BorderLayout.NORTH);

		add(details, BorderLayout.CENTER);
		setDetailsVisible(false);
	}

	public void reset() {
		details.clear();
		overall.setValue(0);
		stepLabel.setForeground(getForeground());
		stepIndex = 0;
		totalSteps = 1;
		stepLabel.setText("Preparing the installation . . .");
		overall.setValue(0);
		overall.setString("0%");
		taskLabel.setText(" ");
		task.setIndeterminate(true);
	}

	public void setDetailsVisible(boolean visible) {
		details.setOutputVisible(visible);
	}

	/** Final state: everything done, or the error message. */
	public void finished(String error) {
		SwingUtilities.invokeLater(() -> {
			task.setIndeterminate(false);
			if (error == null) {
				stepLabel.setText("Installation completed");
				setOverall(1);
				task.setValue(task.getMaximum());
				taskLabel.setText(" ");
			} else {
				stepLabel.setText("Installation failed");
				stepLabel.setForeground(BiaPyFiji.RED);
				taskLabel.setText(error);
				setDetailsVisible(true);
			}
		});
	}

	@Override
	public void step(int index, int total, String name) {
		SwingUtilities.invokeLater(() -> {
			stepIndex = index;
			totalSteps = total;
			stepLabel.setForeground(getForeground());
			stepLabel.setText("Step " + (index + 1) + " of " + total + ": " + name);
			setOverall((double) index / total);
			task.setIndeterminate(true);
			taskLabel.setText(" ");
		});
	}

	@Override
	public void task(double fraction, String detail) {
		SwingUtilities.invokeLater(() -> {
			if (detail != null) taskLabel.setText(detail);
			if (fraction < 0) {
				task.setIndeterminate(true);
			} else {
				task.setIndeterminate(false);
				task.setValue((int) (fraction * task.getMaximum()));
			}
		});
	}

	@Override
	public void stepProgress(double fraction) {
		SwingUtilities.invokeLater(() -> setOverall((stepIndex + Math.max(0, Math.min(1, fraction))) / totalSteps));
	}

	@Override
	public void log(String text) {
		details.append(text);
	}

	private void setOverall(double fraction) {
		int v = (int) (fraction * overall.getMaximum());
		if (v < overall.getValue()) return; // never go backwards within the installation
		overall.setValue(v);
		overall.setString(Math.round(fraction * 100) + "%");
	}
}
