package io.github.biapyx.fiji.gui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.github.biapyx.fiji.BiaPyFiji;
import io.github.biapyx.fiji.env.PythonBridge;
import io.github.biapyx.fiji.env.PythonEnv;

/**
 * The models of the BioImage Model Zoo that BiaPy can use, of every workflow and dimensions (grouped by them), to
 * choose one for "Apply model". The search takes minutes, so its models are kept for the Fiji session.
 */
final class ZooModelsDialog extends JDialog {

	private static final long serialVersionUID = 1L;
	private static ZooModelsDialog dialog;
	/** Models found so far, kept while Fiji is open. */
	private static final List<JsonObject> FOUND = new ArrayList<>();

	private Consumer<JsonObject> onSelect;
	private final ModelList list;
	private final JButton search = UiUtils.secondaryButton("Search again", "Look for the models in the BioImage Model Zoo again");
	private final JButton stop = UiUtils.secondaryButton("Stop", "Stop the search");
	private final JProgressBar bar = new JProgressBar();
	private final JLabel status = new JLabel(" ");
	private volatile PythonBridge searching;

	/** Shows the models; {@code onSelect} gets the one selected (the dialog closes then). */
	static void choose(Window owner, Consumer<JsonObject> onSelect) {
		if (!PythonEnv.pythonExists()) {
			UiUtils.info(owner, "BiaPy is not installed: install it in the 'Installation' tab.");
			return;
		}
		if (dialog == null) dialog = new ZooModelsDialog(owner);
		dialog.onSelect = onSelect;
		dialog.setVisible(true);
		dialog.toFront();
		if (FOUND.isEmpty() && dialog.searching == null) dialog.search();
	}

	/** "Instance segmentation, 2D, 1 channel": the workflow, dimensions and channels of the images the model takes. */
	static String group(JsonObject m) {
		String wf = m.has("workflow_type") ? m.get("workflow_type").getAsString() : "";
		String g = WorkflowConfirmDialog.workflowName(wf) + ", " + (m.has("ndim") ? m.get("ndim").getAsString() : "?");
		Integer c = channels(m);
		return c == null ? g : g + ", " + ApplyModelPanel.plural(c, "channel");
	}

	private static final Pattern LAST_NUMBER = Pattern.compile("(\\d+)\\D*$");

	/** Channels of the images a model takes: the last value of the DATA.PATCH_SIZE it imposes. */
	private static Integer channels(JsonObject m) {
		if (!m.has("imposed_vars") || !m.get("imposed_vars").isJsonObject()) return null;
		JsonObject vars = m.getAsJsonObject("imposed_vars");
		if (!vars.has("DATA.PATCH_SIZE")) return null;
		Matcher n = LAST_NUMBER.matcher(vars.get("DATA.PATCH_SIZE").toString());
		return n.find() ? Integer.valueOf(n.group(1)) : null;
	}

	private ZooModelsDialog(Window owner) {
		super(owner, "BiaPy - Models of the BioImage Model Zoo", ModalityType.MODELESS);
		list = new ModelList("Pretrained models of the BioImage Model Zoo that BiaPy can use", ZooModelsDialog::group,
				Comparator.comparing(ZooModelsDialog::group).thenComparing(ModelList.REPRESENTATION_ORDER), m -> false, m -> {
					// The search would keep the Python side busy, and the model waiting
					PythonBridge b = searching;
					if (b != null) b.cancel();
					setVisible(false);
					if (onSelect != null) onSelect.accept(m);
				});
		for (JsonObject m : FOUND) list.add(m);
		JPanel top = new JPanel(new BorderLayout(8, 0));
		top.setBorder(BorderFactory.createEmptyBorder(8, 10, 6, 10));
		JPanel progress = new JPanel(new BorderLayout(0, 2));
		progress.add(status, BorderLayout.NORTH);
		progress.add(bar, BorderLayout.CENTER);
		bar.setVisible(false);
		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
		buttons.add(stop);
		buttons.add(search);
		stop.setVisible(false);
		stop.addActionListener(e -> {
			PythonBridge b = searching;
			if (b != null) b.cancel();
		});
		search.addActionListener(e -> search());
		top.add(progress, BorderLayout.CENTER);
		top.add(buttons, BorderLayout.EAST);
		JPanel tracking = new WidthTrackingPanel();
		tracking.setLayout(new BorderLayout());
		tracking.add(list.view(), BorderLayout.CENTER);
		JScrollPane scroll = new JScrollPane(tracking);
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		JPanel content = new JPanel(new BorderLayout());
		content.setBackground(BiaPyFiji.WHITE);
		content.add(top, BorderLayout.NORTH);
		content.add(scroll, BorderLayout.CENTER);
		setContentPane(content);
		setSize(new Dimension(760, 640));
		setLocationRelativeTo(owner);
		status.setText(FOUND.isEmpty() ? " " : FOUND.size() + " models found");
	}

	private void search() {
		if (searching != null) return;
		PythonBridge bridge = new PythonBridge();
		searching = bridge;
		FOUND.clear();
		list.clear();
		// Python imports BiaPy first (several seconds the first time)
		status.setText("Loading BiaPy . . .");
		bar.setVisible(true);
		bar.setIndeterminate(true);
		stop.setVisible(true);
		search.setEnabled(false);
		JsonObject args = new JsonObject();
		args.addProperty("workflow", "all");
		args.addProperty("ndim", "all");
		args.addProperty("nchannels", "all");
		args.addProperty("cache", ModelList.SEARCH_CACHE.getAbsolutePath());
		new Thread(() -> {
			String error = null;
			try {
				bridge.call("find_models", args, line -> SwingUtilities.invokeLater(() -> progress(line)));
			} catch (InterruptedException e) {
				error = "";
			} catch (Exception e) {
				error = e.getMessage();
			}
			String fe = error;
			SwingUtilities.invokeLater(() -> {
				searching = null;
				bar.setVisible(false);
				stop.setVisible(false);
				search.setEnabled(true);
				status.setText(FOUND.size() + " models found" + (fe == null ? "" : fe.isEmpty() ? " (search stopped)" : ""));
				if (fe != null && !fe.isEmpty()) UiUtils.error(this, fe);
			});
		}, "BiaPy-find-models").start();
	}

	/** A line of the search: a model found or its progress (anywhere in the line, after other output maybe). */
	private void progress(String line) {
		int found = line.indexOf("MODEL_FOUND:"), prog = line.indexOf("MODEL_PROGRESS:");
		if (line.startsWith("Getting the list of models")) {
			status.setText("Getting the list of models from the BioImage Model Zoo . . .");
		} else if (found >= 0) {
			JsonObject m = JsonParser.parseString(line.substring(found + 12)).getAsJsonObject();
			FOUND.add(m);
			list.add(m);
		} else if (prog >= 0) {
			String[] t = line.substring(prog + 15).trim().split(" ");
			bar.setIndeterminate(false);
			bar.setMaximum(Integer.parseInt(t[1]));
			bar.setValue(Integer.parseInt(t[0]));
			status.setText(t[0] + "/" + t[1] + " models checked, " + FOUND.size() + " BiaPy can use");
		}
	}
}
