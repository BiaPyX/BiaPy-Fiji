package io.github.biapyx.fiji.gui;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import javax.swing.SwingUtilities;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The runs started from the plugin, newest first, kept between sessions in "biapy_runs.json" in Fiji's
 * preferences folder (the History page). Each run is a JSON object: see {@link JobPanel} for its fields.
 */
public final class RunHistory {

	/** Runs kept: the oldest are forgotten (their results stay in their folders). */
	static final int MAX_RUNS = 200;
	/** Runs of this Fiji session: a run still "Running" from another session was interrupted (Fiji closed). */
	static final String SESSION = UUID.randomUUID().toString();

	private static final List<Runnable> listeners = new CopyOnWriteArrayList<>();
	private static List<JsonObject> runs;

	private RunHistory() {}

	static File file() {
		String dir = ij.Prefs.getPrefsDir();
		return new File(dir != null ? dir : System.getProperty("user.home"), "biapy_runs.json");
	}

	/** Copy of the runs, newest first. */
	public static synchronized List<JsonObject> runs() {
		List<JsonObject> out = new ArrayList<>();
		for (JsonObject r : load()) out.add(r.deepCopy());
		return out;
	}

	/** Adds a run (it gets an "id" and this session) and returns its id. */
	static synchronized String add(JsonObject run) {
		String id = UUID.randomUUID().toString();
		run.addProperty("id", id);
		run.addProperty("session", SESSION);
		List<JsonObject> all = load();
		all.add(0, run);
		while (all.size() > MAX_RUNS) all.remove(all.size() - 1);
		save();
		return id;
	}

	/** Changes a run (e.g. its status once it ends); nothing if it was removed meanwhile. */
	static synchronized void update(String id, Consumer<JsonObject> change) {
		for (JsonObject r : load()) {
			if (id.equals(string(r, "id"))) {
				change.accept(r);
				save();
				return;
			}
		}
	}

	/** Forgets all the runs. */
	static synchronized void clear() {
		load().clear();
		save();
	}

	/** Called on the EDT after every change. */
	static void addListener(Runnable l) {
		listeners.add(l);
	}

	/** Status shown: a run still "Running" in the file but not in this session ended with Fiji. */
	static String status(JsonObject run) {
		String status = string(run, "status");
		if (JobPanel.Status.RUNNING.name().equals(status) && !SESSION.equals(string(run, "session"))) return "INTERRUPTED";
		return status == null ? "INTERRUPTED" : status;
	}

	static String string(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e == null || e.isJsonNull() ? null : e.getAsString();
	}

	private static List<JsonObject> load() {
		if (runs != null) return runs;
		runs = new ArrayList<>();
		File f = file();
		if (!f.isFile()) return runs;
		try {
			JsonElement root = JsonParser.parseString(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
			if (root.isJsonObject() && root.getAsJsonObject().has("runs"))
				for (JsonElement e : root.getAsJsonObject().getAsJsonArray("runs"))
					if (e.isJsonObject()) runs.add(e.getAsJsonObject());
		} catch (Exception e) {
			ij.IJ.log("[BiaPy] Could not read the run history (" + f + "): " + e.getMessage());
		}
		return runs;
	}

	private static void save() {
		JsonObject root = new JsonObject();
		root.addProperty("version", 1);
		JsonArray arr = new JsonArray();
		for (JsonObject r : runs) arr.add(r);
		root.add("runs", arr);
		File f = file();
		try {
			// Written aside and then moved, so a crash never leaves half a file
			File tmp = new File(f.getPath() + ".tmp");
			f.getParentFile().mkdirs();
			Files.write(tmp.toPath(), new GsonBuilder().setPrettyPrinting().create().toJson(root).getBytes(StandardCharsets.UTF_8));
			Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
		} catch (Exception e) {
			ij.IJ.log("[BiaPy] Could not save the run history (" + f + "): " + e.getMessage());
		}
		for (Runnable l : listeners) SwingUtilities.invokeLater(l);
	}
}
