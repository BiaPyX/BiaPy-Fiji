package io.github.biapyx.fiji.wizard;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/**
 * State of the Wizard questionnaire. Questions are loaded from {@code questions.json} (taken from BiaPy-GUI's
 * settings.py, but the data questions come before the model ones), and the logic (answers, conditional visibility, navigation and checks before
 * exporting) mirrors BiaPy-GUI's ui_utils.py so both produce the same configurations.
 */
public class WizardModel {

	public enum Type { CHOICE, PATH, MODEL_BIAPY, MODEL_OTHERS, REPRESENTATION, VOXEL_SIZE, POINT_SIZE }

	/** One question of the questionnaire. */
	public static class Question {
		public final int index;
		public final String id;
		public final int section;
		public final String toc;
		public final String text;
		public final Type type;
		public final List<String> answers = new ArrayList<>();
		public final JsonObject map;
		public final JsonObject condition;
		public final String help;
		/** REPRESENTATION questions: id, description and image (relative to biapy/wizard/) of each answer. */
		public final List<String> answerIds = new ArrayList<>();
		public final List<String> answerDescriptions = new ArrayList<>();
		public final List<String> answerImages = new ArrayList<>();
		/** REPRESENTATION questions: folder (question key) whose data check decides which answers are offered. */
		public final String optionsFrom;

		Question(int index, JsonObject o) {
			this.index = index;
			id = o.get("id").getAsString();
			section = o.get("section").getAsInt();
			toc = o.get("toc").getAsString();
			text = o.get("text").getAsString();
			type = Type.valueOf(o.get("type").getAsString().toUpperCase());
			for (JsonElement a : o.getAsJsonArray("answers")) answers.add(a.getAsString());
			map = o.getAsJsonObject("map");
			condition = o.has("condition") ? o.getAsJsonObject("condition") : null;
			help = o.get("help").getAsString();
			addStrings(o, "answer_ids", answerIds);
			addStrings(o, "answer_descriptions", answerDescriptions);
			addStrings(o, "answer_images", answerImages);
			optionsFrom = o.has("options_from") ? o.get("options_from").getAsString() : null;
		}

		private static void addStrings(JsonObject o, String key, List<String> dst) {
			if (o.has(key)) for (JsonElement e : o.getAsJsonArray(key)) dst.add(e.getAsString());
		}

		/** First variable this question sets, e.g. "DATA.TRAIN.PATH". */
		public String firstKey() {
			return map.keySet().iterator().next();
		}
	}

	/** An answer of a REPRESENTATION question, as judged from the data. */
	public static class Option {
		public final int index;
		public final boolean available, recommended;
		public final String reason;

		Option(int index, boolean available, boolean recommended, String reason) {
			this.index = index;
			this.available = available;
			this.recommended = recommended;
			this.reason = reason;
		}
	}

	private static final JsonPrimitive UNSET = new JsonPrimitive(-1);

	private final List<String> sections = new ArrayList<>();
	private final List<Question> questions = new ArrayList<>();
	private final boolean[] defaultVisible;
	private boolean[] visible;
	/** Per question: null (not answered), Integer (choice index) or String (path / model). */
	private Object[] answered;
	private JsonObject answers;
	private final JsonObject originalAnswers = new JsonObject();
	private int current = 0;
	/** Channels of the raw images checked, per variable ("DATA.TRAIN.PATH", "DATA.TEST.PATH"). */
	private final Map<String, Integer> rawChannels = new java.util.HashMap<>();
	/**
	 * What the data checks found that questions depend on, e.g. "UNCALIBRATED DATA.TRAIN.PATH": true. Read by the
	 * conditions like the answers, but not sent to BiaPy.
	 */
	private final JsonObject dataFacts = new JsonObject();
	private static final String VOXEL_SIZE = "voxel_size ";

	public WizardModel() {
		JsonObject root;
		try (Reader r = new InputStreamReader(WizardModel.class.getResourceAsStream("/biapy/wizard/questions.json"),
				StandardCharsets.UTF_8)) {
			root = JsonParser.parseReader(r).getAsJsonObject();
		} catch (Exception e) {
			throw new IllegalStateException("Could not load the wizard questions", e);
		}
		for (JsonElement s : root.getAsJsonArray("sections")) sections.add(s.getAsString());
		JsonArray qs = root.getAsJsonArray("questions");
		defaultVisible = new boolean[qs.size()];
		for (int i = 0; i < qs.size(); i++) {
			JsonObject o = qs.get(i).getAsJsonObject();
			Question q = new Question(i, o);
			questions.add(q);
			defaultVisible[i] = o.get("visible").getAsBoolean();
			for (String key : q.map.keySet()) originalAnswers.add(key, UNSET);
			if (q.type == Type.PATH) originalAnswers.add("CHECKED " + q.firstKey(), UNSET);
		}
		clear();
	}

	public void clear() {
		answers = originalAnswers.deepCopy();
		rawChannels.clear();
		for (String k : new ArrayList<>(dataFacts.keySet())) dataFacts.remove(k);
		answered = new Object[questions.size()];
		visible = defaultVisible.clone();
		current = 0;
	}

	public List<String> getSections() {
		return sections;
	}

	public List<Question> getQuestions() {
		return questions;
	}

	public Question getQuestion(int i) {
		return questions.get(i);
	}

	public int getCurrent() {
		return current;
	}

	public void setCurrent(int i) {
		current = Math.max(0, Math.min(questions.size() - 1, i));
	}

	public boolean isVisible(int i) {
		return visible[i];
	}

	public Object getAnswered(int i) {
		return answered[i];
	}

	public JsonElement getAnswer(String key) {
		return answers.get(key);
	}

	public boolean isSet(String key) {
		JsonElement e = answers.get(key);
		return e != null && !e.equals(UNSET);
	}

	/** Answered questions are shown in blue in the TOC. Paths count only once their data was checked. */
	public boolean isAnswered(int i) {
		if (answered[i] == null) return false;
		Question q = questions.get(i);
		return q.type != Type.PATH || isDataChecked(q);
	}

	public boolean isDataChecked(Question q) {
		JsonElement c = answers.get("CHECKED " + q.firstKey());
		return c != null && !c.equals(UNSET);
	}

	/** Answer of a multiple-choice question. */
	public void setChoice(int qi, int answerIndex) {
		Question q = questions.get(qi);
		boolean changed = !Integer.valueOf(answerIndex).equals(answered[qi]);
		answered[qi] = answerIndex;
		for (Map.Entry<String, JsonElement> e : q.map.entrySet())
			answers.add(e.getKey(), e.getValue().getAsJsonArray().get(answerIndex));

		String key = q.firstKey();
		if (changed) forgetDependentAnswers(key);
		// The test images of a tracking job are checked as the frames of a time-lapse
		if (changed && key.equals(TRACKING)) answers.add("CHECKED DATA.TEST.PATH", UNSET);
		updateVisibility();
	}

	private static final String TRACKING = "PROBLEM.TRACKING.ENABLE";

	/** Whether the objects are tracked (answered so, and the question applies to the answers given). */
	public boolean isTracking() {
		int qi = questionFor(TRACKING);
		return qi >= 0 && visible[qi] && new JsonPrimitive(true).equals(answers.get(TRACKING));
	}

	/** Removes the answer of a multiple-choice question (e.g. undoing an unconfirmed workflow choice). */
	public void unsetChoice(int qi) {
		Question q = questions.get(qi);
		boolean changed = answered[qi] != null;
		answered[qi] = null;
		for (String key : q.map.keySet()) answers.add(key, UNSET);
		if (changed) forgetDependentAnswers(q.firstKey());
		updateVisibility();
	}

	/** Data checks and model choices depend on the dimensionality and workflow; the zoo model on the model source. */
	private void forgetDependentAnswers(String key) {
		if (key.equals("PROBLEM.NDIM") || key.equals("PROBLEM.TYPE")) {
			for (String k : answers.keySet()) if (k.startsWith("CHECKED ")) answers.add(k, UNSET);
			for (Question o : questions) if (o.type == Type.MODEL_BIAPY) answered[o.index] = null;
			resetZooModel();
		} else if (key.equals("MODEL.SOURCE")) {
			resetZooModel();
		}
	}

	/** Index of the question that sets the given variable (e.g. "PROBLEM.TYPE"), or -1. */
	public int questionFor(String key) {
		for (Question q : questions) if (q.map.has(key)) return q.index;
		return -1;
	}

	/** Folder answer. Changing it invalidates the previous data check. */
	public void setPath(int qi, String path) {
		Question q = questions.get(qi);
		path = path == null ? "" : path.trim();
		if (path.isEmpty()) return;
		if (!path.equals(answered[qi])) {
			answers.add("CHECKED " + q.firstKey(), UNSET);
			resetZooModel();
		}
		answers.addProperty(q.firstKey(), path);
		answered[qi] = path;
		updateVisibility();
	}

	/** Model typed by hand (BiaPy checkpoint path or model id). */
	public void setModel(int qi, String text) {
		Question q = questions.get(qi);
		text = text == null ? "" : text.trim();
		if (text.isEmpty()) return;
		answers.addProperty(q.firstKey(), text);
		answered[qi] = text;
		updateVisibility();
	}

	/**
	 * Voxel size (z, y, x) of the images of a VOXEL_SIZE question, typed by the user: three positive numbers, or all
	 * empty if they don't know it.
	 *
	 * @return why it is not valid, or null if it was stored
	 */
	public String setVoxelSize(int qi, String[] texts) {
		Question q = questions.get(qi);
		boolean empty = true;
		for (String t : texts) empty &= t.trim().isEmpty();
		if (empty) {
			answers.add(q.firstKey(), UNSET);
			answered[qi] = "Unknown";
			return null;
		}
		JsonArray size = new JsonArray();
		for (String t : texts) {
			double v;
			try {
				v = Double.parseDouble(t.trim().replace(',', '.'));
			} catch (NumberFormatException e) {
				v = -1;
			}
			if (!(v > 0) || Double.isInfinite(v))
				return "Please type the voxel size as three positive numbers (Z, Y and X), or leave all of them empty if you don't know it.";
			size.add(v);
		}
		answers.add(q.firstKey(), size);
		answered[qi] = "Z " + size.get(0) + ", Y " + size.get(1) + ", X " + size.get(2) + " (\u00b5m)";
		return null;
	}

	/**
	 * Radius of the detection points of a POINT_SIZE question (its keys: XY radius, then Z radius): "auto", or the
	 * pixels typed in XY and the slices in Z (empty: "auto", the XY radius scaled by the voxel size). Returns the
	 * problem to show to the user, or null.
	 */
	public String setPointSize(int qi, boolean manual, String xy, String z) {
		Question q = questions.get(qi);
		java.util.Iterator<String> keys = q.map.keySet().iterator();
		JsonElement rxy = new JsonPrimitive("auto"), rz = new JsonPrimitive("auto");
		String text = "Automatic";
		if (manual) {
			Integer vxy = radius(xy), vz = radius(z);
			if (vxy == null || (vz == null && !z.trim().isEmpty()))
				return "Please type the radius of the points as a whole number of pixels (e.g. 3), or choose 'Automatic'.";
			rxy = new JsonPrimitive(vxy);
			text = vxy + " px";
			if (vz != null) {
				rz = new JsonPrimitive(vz);
				text += ", Z " + vz + (vz == 1 ? " slice" : " slices");
			}
		}
		answers.add(keys.next(), rxy);
		answers.add(keys.next(), rz);
		answered[qi] = text;
		return null;
	}

	private static Integer radius(String text) {
		try {
			int v = Integer.parseInt(text.trim());
			return v >= 0 ? v : null;
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/** The XY and Z radii answered in a POINT_SIZE question: a number of pixels, or null if automatic or unanswered. */
	public Integer[] pointSize(int qi) {
		Integer[] out = new Integer[2];
		int i = 0;
		for (String key : questions.get(qi).map.keySet()) {
			JsonElement e = answers.get(key);
			boolean px = e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() && e.getAsInt() >= 0;
			out[i++] = px ? e.getAsInt() : null;
		}
		return out;
	}

	/** Voxel size answered in a VOXEL_SIZE question as the three numbers typed, or null. */
	public double[] voxelSize(int qi) {
		JsonElement e = answers.get(questions.get(qi).firstKey());
		if (e == null || !e.isJsonArray()) return null;
		double[] v = new double[3];
		for (int i = 0; i < 3; i++) v[i] = e.getAsJsonArray().get(i).getAsDouble();
		return v;
	}

	/**
	 * Channels the model must take: the training images' when the job trains and they are checked (the test images
	 * have to be converted to them), otherwise the test images'; null if none is checked.
	 */
	public Integer dataChannels() {
		Integer train = trainChannels();
		return train != null ? train : checkedChannels("DATA.TEST.PATH");
	}

	/** Channels of the training images, if the job trains and they are checked; otherwise null. */
	public Integer trainChannels() {
		return checkedChannels("DATA.TRAIN.PATH");
	}

	/** Channels of the raw images of {@code key} (e.g. "DATA.TEST.PATH") if they are asked and checked, else null. */
	private Integer checkedChannels(String key) {
		int qi = questionFor(key);
		return qi >= 0 && visible[qi] && isDataChecked(questions.get(qi)) ? rawChannels.get(key) : null;
	}

	/**
	 * Forgets the Model Zoo model chosen (listed for data or answers that changed), with the settings it imposed: left
	 * behind, they would still be applied to a model trained from scratch or loaded from a checkpoint.
	 */
	private void resetZooModel() {
		for (Question o : questions) {
			if (o.type != Type.MODEL_OTHERS) continue;
			answered[o.index] = null;
			for (String key : o.map.keySet()) answers.add(key, UNSET);
		}
		answers.remove("model_restrictions");
		answers.remove("MODEL.TORCHVISION_MODEL_NAME");
	}

	/** Model picked from the list of compatible BioImage Model Zoo / Torchvision models. */
	public void selectExternalModel(int qi, String modelId, String source, JsonObject restrictions) {
		if ("BioImage Model Zoo".equals(source)) {
			answers.addProperty("MODEL.SOURCE", "bmz");
			answers.addProperty("MODEL.BMZ.SOURCE_MODEL_ID", modelId);
		} else {
			answers.addProperty("MODEL.SOURCE", "torchvision");
			answers.addProperty("MODEL.TORCHVISION_MODEL_NAME", modelId);
		}
		answers.add("model_restrictions", restrictions == null ? new JsonObject() : restrictions);
		answers.addProperty("MODEL.LOAD_CHECKPOINT", false);
		answered[qi] = modelId + " (" + source + ")";
		updateVisibility();
	}

	/**
	 * Stores the result of a successful data check of the folder asked in question {@code qi}.
	 *
	 * @return whether the test images were unchecked: they no longer have the training images' channels
	 */
	public boolean setDataChecked(int qi, JsonObject dataConstraints, JsonObject sampleInfo) {
		Question q = questions.get(qi);
		Integer channelsBefore = dataChannels();
		JsonObject dc = answers.has("data_constraints") ? answers.getAsJsonObject("data_constraints") : new JsonObject();
		deepUpdate(dc, dataConstraints);
		if (dataConstraints.has("DATA.PATCH_SIZE_C") && !q.firstKey().contains("GT_PATH"))
			rawChannels.put(q.firstKey(), dataConstraints.get("DATA.PATCH_SIZE_C").getAsInt());
		answers.add("data_constraints", dc);
		answers.addProperty("CHECKED " + q.firstKey(), 1);
		// Images without voxel size in their metadata: the user is asked for it (again, for the new data)
		JsonElement fpe = sampleInfo.get("fingerprint");
		JsonObject fp = fpe != null && fpe.isJsonObject() ? fpe.getAsJsonObject() : null;
		if (fp != null && fp.has("n_calibrated"))
			dataFacts.addProperty("UNCALIBRATED " + q.firstKey(), fp.get("n_calibrated").getAsInt() == 0);
		int vq = questionFor(VOXEL_SIZE + q.firstKey());
		if (vq >= 0) {
			answered[vq] = null;
			answers.add(VOXEL_SIZE + q.firstKey(), UNSET);
		}
		// The test images must have the training images' channels: they are checked again against new ones
		Integer test = checkedChannels("DATA.TEST.PATH");
		boolean testUnchecked = q.firstKey().equals("DATA.TRAIN.PATH") && test != null
				&& !test.equals(rawChannels.get("DATA.TRAIN.PATH"));
		if (testUnchecked) answers.add("CHECKED DATA.TEST.PATH", UNSET);
		// The last folder checked would set the channels: the model takes the training images' (see dataChannels)
		Integer channels = dataChannels();
		if (channels != null) dc.addProperty("DATA.PATCH_SIZE_C", channels);
		if (channelsBefore != null && !channelsBefore.equals(channels)) resetZooModel();
		JsonObject si = answers.has("sample_info") ? answers.getAsJsonObject("sample_info") : new JsonObject();
		String dirName = sampleInfo.has("dir_name") ? sampleInfo.get("dir_name").getAsString() : q.firstKey();
		si.add(dirName, sampleInfo);
		answers.add("sample_info", si);
		// The answers offered by the questions that depend on this data may have changed
		for (Question o : questions) {
			if (o.optionsFrom == null || !o.optionsFrom.equals(q.firstKey()) || !(answered[o.index] instanceof Integer)) continue;
			Option chosen = null;
			for (Option op : options(o)) if (op.index == (Integer) answered[o.index]) chosen = op;
			if (chosen == null || !chosen.available) unsetChoice(o.index);
		}
		updateVisibility();
		return testUnchecked;
	}

	/** Folders the wizard checks, raw images first. */
	private static final String[] DATA_KEYS = { "DATA.TRAIN.PATH", "DATA.TEST.PATH", "DATA.TRAIN.GT_PATH", "DATA.TEST.GT_PATH" };

	/**
	 * The check of {@code folder} already done for another question of the same kind (raw images, or targets), e.g.
	 * when the same folder is used to train and to test, as {data constraints, sample info, the other key} for the key
	 * of question {@code qi}; null if there is none.
	 */
	public Object[] reusableCheck(int qi, String folder) {
		String key = questions.get(qi).firstKey();
		boolean target = key.contains("GT_PATH");
		// The frames of a time-lapse have their own checks
		if (key.equals("DATA.TEST.PATH") && isTracking()) return null;
		if (!answers.has("data_constraints") || !answers.has("sample_info")) return null;
		JsonObject dc = answers.getAsJsonObject("data_constraints"), si = answers.getAsJsonObject("sample_info");
		for (String other : DATA_KEYS) {
			if (other.equals(key) || other.contains("GT_PATH") != target) continue;
			// Targets read from the label images of the raw images' files are not those of the folder itself
			if (target && !targetDataPath(other.split("\\.")[1]).isEmpty()) continue;
			String checked = checkedFolder(other);
			if (checked == null || !samePath(checked, folder) || !si.has(other)) continue;
			JsonObject constraints = new JsonObject();
			for (String suffix : new String[] { "", "_path", "_path_shapes" })
				if (dc.has(other + suffix)) constraints.add(key + suffix, dc.get(other + suffix).deepCopy());
			for (String shared : new String[] { "DATA.PATCH_SIZE_C", "DATA.N_CLASSES" })
				if (dc.has(shared)) constraints.add(shared, dc.get(shared).deepCopy());
			JsonObject info = si.getAsJsonObject(other).deepCopy();
			info.addProperty("dir_name", key);
			return new Object[] { constraints, info, other };
		}
		return null;
	}

	private static boolean samePath(String a, String b) {
		try {
			return new java.io.File(a).getCanonicalFile().equals(new java.io.File(b).getCanonicalFile());
		} catch (java.io.IOException e) {
			return new java.io.File(a).getAbsoluteFile().equals(new java.io.File(b).getAbsoluteFile());
		}
	}

	/**
	 * Label images (e.g. the ground truth) that all the OME-Zarr images checked for {@code key} (e.g.
	 * "DATA.TRAIN.PATH") have, which can be used as their targets; empty if there are none.
	 */
	public List<String> omeZarrLabels(String key) {
		List<String> names = new ArrayList<>();
		if (!answers.has("sample_info")) return names;
		JsonElement info = answers.getAsJsonObject("sample_info").get(key);
		if (info == null || !info.isJsonObject() || !info.getAsJsonObject().has("ome_zarr_labels")) return names;
		for (JsonElement e : info.getAsJsonObject().getAsJsonArray("ome_zarr_labels")) names.add(e.getAsString());
		return names;
	}

	/**
	 * Path within the Zarr files of the targets checked for {@code phase} ("TRAIN" or "TEST"), e.g. "labels.mito" when
	 * they are label images of the raw OME-Zarr images; empty when they are files of their own.
	 */
	public String targetDataPath(String phase) {
		if (!answers.has("data_constraints")) return "";
		JsonObject dc = answers.getAsJsonObject("data_constraints");
		String key = "DATA." + phase + ".INPUT_ZARR_MULTIPLE_DATA";
		if (!dc.has(key) || !dc.get(key).getAsBoolean() || !dc.has(key + "_GT_PATH")) return "";
		return dc.get(key + "_GT_PATH").getAsString();
	}

	/** Undoes the data check of the folder asked in question {@code qi} (e.g. the user wants another folder). */
	public void uncheckData(int qi) {
		answers.add("CHECKED " + questions.get(qi).firstKey(), UNSET);
		resetZooModel();
		updateVisibility();
	}

	/** Folder answered for {@code key} (e.g. "DATA.TRAIN.PATH") if its data was checked, otherwise null. */
	public String checkedFolder(String key) {
		int qi = questionFor(key);
		if (qi < 0 || !visible[qi] || !isDataChecked(questions.get(qi)) || !(answered[qi] instanceof String)) return null;
		return (String) answered[qi];
	}

	/** Fingerprint of the data checked for {@code key} (see biapy.wizard.fingerprint), or null. */
	private JsonObject fingerprint(String key) {
		if (key == null || !answers.has("sample_info")) return null;
		JsonElement e = answers.getAsJsonObject("sample_info").get(key);
		if (e == null || !e.isJsonObject() || !e.getAsJsonObject().has("fingerprint")) return null;
		JsonElement fp = e.getAsJsonObject().get("fingerprint");
		return fp.isJsonObject() ? fp.getAsJsonObject() : null;
	}

	/**
	 * Answers of a REPRESENTATION question as judged from the data it depends on (available, recommended and
	 * why). All of them are available, without reasons, if the data could not be analyzed.
	 */
	public List<Option> options(Question q) {
		List<Option> out = new ArrayList<>();
		JsonObject fp = fingerprint(q.optionsFrom);
		JsonArray reps = fp != null && fp.has("representations") ? fp.getAsJsonArray("representations") : null;
		for (int i = 0; i < q.answers.size(); i++) {
			JsonObject r = null;
			if (reps != null)
				for (JsonElement e : reps)
					if (e.getAsJsonObject().get("id").getAsString().equals(q.answerIds.get(i))) r = e.getAsJsonObject();
			if (r == null) out.add(new Option(i, true, false, ""));
			else out.add(new Option(i, r.get("available").getAsBoolean(), r.get("recommended").getAsBoolean(),
					r.get("reason").getAsString()));
		}
		return out;
	}

	/** Summary of the data a REPRESENTATION question depends on (e.g. the shape of the instances), or null. */
	public String dataSummary(Question q) {
		JsonObject fp = fingerprint(q.optionsFrom);
		return fp != null && fp.has("morphology_summary") ? fp.get("morphology_summary").getAsString() : null;
	}

	private static void deepUpdate(JsonObject target, JsonObject src) {
		for (Map.Entry<String, JsonElement> e : src.entrySet()) {
			if (e.getValue().isJsonObject() && target.has(e.getKey()) && target.get(e.getKey()).isJsonObject())
				deepUpdate(target.getAsJsonObject(e.getKey()), e.getValue().getAsJsonObject());
			else
				target.add(e.getKey(), e.getValue());
		}
	}

	/** Shows/hides questions depending on the answers (BiaPy-GUI eval_wizard_answer). */
	private void updateVisibility() {
		for (Question q : questions) {
			if (q.condition == null) continue;
			Boolean v;
			if (q.condition.has("any_of")) {
				// Visible if any group holds; hidden if none holds but some could be evaluated
				v = null;
				for (JsonElement g : q.condition.getAsJsonArray("any_of")) {
					Boolean gv = evaluate(g.getAsJsonObject());
					if (Boolean.TRUE.equals(gv)) {
						v = true;
						break;
					}
					if (gv != null) v = false;
				}
			} else {
				v = evaluate(q.condition);
			}
			if (v != null) visible[q.index] = v;
		}
	}

	/**
	 * One condition group, as in BiaPy-GUI: all "and_cond" answers must equal their value and all "or_cond"
	 * answers must be in their list of values.
	 *
	 * @return whether it holds, or null when none of its answers is set yet (keep the default visibility)
	 */
	private Boolean evaluate(JsonObject condition) {
		boolean makeVisible = true;
		boolean setDefault = true;
		for (JsonElement ce : condition.getAsJsonArray("and_cond")) {
			JsonArray c = ce.getAsJsonArray();
			JsonElement a = value(c.get(0).getAsString());
			if (a != null && !a.equals(UNSET)) {
				setDefault = false;
				if (!a.equals(c.get(1))) {
					makeVisible = false;
					break;
				}
			} else {
				makeVisible = false;
				break;
			}
		}
		if (makeVisible) {
			for (JsonElement ce : condition.getAsJsonArray("or_cond")) {
				JsonArray c = ce.getAsJsonArray();
				JsonElement a = value(c.get(0).getAsString());
				if (a != null && !a.equals(UNSET)) {
					setDefault = false;
					if (!c.get(1).getAsJsonArray().contains(a)) {
						makeVisible = false;
						break;
					}
				} else {
					makeVisible = false;
					break;
				}
			}
		}
		return setDefault ? null : makeVisible;
	}

	/** An answer or, if there is none with that name, what the data checks found (see dataFacts). */
	private JsonElement value(String key) {
		return answers.has(key) ? answers.get(key) : dataFacts.get(key);
	}

	/** Index of the next visible question, or {@code size()} when the summary must be shown. */
	public int nextIndex() {
		for (int i = current + 1; i < questions.size(); i++) if (visible[i]) return i;
		return questions.size();
	}

	/** Index of the previous visible question, or -1 to go back to the start page. */
	public int previousIndex() {
		for (int i = current - 1; i >= 0; i--) if (visible[i]) return i;
		return -1;
	}

	/** Returns a message describing why the configuration cannot be created yet, or null if it can. */
	public String checkReadyToExport() {
		for (Question q : questions) {
			if (visible[q.index] && answered[q.index] == null)
				return "There are still some questions not answered (" + q.toc + "). Please go back and answer them.";
		}
		for (Question q : questions) {
			if (q.type != Type.PATH || !visible[q.index]) continue;
			String key = q.firstKey();
			String phase = key.contains("TRAIN") ? "TRAIN" : "TEST";
			JsonElement enabled = answers.get(phase + ".ENABLE");
			if (enabled == null || !enabled.isJsonPrimitive() || !enabled.getAsJsonPrimitive().isBoolean()
					|| !enabled.getAsBoolean())
				continue;
			if (!isDataChecked(q))
				return "Please check the data in '" + q.toc + "' (button 'Check data') to prevent future errors.";
		}
		return null;
	}

	/** Answers in the format expected by the Python side (same as BiaPy-GUI wizard_answers). */
	public JsonObject toJson() {
		return answers.deepCopy();
	}

	public int size() {
		return questions.size();
	}
}
