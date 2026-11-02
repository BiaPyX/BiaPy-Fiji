package io.github.biapyx.fiji.env;

import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ij.Prefs;
import io.github.biapyx.fiji.BiaPyFiji;

/**
 * BiaPy versions the user can install: the releases on PyPI (the same as BiaPy's GitHub releases, and what pip
 * installs) from {@link BiaPyFiji#BIAPY_MIN_VERSION}, the oldest one whose configurations the plugin creates
 * correctly, that run with the environment's Python. The version selected is remembered in Fiji's preferences;
 * until the user selects one, the latest is used.
 */
public class BiaPyVersions {

	private static final String PYPI_URL = "https://pypi.org/pypi/biapy/json";
	private static final String PREF_SELECTED = "biapy.selected_version";
	private static final String PREF_CACHE = "biapy.available_versions";
	private static final String PREF_DEV_SOURCE = "biapy.dev_source";
	/** Whether the development version was selected when there were releases to choose from (see selected()). */
	private static final String PREF_DEV_CHOSEN = "biapy.dev_chosen";
	/**
	 * Entry of the list to install BiaPy from its source code instead of a release (e.g. a branch on GitHub, to test
	 * changes before they are released).
	 */
	public static final String DEV = "Development version";
	public static final String DEFAULT_DEV_SOURCE = "BiaPyX/BiaPy@master";

	private BiaPyVersions() {}

	/**
	 * Versions available, newest first, from PyPI (and remembered for offline use).
	 *
	 * @throws Exception if PyPI can't be reached
	 */
	public static List<String> fetch() throws Exception {
		HttpURLConnection c = (HttpURLConnection) new URL(PYPI_URL).openConnection();
		c.setConnectTimeout(10_000);
		c.setReadTimeout(20_000);
		c.setRequestProperty("Accept", "application/json");
		JsonObject root;
		try (InputStreamReader r = new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8)) {
			root = JsonParser.parseReader(r).getAsJsonObject();
		}
		List<String> versions = new ArrayList<>();
		for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("releases").entrySet()) {
			String v = e.getKey();
			if (!v.matches("\\d+(\\.\\d+)*")) continue; // pre-releases (e.g. 3.8.0rc1)
			if (compare(v, BiaPyFiji.BIAPY_MIN_VERSION) < 0) continue;
			JsonArray files = e.getValue().getAsJsonArray();
			boolean usable = false;
			String requires = null;
			for (JsonElement f : files) {
				JsonObject o = f.getAsJsonObject();
				if (o.has("yanked") && o.get("yanked").getAsBoolean()) continue;
				usable = true;
				if (o.has("requires_python") && !o.get("requires_python").isJsonNull()) requires = o.get("requires_python").getAsString();
			}
			if (usable && pythonAllowed(requires, BiaPyEnvManager.PYTHON_VERSION)) versions.add(v);
		}
		versions.sort((a, b) -> compare(b, a));
		// Remembered even if empty: no release is supported yet (the development version is offered)
		Prefs.set(PREF_CACHE, String.join(",", versions));
		Prefs.savePreferences();
		return versions;
	}

	/**
	 * Versions known without network: the last list fetched (without the ones older than the plugin supports, e.g.
	 * cached by a previous version of the plugin), or the plugin's default version.
	 */
	public static List<String> cached() {
		String s = Prefs.get(PREF_CACHE, null);
		List<String> out = new ArrayList<>();
		if (s == null) {
			out.add(BiaPyFiji.BIAPY_VERSION);
			return out;
		}
		for (String v : s.split(","))
			if (!v.trim().isEmpty() && compare(v.trim(), BiaPyFiji.BIAPY_MIN_VERSION) >= 0) out.add(v.trim());
		return out;
	}

	/**
	 * Version to install: the one the user selected, or the latest known, or the development version when no
	 * release is supported yet. The development version is kept only if it was chosen when there were releases to
	 * choose from: before the first supported release it was the only entry of the list, and installing it remembered
	 * it, so the latest release is selected once there is one.
	 */
	public static String selected() {
		String v = Prefs.get(PREF_SELECTED, "");
		List<String> known = cached();
		if (isDev(v) && ("true".equals(Prefs.get(PREF_DEV_CHOSEN, "false")) || known.isEmpty())) return v;
		if (!v.isEmpty() && !isDev(v) && compare(v, BiaPyFiji.BIAPY_MIN_VERSION) >= 0) return v;
		return known.isEmpty() ? DEV : known.get(0);
	}

	/** Whether the user has selected a version (otherwise the latest is used). */
	public static boolean userSelected() {
		return !Prefs.get(PREF_SELECTED, "").isEmpty();
	}

	public static void select(String version) {
		Prefs.set(PREF_SELECTED, version);
		// See selected(): the development version is a choice only if there were releases to choose from
		Prefs.set(PREF_DEV_CHOSEN, String.valueOf(isDev(version) && !cached().isEmpty()));
		Prefs.savePreferences();
	}

	public static boolean isDev(String version) {
		return DEV.equals(version);
	}

	/**
	 * Where the development version is installed from: "owner/repo@branch" on GitHub (the branch's zip is
	 * downloaded, so git is not needed), or anything pip installs (a URL, or the folder of a BiaPy checkout).
	 */
	public static String devSource() {
		return Prefs.get(PREF_DEV_SOURCE, DEFAULT_DEV_SOURCE);
	}

	public static void setDevSource(String source) {
		Prefs.set(PREF_DEV_SOURCE, source == null || source.trim().isEmpty() ? DEFAULT_DEV_SOURCE : source.trim());
		Prefs.savePreferences();
	}

	/** What pip installs for a version of the list. */
	public static String requirement(String version) {
		if (!isDev(version)) return "biapy==" + version;
		String src = devSource();
		java.util.regex.Matcher m = java.util.regex.Pattern.compile("([\\w.-]+/[\\w.-]+)(?:@([\\w./-]+))?").matcher(src);
		if (m.matches())
			return "biapy @ https://github.com/" + m.group(1) + "/archive/refs/heads/"
					+ (m.group(2) == null ? "master" : m.group(2)) + ".zip";
		return src;
	}

	/** Name of a version of the list for the user, e.g. "BiaPy 3.7.1" or "BiaPy's development version (...)". */
	public static String describe(String version) {
		return isDev(version) ? "BiaPy's development version (" + devSource() + ")" : "BiaPy " + version;
	}

	/** Compares dotted version numbers ("3.7.10" > "3.7.9"). */
	public static int compare(String a, String b) {
		int[] x = parts(a), y = parts(b);
		for (int i = 0; i < Math.max(x.length, y.length); i++) {
			int d = Integer.compare(i < x.length ? x[i] : 0, i < y.length ? y[i] : 0);
			if (d != 0) return d;
		}
		return 0;
	}

	private static int[] parts(String v) {
		return Arrays.stream(v.split("\\.")).mapToInt(p -> {
			try {
				return Integer.parseInt(p.replaceAll("\\D.*", ""));
			} catch (NumberFormatException e) {
				return 0;
			}
		}).toArray();
	}

	/** Whether a "requires_python" specifier (e.g. ">=3.11,<3.13") allows the given Python version. */
	static boolean pythonAllowed(String requires, String python) {
		if (requires == null || requires.trim().isEmpty()) return true;
		for (String spec : requires.split(",")) {
			spec = spec.trim();
			java.util.regex.Matcher m = java.util.regex.Pattern.compile("(>=|<=|==|!=|~=|>|<)\\s*([\\d.]+)(\\.\\*)?").matcher(spec);
			if (!m.matches()) continue;
			String op = m.group(1), v = m.group(2);
			// Compare only as many parts as the specifier gives (e.g. "<3.12" against "3.11")
			int n = parts(v).length;
			int[] p = parts(python);
			String py = Arrays.stream(Arrays.copyOf(p, Math.max(n, 1))).mapToObj(String::valueOf).reduce((s1, s2) -> s1 + "." + s2).orElse(python);
			int d = compare(py, v);
			boolean ok;
			switch (op) {
			case ">=":
			case "~=":
				ok = d >= 0;
				break;
			case "<=":
				ok = d <= 0;
				break;
			case ">":
				ok = d > 0;
				break;
			case "<":
				ok = d < 0;
				break;
			case "==":
				ok = d == 0;
				break;
			default:
				ok = d != 0;
			}
			if (!ok) return false;
		}
		return true;
	}
}
