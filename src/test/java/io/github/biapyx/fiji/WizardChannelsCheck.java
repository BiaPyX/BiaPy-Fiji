package io.github.biapyx.fiji;

import io.github.biapyx.fiji.wizard.WizardModel;
import com.google.gson.JsonObject;
/**
 * Checks of the wizard state about the number of channels (no Python needed): the model takes the training images'
 * channels, the test images must have them, and the Model Zoo model chosen is reset when the data changes.
 */
public class WizardChannelsCheck {
	static JsonObject dc(int c) { JsonObject o = new JsonObject(); o.addProperty("DATA.PATCH_SIZE_C", c); return o; }
	static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); System.out.println("ok: " + what); }
	public static void main(String[] a) {
		WizardModel m = new WizardModel();
		m.setChoice(m.questionFor("PROBLEM.NDIM"), 1);
		m.setChoice(m.questionFor("PROBLEM.TYPE"), 1);
		m.setChoice(m.questionFor("TRAIN.ENABLE"), 2); // train and test
		int train = m.questionFor("DATA.TRAIN.PATH"), test = m.questionFor("DATA.TEST.PATH");
		m.setPath(train, "/gray"); m.setDataChecked(train, dc(1), new JsonObject());
		m.setPath(test, "/rgb"); m.setDataChecked(test, dc(3), new JsonObject());
		check(m.dataChannels() == 1, "training channels win over the test ones (gray train, RGB test)");
		check(m.getAnswer("data_constraints").getAsJsonObject().get("DATA.PATCH_SIZE_C").getAsInt() == 1,
				"the configuration gets the training channels");
		m.setChoice(m.questionFor("TRAIN.ENABLE"), 1); // test only
		check(m.dataChannels() == 3, "test only: the test channels");
		int sel = m.questionFor("MODEL.BMZ.SOURCE_MODEL_ID");
		m.selectExternalModel(sel, "nice-flamingo", "BioImage Model Zoo", new JsonObject());
		m.setPath(test, "/rgb");
		check(m.getAnswered(sel) != null, "the same folder keeps the model chosen");
		m.setPath(test, "/other");
		check(m.getAnswered(sel) == null && !m.isSet("MODEL.BMZ.SOURCE_MODEL_ID") && !m.toJson().has("model_restrictions"),
				"another folder resets the model chosen");
		check(m.dataChannels() == null, "no channels while the new folder is not checked");

		// Train and test again: the training images re-checked with other channels uncheck the test ones
		m.setChoice(m.questionFor("TRAIN.ENABLE"), 2);
		m.setPath(test, "/gray2");
		check(!m.setDataChecked(test, dc(1), new JsonObject()), "test images with the training channels stay checked");
		check(m.trainChannels() == 1, "trainChannels: the training images' (passed to the test check)");
		m.selectExternalModel(sel, "nice-flamingo", "BioImage Model Zoo", new JsonObject());
		check(m.setDataChecked(train, dc(3), new JsonObject()), "training re-checked with 3 channels: test unchecked");
		check(!m.isDataChecked(m.getQuestion(test)) && m.dataChannels() == 3, "the model now takes 3 channels");
		check(m.getAnswered(sel) == null, "the model chosen for 1 channel is reset");
	}
}
