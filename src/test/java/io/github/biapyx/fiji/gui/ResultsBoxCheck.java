package io.github.biapyx.fiji.gui;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

/** Checks of the metrics read for the results box (no Python needed): files shaped as BiaPy writes them. */
public class ResultsBoxCheck {
	static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); System.out.println("ok: " + what); }

	static File write(String name, String text) throws Exception {
		File f = File.createTempFile(name, ".tmp");
		f.deleteOnExit();
		Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8));
		return f;
	}

	public static void main(String[] a) throws Exception {
		ResultsBox.Training t = ResultsBox.training(write("log",
				"{\"train_loss\": 0.9, \"train_lr\": 0.001, \"train_IoU\": 0.2, \"test_loss\": 0.8, \"test_IoU\": 0.3, \"epoch\": 0}\n"
						+ "{\"train_loss\": 0.5, \"train_lr\": 0.001, \"train_IoU\": 0.6, \"test_loss\": 0.4, \"test_IoU\": 0.7, \"epoch\": 1}\n"
						+ "{\"train_loss\": 0.3, \"train_lr\": 0.001, \"train_IoU\": 0.8, \"test_loss\": 0.6, \"test_IoU\": 0.65, \"epoch\": 2}\n"));
		check(t.bestOnVal && t.epoch == 2 && t.epochs == 3, "the epoch with the lowest validation loss is summarized");
		check(t.rows.size() == 2 && t.rows.get(0).name.equals("Loss") && t.rows.get(1).val == 0.7, "loss and IoU, no learning rate");
		t = ResultsBox.training(write("log", "{\"train_loss\": 0.9, \"epoch\": 0}\n{\"train_loss\": 0.5, \"epoch\": 1}\n"));
		check(!t.bestOnVal && t.epoch == 2 && t.rows.get(0).train == 0.5, "without validation: the last epoch");

		Map<String, Double> m = ResultsBox.test(write("csv", "file,iou,precision,recall,f1,tp,fp,fn\n"
				+ "\"a,1.tif\",0.5,1.0,0.5,0.6,4,0,4\nb.tif,0.7,0.8,1.0,0.8,6,2,0\n"));
		check(m.keySet().toString().equals("[IoU, Precision, Recall, F1]"), "detection: the metrics, not the counts " + m.keySet());
		check(Math.abs(m.get("F1") - 0.7) < 1e-9, "mean over the images, also with a quoted file name");

		m = ResultsBox.test(write("csv", "file,iou (f channel),0.3 TH f1,0.5 TH tp,0.5 TH precision,0.5 TH recall,0.5 TH f1,"
				+ "0.5 TH panoptic_quality,0.5 TH (post)f1,0.5 TH (post)precision,class iou\n"
				+ "a.tif,0.9,0.95,10,0.8,0.7,0.75,0.6,0.77,0.81,0.5\n"));
		check(m.keySet().toString().equals("[Precision (IoU \u2265 0.5), F1 (IoU \u2265 0.5), Class IoU]"),
				"instances: post-processed matching at IoU 0.5 " + m.keySet());
		check(m.get("F1 (IoU \u2265 0.5)") == 0.77, "the post-processed values");
		check(ResultsBox.test(write("csv", "file\na.tif\n")).isEmpty(), "no ground truth: nothing");
		check(ResultsBox.testHtml(ResultsBox.test(write("csv", "file\na.tif\n")), 1) == null, "and no box");
	}
}
