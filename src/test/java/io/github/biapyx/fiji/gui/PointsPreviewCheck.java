package io.github.biapyx.fiji.gui;

import java.awt.image.BufferedImage;

import com.google.gson.JsonParser;

import io.github.biapyx.fiji.wizard.WizardModel;

/** Checks of the point size question (no Python needed): the answers and the targets drawn as BiaPy paints them. */
public class PointsPreviewCheck {
	static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); System.out.println("ok: " + what); }

	public static void main(String[] a) {
		int[] r = { 1, 3, 3 };
		check(PointsPreview.inTarget(0, 3, 0, r) && PointsPreview.inTarget(1, 0, 0, r), "ellipsoid: its axes are in");
		check(!PointsPreview.inTarget(1, 1, 0, r) && !PointsPreview.inTarget(2, 0, 0, r), "and the voxels beyond them are out");
		check(!PointsPreview.inTarget(1, 0, 0, new int[] { 0, 3, 3 }), "radius 0 along Z: only the slice of the point");

		BufferedImage crop = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
		BufferedImage d = PointsPreview.draw(crop, JsonParser.parseString("[[0, 5, 5], [3, 1, 1]]").getAsJsonArray(),
				new int[] { 1, 1, 1 }, 20);
		check(d.getWidth() == 20 && (d.getRGB(10, 10) & 0xff0000) != 0 && (d.getRGB(8, 10) & 0xff0000) != 0,
				"the target of a point is drawn, enlarged");
		check(d.getRGB(8, 8) == 0xff000000 && d.getRGB(2, 2) == 0xff000000, "a disk of radius 1, and nothing for a point 3 slices away");

		WizardModel m = new WizardModel();
		int q = -1;
		for (int i = 0; i < m.getQuestions().size(); i++)
			if (m.getQuestion(i).type == WizardModel.Type.POINT_SIZE) q = i;
		check(m.setPointSize(q, true, "x", "") != null && m.setPointSize(q, true, "3", "-1") != null, "wrong radii refused");
		check(m.setPointSize(q, true, " 4 ", "") == null && m.getAnswer("detection_point_radius").getAsInt() == 4
				&& m.getAnswer("detection_point_radius_z").getAsString().equals("auto") && "4 px".equals(m.getAnswered(q)),
				"manual XY, automatic Z");
		check(m.pointSize(q)[0] == 4 && m.pointSize(q)[1] == null, "shown again in the question");
		check(m.setPointSize(q, false, "4", "2") == null && m.getAnswer("detection_point_radius").getAsString().equals("auto"),
				"automatic ignores the fields");
	}
}
