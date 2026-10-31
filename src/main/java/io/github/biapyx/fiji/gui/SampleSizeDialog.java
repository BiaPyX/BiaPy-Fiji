package io.github.biapyx.fiji.gui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.SwingUtilities;

import com.google.gson.JsonObject;

import io.github.biapyx.fiji.BiaPyFiji;

/**
 * Asks how much of a folder of large Zarr/H5 images to check in the Wizard: reading them whole may take long, so a
 * percentage of each image, in blocks chosen at random, is read instead (biapy.wizard.data_check.folder_size gives
 * their size and the percentage suggested).
 */
final class SampleSizeDialog {

	private SampleSizeDialog() {}

	/**
	 * @param size result of the bridge command "data_size"
	 * @return the percentage chosen (1-100), or -1 if the user cancelled
	 */
	static int ask(Component parent, String folder, JsonObject size) {
		long bytes = size.get("chunked_bytes").getAsLong();
		int files = size.get("chunked_files").getAsInt();
		int suggested = size.get("suggested_percentage").getAsInt();
		if (java.awt.GraphicsEnvironment.isHeadless()) {
			System.out.println("[BiaPy sample size -> " + suggested + "%] " + folder);
			return suggested;
		}
		String body = "<h1>Large Zarr/H5 data</h1><p>The folder <i>" + folder.replace("&", "&amp;").replace("<", "&lt;")
				+ "</i> has " + files + " Zarr/H5 "
				+ (files == 1 ? "image" : "images") + " with " + gib(bytes) + " of data. Reading all of it to check it may "
				+ "take long, so the Wizard can check only a part of each image, in blocks chosen at random. That is "
				+ "usually enough to know its channels, values and classes, and the sizes are always those of the whole "
				+ "images.</p><p>Choose the percentage of the data to check:</p>";
		JDialog d = new JDialog(SwingUtilities.getWindowAncestor(parent), "Large data", java.awt.Dialog.ModalityType.APPLICATION_MODAL);
		JSlider slider = new JSlider(1, 100, suggested);
		slider.setMajorTickSpacing(25);
		slider.setMinorTickSpacing(5);
		slider.setPaintTicks(true);
		slider.setPaintLabels(true);
		slider.setOpaque(false);
		JLabel amount = new JLabel();
		amount.setFont(amount.getFont().deriveFont(Font.BOLD, 12f));
		Runnable update = () -> amount.setText(slider.getValue() + "% of the data: about " + gib(bytes * slider.getValue() / 100)
				+ " to read" + (slider.getValue() == suggested ? " (suggested)" : ""));
		update.run();
		slider.addChangeListener(e -> update.run());

		final int[] result = { -1 };
		JButton check = UiUtils.primaryButton("Check data", "Check the percentage of the data chosen");
		check.addActionListener(e -> {
			result[0] = slider.getValue();
			d.dispose();
		});
		JButton cancel = UiUtils.secondaryButton("Cancel", "Don't check the data now");
		cancel.addActionListener(e -> d.dispose());

		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
		buttons.setOpaque(false);
		buttons.add(cancel);
		buttons.add(check);
		JPanel choice = new JPanel(new BorderLayout(0, 8));
		choice.setOpaque(false);
		choice.add(slider, BorderLayout.NORTH);
		choice.add(amount, BorderLayout.CENTER);
		choice.add(buttons, BorderLayout.SOUTH);
		JPanel content = new JPanel(new BorderLayout(0, 8));
		content.setBackground(BiaPyFiji.WHITE);
		content.setBorder(BorderFactory.createEmptyBorder(8, 14, 12, 14));
		content.add(UiUtils.htmlPane(UiUtils.htmlPage(body)), BorderLayout.CENTER);
		content.add(choice, BorderLayout.SOUTH);
		d.setContentPane(content);
		d.getRootPane().setDefaultButton(check);
		d.setSize(520, 380);
		d.setLocationRelativeTo(parent);
		d.setVisible(true);
		return result[0];
	}

	private static String gib(long bytes) {
		double g = bytes / (1024.0 * 1024 * 1024);
		return g >= 1 ? String.format("%.1f GB", g) : String.format("%.0f MB", bytes / (1024.0 * 1024));
	}
}
