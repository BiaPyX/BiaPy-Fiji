package io.github.biapyx.fiji;

import java.awt.Color;
import java.awt.Image;
import java.io.InputStream;
import java.net.URL;
import java.util.Properties;

import javax.swing.ImageIcon;

/** Versions, links, colours and resource helpers shared across the plugin. */
public final class BiaPyFiji {

	public static final String PLUGIN_VERSION;
	/** BiaPy version installed by default, when the list of versions can't be fetched (no network). */
	public static final String BIAPY_VERSION;
	/** Oldest BiaPy version with the wizard's logic the plugin uses (offered for installation). */
	public static final String BIAPY_MIN_VERSION;
	/** Version of BiaPy's wizard interface (biapy.wizard.API_VERSION) the plugin is written for. */
	public static final int WIZARD_API = 1;

	static {
		Properties p = new Properties();
		try (InputStream in = BiaPyFiji.class.getResourceAsStream("/biapy/biapy-fiji.properties")) {
			if (in != null) p.load(in);
		} catch (Exception e) {
			// Defaults below
		}
		String v = p.getProperty("plugin.version", "dev");
		// Without Maven's "-SNAPSHOT" (development builds): users only need the version number
		PLUGIN_VERSION = v.startsWith("${") ? "dev" : v.replaceFirst("-SNAPSHOT$", "");
		BIAPY_VERSION = p.getProperty("biapy.version", "3.7.2");
		BIAPY_MIN_VERSION = p.getProperty("biapy.min_version", "3.7.2");
	}

	/**
	 * Why the environment described by {@code info} (the bridge's env_quick) can't be used by the wizard, or null if
	 * it can. Checked by the interface version of BiaPy's wizard, not BiaPy's own version, so development installs
	 * work too. Only the interface the plugin was written for is accepted: BiaPy changes it only when the front-ends
	 * need to adapt.
	 */
	public static String wizardProblem(com.google.gson.JsonObject info) {
		Integer api = info != null && info.has("wizard_api") && !info.get("wizard_api").isJsonNull()
				? info.get("wizard_api").getAsInt() : null;
		if (api != null && api == WIZARD_API) return null;
		if (api != null && api > WIZARD_API)
			return "this BiaPy version is newer than the plugin supports: update the BiaPy plugin (Help > Update...) "
					+ "or install an older BiaPy version";
		return "the plugin needs BiaPy " + BIAPY_MIN_VERSION + " or newer (or its development version)";
	}

	public static boolean wizardSupported(com.google.gson.JsonObject info) {
		return wizardProblem(info) == null;
	}

	// Colours of BiaPy-GUI
	public static final Color BLUE = new Color(64, 144, 253);
	public static final Color LIGHT_BLUE = new Color(197, 234, 250);
	public static final Color WHITE = Color.WHITE;
	public static final Color GREEN = new Color(4, 170, 109);
	public static final Color RED = new Color(255, 51, 0);
	public static final Color GREY = new Color(120, 120, 120);

	// Links shown in the HOME tab
	public static final String URL_PROJECT = "https://biapyx.github.io";
	public static final String URL_GITHUB = "https://github.com/BiaPyX/BiaPy";
	public static final String URL_FORUM = "https://forum.image.sc/tag/biapy";
	public static final String URL_TEMPLATES = "https://github.com/BiaPyX/BiaPy/tree/master/templates";
	public static final String URL_DOCS = "https://biapy.readthedocs.io/en/latest/";
	public static final String URL_NOTEBOOKS = "https://github.com/BiaPyX/BiaPy/tree/master/notebooks";
	public static final String URL_CITATION = "https://www.nature.com/articles/s41592-025-02699-y";

	private BiaPyFiji() {}

	public static URL resource(String path) {
		return BiaPyFiji.class.getResource("/biapy/" + path);
	}

	public static ImageIcon icon(String path) {
		URL url = resource(path);
		return url == null ? new ImageIcon() : new ImageIcon(url);
	}

	/** Icon scaled to the given height keeping the aspect ratio. */
	public static ImageIcon icon(String path, int height) {
		ImageIcon ic = icon(path);
		if (ic.getIconHeight() <= 0) return ic;
		int w = Math.max(1, ic.getIconWidth() * height / ic.getIconHeight());
		return new ImageIcon(ic.getImage().getScaledInstance(w, height, Image.SCALE_SMOOTH));
	}
}
