package io.github.biapyx.fiji.gui;

import java.awt.BorderLayout;
import java.io.File;
import java.io.FileReader;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import io.github.biapyx.fiji.ScreenshotHarness;

/**
 * The list of "Apply model"'s zoo models, grouped as ZooModelsDialog groups them:
 * {@code ZooModelsRender <models.json (the "models" of find_models with workflow, ndim and nchannels "all")> <png>}.
 */
public class ZooModelsRender {
	public static void main(String[] a) throws Exception {
		Map<String, Integer> groups = new LinkedHashMap<>();
		SwingUtilities.invokeAndWait(() -> {
			ModelList list = new ModelList("Pretrained models of the BioImage Model Zoo that BiaPy can use", ZooModelsDialog::group,
					Comparator.comparing(ZooModelsDialog::group).thenComparing(ModelList.REPRESENTATION_ORDER), m -> false, m -> {});
			try (FileReader r = new FileReader(a[0])) {
				for (JsonElement e : JsonParser.parseReader(r).getAsJsonArray()) {
					list.add(e.getAsJsonObject());
					groups.merge(ZooModelsDialog.group(e.getAsJsonObject()), 1, Integer::sum);
				}
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
			JPanel p = new WidthTrackingPanel();
			p.setLayout(new BorderLayout());
			p.add(list.view());
			ScreenshotHarness.save(p, new File(a[1]), 760, 1600);
		});
		groups.forEach((g, n) -> System.out.println(n + "  " + g));
		if (groups.keySet().stream().anyMatch(g -> !g.contains("channel"))) throw new AssertionError("models without channels");
		System.exit(0);
	}
}
