package io.github.biapyx.fiji.gui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JEditorPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.github.biapyx.fiji.BiaPyFiji;

/**
 * Asks the user to confirm the workflow chosen in the Wizard (question 2), the most important answer:
 * it shows what the workflow does, with examples, before going on. The content of each workflow is in
 * {@code biapy/wizard/confirm/workflows.json}.
 */
public final class WorkflowConfirmDialog {

	/** Why the dialog is shown, which decides its buttons. */
	public enum Mode {
		/** Leaving the question forward: "Yes, this is my task" / "No, let me change it". */
		CONTINUE,
		/** Going back after changing the workflow: "Keep it" / "Undo the change". */
		BACK
	}

	private static JsonObject content;

	private WorkflowConfirmDialog() {}

	/**
	 * @param workflow PROBLEM.TYPE, e.g. "SEMANTIC_SEG"
	 * @param answer the answer the user picked in the Wizard
	 * @return true to accept the workflow (continue / keep it), false otherwise (change it / undo)
	 */
	public static boolean ask(Component parent, String workflow, String answer, Mode mode) {
		if (java.awt.GraphicsEnvironment.isHeadless()) {
			System.out.println("[BiaPy workflow confirmation -> yes] " + workflow);
			return true;
		}
		Window owner = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
		JDialog d = new JDialog(owner, "BiaPy - Is this your task?", java.awt.Dialog.ModalityType.APPLICATION_MODAL);
		JEditorPane html = UiUtils.htmlPane(html(workflow, answer));
		html.setOpaque(true);
		html.setBackground(BiaPyFiji.WHITE);
		html.setCaretPosition(0);
		JScrollPane sp = new JScrollPane(html);
		sp.setBorder(null);
		sp.getVerticalScrollBar().setUnitIncrement(16);

		final boolean[] accepted = { false };
		JButton yes = UiUtils.primaryButton(mode == Mode.CONTINUE ? "Yes, this is my task" : "Keep it",
				mode == Mode.CONTINUE ? "Continue with this workflow" : "Keep the new answer and its workflow");
		JButton no = UiUtils.secondaryButton(mode == Mode.CONTINUE ? "No, let me change it" : "Undo the change",
				mode == Mode.CONTINUE ? "Go back to the question and choose another answer" : "Restore the previous answer");
		yes.addActionListener(e -> {
			accepted[0] = true;
			d.dispose();
		});
		no.addActionListener(e -> d.dispose());
		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
		buttons.setBackground(BiaPyFiji.WHITE);
		buttons.add(no);
		buttons.add(yes);

		JPanel root = new JPanel(new BorderLayout());
		root.setBackground(BiaPyFiji.WHITE);
		root.setBorder(BorderFactory.createEmptyBorder(6, 6, 0, 6));
		root.add(sp, BorderLayout.CENTER);
		root.add(buttons, BorderLayout.SOUTH);
		d.setContentPane(root);
		d.getRootPane().setDefaultButton(yes);
		d.setSize(new Dimension(680, 620));
		d.setLocationRelativeTo(owner);
		d.setVisible(true); // blocks until closed
		return accepted[0];
	}

	/** The dialog's HTML (package visible for tests). */
	static String html(String workflow, String answer) {
		JsonObject all = content();
		JsonObject wf = all.getAsJsonObject("workflows").getAsJsonObject(workflow);
		StringBuilder sb = new StringBuilder("<h1>Are you sure this is the task you want to solve?</h1>");
		String name = wf == null ? workflow : wf.get("name").getAsString();
		sb.append("<p>You answered <i>&quot;").append(answer).append("&quot;</i>, which corresponds to the <b>")
				.append(name).append("</b> workflow. Please check that it is what you need:</p>");
		if (wf == null) return UiUtils.htmlPage(sb.toString());
		sb.append("<p>").append(wf.get("description").getAsString()).append("</p>");
		for (JsonElement be : wf.getAsJsonArray("blocks")) {
			JsonObject b = be.getAsJsonObject();
			if (b.has("shared")) {
				for (JsonElement se : all.getAsJsonObject("shared").getAsJsonArray(b.get("shared").getAsString()))
					appendBlock(sb, se.getAsJsonObject());
			} else {
				appendBlock(sb, b);
			}
		}
		if (wf.has("docs"))
			sb.append("<p>More information in the <a href='").append(wf.get("docs").getAsString())
					.append("'>").append(name).append(" documentation</a>.</p>");

		return UiUtils.htmlPage(withReferences(sb.toString()));
	}

	private static void appendBlock(StringBuilder sb, JsonObject b) {
		if (b.has("text")) sb.append("<p>").append(b.get("text").getAsString()).append("</p>");
		else if (b.has("images")) sb.append(imageRow(b));
		else if (b.has("note"))
			sb.append("<table width='100%' cellpadding='6' style='background-color:#eef5ff'><tr><td>")
					.append(b.get("note").getAsString()).append("</td></tr></table>");
	}

	/** A centered row of images, each with a small caption under it. */
	private static String imageRow(JsonObject b) {
		int width = b.has("width") ? b.get("width").getAsInt() : 300;
		StringBuilder sb = new StringBuilder("<table align='center' cellpadding='4'><tr valign='top'>");
		for (JsonElement ie : b.getAsJsonArray("images")) {
			JsonObject img = ie.getAsJsonObject();
			String caption = img.has("caption") ? img.get("caption").getAsString() : null;
			sb.append(figureCell(img.get("file").getAsString(), width, caption));
		}
		return sb.append("</tr></table>").toString();
	}

	/**
	 * One image (file relative to biapy/wizard/) with its caption left-aligned under it, as a table cell. The
	 * caption defaults to the one of the shared "captions" map.
	 */
	private static String figureCell(String file, int width, String caption) {
		if (caption == null) caption = captionFor(file);
		StringBuilder sb = new StringBuilder("<td align='left' width='").append(width + 8).append("'>");
		URL url = BiaPyFiji.resource("wizard/" + file);
		if (url != null) {
			ImageIcon ic = new ImageIcon(url);
			int h = ic.getIconWidth() > 0 ? ic.getIconHeight() * width / ic.getIconWidth() : width;
			sb.append("<img src='").append(url).append("' width='").append(width).append("' height='").append(h).append("'><br>");
		}
		if (caption != null)
			sb.append("<span class='small'>").append(caption).append("</span>");
		return sb.append("</td>").toString();
	}

	/** A centered image with its caption (used by the Wizard help). */
	public static String figure(String file, int width) {
		return "<table align='center' cellpadding='4'><tr valign='top'>" + figureCell(file, width, null) + "</tr></table>";
	}

	/** Caption of an image of biapy/wizard/ from the shared "captions" map, or null. */
	public static String captionFor(String file) {
		JsonObject caps = content().getAsJsonObject("captions");
		return caps != null && caps.has(file) ? caps.get(file).getAsString() : null;
	}

	/** Numbers the {ref:key} citations in order of appearance ([1], [2]...) and appends the list of references. */
	public static String withReferences(String html) {
		java.util.List<String> cited = new java.util.ArrayList<>();
		java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{ref:([\\w-]+)\\}").matcher(html);
		StringBuffer out = new StringBuffer();
		while (m.find()) {
			if (!cited.contains(m.group(1))) cited.add(m.group(1));
			m.appendReplacement(out, "[" + (cited.indexOf(m.group(1)) + 1) + "]");
		}
		m.appendTail(out);
		if (!cited.isEmpty()) {
			out.append("<h2>References</h2>");
			JsonObject refs = content().getAsJsonObject("references");
			for (int i = 0; i < cited.size(); i++) {
				String ref = refs.has(cited.get(i)) ? refs.get(cited.get(i)).getAsString() : cited.get(i);
				out.append("<p class='small'>[").append(i + 1).append("] ").append(ref)
						.append("</p>");
			}
		}
		return out.toString();
	}


	/** Display name of a workflow (PROBLEM.TYPE), e.g. "Semantic segmentation". */
	public static String workflowName(String workflow) {
		JsonObject wfs = content().getAsJsonObject("workflows");
		if (wfs != null && wfs.has(workflow) && wfs.getAsJsonObject(workflow).has("name"))
			return wfs.getAsJsonObject(workflow).get("name").getAsString();
		String s = workflow.replace('_', ' ').toLowerCase();
		return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}

	private static synchronized JsonObject content() {
		if (content == null) {
			try (Reader r = new InputStreamReader(
					WorkflowConfirmDialog.class.getResourceAsStream("/biapy/wizard/confirm/workflows.json"), StandardCharsets.UTF_8)) {
				content = JsonParser.parseReader(r).getAsJsonObject();
			} catch (Exception e) {
				content = new JsonObject();
				content.add("workflows", new JsonObject());
			}
		}
		return content;
	}
}
