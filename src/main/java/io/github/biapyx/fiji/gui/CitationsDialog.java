package io.github.biapyx.fiji.gui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JEditorPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.github.biapyx.fiji.env.PythonBridge;

/**
 * The works to cite for a job (BiaPy, its model architecture, the methods it uses...), as BiaPy gives them for its
 * configuration (biapy.citations, through the bridge), with why each one is cited and buttons to copy them.
 */
final class CitationsDialog {

	/** BiaPy's own citation, shown when the BiaPy installed can't list them (versions before biapy.citations). */
	private static final String BIAPY_TEXT = "Franco-Barranco D, Andrés-San Román JA, Hidalgo-Cenalmor I, et al. "
			+ "BiaPy: accessible deep learning on bioimages. Nature Methods 22:1124-1126, 2025.";
	private static final String BIAPY_URL = "https://doi.org/10.1038/s41592-025-02699-y";

	private CitationsDialog() {}

	/** Opens the dialog and fills it when BiaPy has listed the works to cite for the configuration. */
	static void show(Component parent, File configFile, String jobName) {
		java.awt.Window owner = SwingUtilities.getWindowAncestor(parent);
		JDialog d = new JDialog(owner, "How to cite '" + jobName + "'", java.awt.Dialog.ModalityType.MODELESS);
		JEditorPane text = UiUtils.htmlPane(page("<p class='muted'>Gathering the works to cite . . .</p>"));
		JScrollPane scroll = new JScrollPane(text);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		scroll.setPreferredSize(new Dimension(640, 460));

		JButton copyBib = UiUtils.secondaryButton("Copy BibTeX", "Copy the BibTeX entries of all the works, e.g. for LaTeX or a reference manager");
		JButton copyText = UiUtils.secondaryButton("Copy as text", "Copy the list of works as plain text");
		JButton close = UiUtils.secondaryButton("Close", "Close this window");
		copyBib.setEnabled(false);
		copyText.setEnabled(false);
		close.addActionListener(e -> d.dispose());
		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
		buttons.add(copyBib);
		buttons.add(copyText);
		buttons.add(close);

		JPanel content = new JPanel(new BorderLayout(0, 10));
		content.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
		content.add(scroll, BorderLayout.CENTER);
		content.add(buttons, BorderLayout.SOUTH);
		d.setContentPane(content);
		d.pack();
		d.setLocationRelativeTo(parent);
		d.setVisible(true);

		new Thread(() -> {
			JsonObject args = new JsonObject();
			args.addProperty("yaml_file", configFile.getAbsolutePath());
			String html;
			List<String> bibtex = new ArrayList<>(), plain = new ArrayList<>();
			try {
				JsonObject res = new PythonBridge().call("citations", args, null);
				html = list(res, bibtex, plain);
			} catch (Exception e) {
				html = "<p>Please cite BiaPy if you use the results of this job in your work:</p><p>" + esc(BIAPY_TEXT)
						+ " <a href='" + BIAPY_URL + "'>" + BIAPY_URL + "</a></p><p class='muted'>The works to "
						+ "cite for the model and methods this job uses could not be listed: " + esc(e.getMessage()) + "</p>";
				plain.add(BIAPY_TEXT + " " + BIAPY_URL);
			}
			String fHtml = html;
			SwingUtilities.invokeLater(() -> {
				text.setText(page(fHtml));
				text.setCaretPosition(0);
				if (!bibtex.isEmpty()) {
					copyBib.setEnabled(true);
					copyBib.addActionListener(e -> copy(String.join("\n\n", bibtex) + "\n"));
				}
				copyText.setEnabled(true);
				copyText.addActionListener(e -> copy(String.join("\n", plain) + "\n"));
			});
		}, "BiaPy-citations").start();
	}

	/** The works as HTML (and their BibTeX entries and numbered plain-text lines into the given lists). */
	private static String list(JsonObject res, List<String> bibtex, List<String> plain) {
		StringBuilder sb = new StringBuilder("<p>If you use the results of this job in your work, please cite these "
				+ "works. When the job finishes they are also in <b>CITATIONS.md</b> and <b>citations.bib</b>, in its "
				+ "results folder.</p>");
		// The works of a group (e.g. the references of a BioImage Model Zoo model) go together, where the first is
		List<List<JsonObject>> items = new ArrayList<>();
		java.util.Map<String, List<JsonObject>> groups = new java.util.HashMap<>();
		for (JsonElement el : res.getAsJsonArray("citations")) {
			JsonObject c = el.getAsJsonObject();
			String group = str(c, "group");
			if (group == null) items.add(new ArrayList<>(java.util.Collections.singletonList(c)));
			else if (groups.containsKey(group)) groups.get(group).add(c);
			else {
				List<JsonObject> g = new ArrayList<>(java.util.Collections.singletonList(c));
				groups.put(group, g);
				items.add(g);
			}
			if (str(c, "bibtex") != null) bibtex.add(str(c, "bibtex"));
		}
		int n = 0;
		for (List<JsonObject> item : items) {
			JsonObject c = item.get(0);
			n++;
			String group = str(c, "group");
			if (group != null) {
				// Their texts are usually their roles (e.g. "training library", "data"): "role: link"
				String groupUrl = str(c, "group_url");
				sb.append("<h2>").append(n).append(". ").append(esc(group)).append("</h2><p>");
				if (groupUrl != null) sb.append("<a href='").append(groupUrl).append("'>").append(esc(groupUrl)).append("</a>");
				sb.append("</p><ul>");
				plain.add(n + ". " + group + (groupUrl != null ? ": " + groupUrl : ""));
				for (JsonObject g : item) {
					String url = str(g, "url"), title = str(g, "title");
					sb.append("<li>").append(esc(title));
					if (url != null) sb.append(": <a href='").append(url).append("'>").append(esc(url)).append("</a>");
					sb.append("</li>");
					plain.add("   - " + title + (url != null ? ": " + url : ""));
				}
				sb.append("</ul>");
				continue;
			}
			String url = str(c, "url"), text = str(c, "text"), code = str(c, "code");
			List<String> reasons = new ArrayList<>();
			for (JsonElement r : c.getAsJsonArray("reasons")) reasons.add(r.getAsString());
			if (url != null && text.endsWith(url)) text = text.substring(0, text.length() - url.length()).trim();
			sb.append("<h2>").append(n).append(". ").append(esc(String.join("; ", reasons))).append("</h2><p>")
					.append(esc(text));
			if (url != null) sb.append(" <a href='").append(url).append("'>").append(esc(url)).append("</a>");
			if (code != null)
				sb.append("<br><span class='muted'>BiaPy's implementation is adapted from </span><a href='")
						.append(code).append("'>").append(esc(code)).append("</a>");
			sb.append("</p>");
			plain.add(n + ". " + str(c, "text"));
		}
		return sb.toString();
	}

	private static String page(String body) {
		return UiUtils.htmlPage(body);
	}

	private static void copy(String s) {
		Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(s), null);
	}

	private static String str(JsonObject o, String key) {
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
	}

	private static String esc(String s) {
		return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
