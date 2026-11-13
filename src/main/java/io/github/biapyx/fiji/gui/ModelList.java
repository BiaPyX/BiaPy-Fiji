package io.github.biapyx.fiji.gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.github.biapyx.fiji.BiaPyFiji;

/**
 * Models of the BioImage Model Zoo found by the bridge's find_models, as cards (cover, name, description and buttons
 * to select a model or open its page) in groups, under a search field. Used by the wizard's zoo model question and
 * by "Apply model".
 */
final class ModelList {

	/** Groups by instance representation (alphabetically, those that are not one of the wizard's last). */
	static final Function<JsonObject, String> BY_REPRESENTATION = ModelList::representationLabel;
	static final Comparator<JsonObject> REPRESENTATION_ORDER = Comparator
			.comparing((JsonObject m) -> representationLabel(m).isEmpty())
			.thenComparing(m -> !m.has("representation") || m.get("representation").getAsString().isEmpty())
			.thenComparing(ModelList::representationLabel, String.CASE_INSENSITIVE_ORDER)
			.thenComparing(m -> m.get("nickname").getAsString());

	/** Where the zoo search keeps its checks (the bridge's find_models "cache"), so the next search is fast. */
	static final java.io.File SEARCH_CACHE = new java.io.File(io.github.biapyx.fiji.env.PythonEnv.getManagedRoot(),
			"cache/bmz_models.json");
	/** Where the covers of the models are kept, at the size of the cards. */
	private static final java.io.File COVERS_DIR = new java.io.File(io.github.biapyx.fiji.env.PythonEnv.getManagedRoot(),
			"cache/covers");

	private final List<JsonObject> models = new ArrayList<>();
	private final Function<JsonObject, String> group;
	private final Comparator<JsonObject> order;
	private final Predicate<JsonObject> selected;
	private final Consumer<JsonObject> select;
	private final JPanel cards = new WidthTrackingPanel();
	private final JTextField words = new JTextField(16);
	private final JPanel view = new JPanel(new BorderLayout(0, 4));

	/**
	 * @param title of the box around the list
	 * @param group the group of a model (its header); the models are not grouped if every group is empty
	 * @param order of the models, the groups first
	 * @param selected whether a model is the one selected (marked)
	 * @param select called when the "Select" button of a model is clicked
	 */
	ModelList(String title, Function<JsonObject, String> group, Comparator<JsonObject> order, Predicate<JsonObject> selected,
			Consumer<JsonObject> select) {
		this.group = group;
		this.order = order;
		this.selected = selected;
		this.select = select;
		words.setToolTipText("<html>Search the models: words separated by spaces, e.g. <i>cellpose dino</i>. Only the models "
				+ "with all of them in their nickname, name or tags are shown (upper or lower case does not matter)</html>");
		JPanel searchRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
		searchRow.setOpaque(false);
		searchRow.add(new JLabel(MAGNIFIER));
		searchRow.add(words);
		cards.setLayout(new BoxLayout(cards, BoxLayout.Y_AXIS));
		cards.setBackground(BiaPyFiji.WHITE);
		view.setBackground(BiaPyFiji.WHITE);
		view.setBorder(BorderFactory.createTitledBorder(title));
		view.add(searchRow, BorderLayout.NORTH);
		view.add(cards, BorderLayout.CENTER);
		// The descriptions are wrapped to the width of the list: rebuild them when it changes
		cards.addComponentListener(new java.awt.event.ComponentAdapter() {
			private int lastWidth = -1;

			@Override
			public void componentResized(java.awt.event.ComponentEvent e) {
				if (Math.abs(cards.getWidth() - lastWidth) < 8) return;
				lastWidth = cards.getWidth();
				fill();
			}
		});
		words.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
			public void insertUpdate(javax.swing.event.DocumentEvent e) { fill(); }
			public void removeUpdate(javax.swing.event.DocumentEvent e) { fill(); }
			public void changedUpdate(javax.swing.event.DocumentEvent e) { fill(); }
		});
	}

	JPanel view() {
		return view;
	}

	void clear() {
		models.clear();
		fill();
	}

	void add(JsonObject m) {
		models.add(m);
		fill();
	}

	int size() {
		return models.size();
	}

	/**
	 * Whether a model found has every word of the search (separated by spaces) in its nickname, name or tags, ignoring
	 * case. An empty search matches every model.
	 */
	static boolean matchesSearch(JsonObject m, String words) {
		StringBuilder text = new StringBuilder();
		for (String key : new String[] { "id", "nickname", "name" })
			if (m.has(key) && m.get(key).isJsonPrimitive()) text.append(m.get(key).getAsString()).append(' ');
		if (m.has("tags") && m.get("tags").isJsonArray())
			for (JsonElement t : m.getAsJsonArray("tags")) text.append(t.getAsString()).append(' ');
		String haystack = text.toString().toLowerCase(Locale.ROOT);
		for (String w : words.trim().toLowerCase(Locale.ROOT).split("\\s+"))
			if (!haystack.contains(w)) return false;
		return true;
	}

	/**
	 * Instance representation of a model found, e.g. "Foreground + flows (Cellpose) (F, Gv, Gh)", or the names of its
	 * channels when it is not one of the Wizard's (e.g. "Foreground + Contour + Mask (F, C, M)"); empty in other
	 * workflows.
	 */
	static String representationLabel(JsonObject m) {
		if (!m.has("channels") || !m.get("channels").isJsonArray() || m.getAsJsonArray("channels").size() == 0) return "";
		List<String> codes = new ArrayList<>(), names = new ArrayList<>();
		for (JsonElement c : m.getAsJsonArray("channels")) {
			codes.add(c.getAsString());
			names.add(WorkflowInfoDialog.name(c.getAsString()));
		}
		String name = m.has("representation") ? m.get("representation").getAsString() : "";
		return (name.isEmpty() ? String.join(" + ", names) : name) + " (" + String.join(", ", codes) + ")";
	}

	/** Shows the models that match the search, as cards under the header of their group. */
	void fill() {
		cards.removeAll();
		String search = words.getText();
		List<JsonObject> sorted = new ArrayList<>();
		for (JsonObject m : models) if (matchesSearch(m, search)) sorted.add(m);
		if (!search.trim().isEmpty()) {
			JLabel matches = new JLabel(sorted.size() + " of " + models.size() + " models match '" + search.trim() + "'");
			matches.setForeground(BiaPyFiji.GREY);
			matches.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
			matches.setAlignmentX(Component.LEFT_ALIGNMENT);
			cards.add(matches);
		}
		java.util.Map<String, Integer> sizes = new java.util.HashMap<>();
		for (JsonObject m : sorted) sizes.merge(group.apply(m), 1, Integer::sum);
		boolean grouped = sizes.keySet().stream().anyMatch(r -> !r.isEmpty());
		sorted.sort(order);
		String current = null;
		for (JsonObject m : sorted) {
			String g = group.apply(m);
			if (grouped && !g.equals(current)) {
				current = g;
				int n = sizes.get(g);
				JLabel header = new JLabel((g.isEmpty() ? "Other" : g) + "  —  " + n + (n == 1 ? " model" : " models"));
				header.setFont(header.getFont().deriveFont(Font.BOLD, 13f));
				header.setForeground(BiaPyFiji.BLUE);
				header.setBorder(BorderFactory.createCompoundBorder(
						BorderFactory.createMatteBorder(0, 0, 2, 0, BiaPyFiji.LIGHT_BLUE),
						BorderFactory.createEmptyBorder(cards.getComponentCount() == 0 ? 4 : 14, 6, 4, 6)));
				header.setAlignmentX(Component.LEFT_ALIGNMENT);
				header.setMaximumSize(new Dimension(Integer.MAX_VALUE, header.getPreferredSize().height));
				cards.add(header);
			}
			cards.add(card(m));
		}
		cards.revalidate();
		cards.repaint();
	}

	private static final int COVER_SIZE = 64;

	/** One model: cover image, name and description, and buttons to select it or open its BioImage.io page. */
	private JPanel card(JsonObject m) {
		boolean isSelected = selected.test(m);
		JPanel card = new JPanel(new BorderLayout(10, 0));
		card.setBackground(isSelected ? new Color(232, 243, 255) : BiaPyFiji.WHITE);
		card.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createMatteBorder(0, 0, 1, 0, BiaPyFiji.LIGHT_BLUE),
				BorderFactory.createEmptyBorder(6, 6, 6, 6)));

		JLabel cover = new JLabel(BiaPyFiji.icon("images/bmz_logo.png", COVER_SIZE));
		cover.setPreferredSize(new Dimension(COVER_SIZE, COVER_SIZE));
		String coverUrl = m.has("cover") ? m.get("cover").getAsString() : "";
		if (!coverUrl.isEmpty()) loadCover(coverUrl, cover);
		card.add(cover, BorderLayout.WEST);

		// "modest-cow 🐄": the emoji is an image (see EmojiIcons), most are missing in Java fonts
		JPanel titleRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
		titleRow.setOpaque(false);
		JLabel name = new JLabel(m.get("nickname").getAsString());
		name.setFont(name.getFont().deriveFont(Font.BOLD, 12f));
		titleRow.add(name);
		JLabel emoji = new JLabel();
		emoji.setBorder(BorderFactory.createEmptyBorder(0, 5, 0, 0));
		EmojiIcons.load(m.has("nickname_icon") ? m.get("nickname_icon").getAsString() : "", 16, emoji);
		titleRow.add(emoji);
		if (isSelected) {
			JLabel sel = new JLabel("  (selected)");
			sel.setFont(sel.getFont().deriveFont(Font.PLAIN, 12f));
			sel.setForeground(BiaPyFiji.GREEN);
			titleRow.add(sel);
		}
		JPanel buttons = new JPanel(new java.awt.GridLayout(2, 1, 0, 4));
		buttons.setOpaque(false);
		JButton selectButton = UiUtils.primaryButton("Select", "Use this model");
		selectButton.addActionListener(e -> select.accept(m));
		JButton web = UiUtils.secondaryButton("BioImage.io ↗", "Open the page of the model in the BioImage Model Zoo ("
				+ m.get("url").getAsString() + ")");
		web.addActionListener(e -> UiUtils.openUrl(m.get("url").getAsString()));
		buttons.add(selectButton);
		buttons.add(web);
		JPanel east = new JPanel(new BorderLayout());
		east.setOpaque(false);
		east.add(buttons, BorderLayout.NORTH);
		card.add(east, BorderLayout.EAST);

		// The whole description, wrapped to the width left by the cover and the buttons, and the representation below
		String html = "<span style='color:#555555'>" + escape(m.has("name") ? m.get("name").getAsString() : "")
				+ "</span><br>" + escape(m.get("description").getAsString());
		String representation = representationLabel(m);
		if (!representation.isEmpty()) html += "<br><b>Representation:</b> " + escape(representation);
		java.awt.Insets in = cards.getInsets();
		int textWidth = WizardPanel.cardsTextWidth(cards, in.left + in.right + 12 + COVER_SIZE + 20 + east.getPreferredSize().width);
		JLabel text = WizardPanel.wrappedLabel(html, textWidth, new JLabel().getFont().deriveFont(Font.PLAIN, 12f));
		JPanel middle = new JPanel(new BorderLayout(0, 2));
		middle.setOpaque(false);
		middle.add(titleRow, BorderLayout.NORTH);
		middle.add(text, BorderLayout.CENTER);
		card.add(middle, BorderLayout.CENTER);
		card.setMaximumSize(new Dimension(Integer.MAX_VALUE, card.getPreferredSize().height));
		card.setAlignmentX(Component.LEFT_ALIGNMENT);
		return card;
	}

	private static String escape(String s) {
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>");
	}

	/** A magnifying glass, for the search of models. */
	private static final javax.swing.Icon MAGNIFIER = new javax.swing.Icon() {
		@Override
		public void paintIcon(Component c, java.awt.Graphics g, int x, int y) {
			java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
			g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setColor(BiaPyFiji.GREY);
			g2.setStroke(new java.awt.BasicStroke(2f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
			g2.drawOval(x + 2, y + 2, 10, 10);
			g2.drawLine(x + 11, y + 11, x + 16, y + 16);
			g2.dispose();
		}

		@Override
		public int getIconWidth() {
			return 18;
		}

		@Override
		public int getIconHeight() {
			return 18;
		}
	};

	private static final java.util.Map<String, ImageIcon> COVERS = new java.util.concurrent.ConcurrentHashMap<>();
	/** Covers being downloaded and the labels waiting for each. */
	private static final java.util.Map<String, List<JLabel>> COVERS_LOADING = new java.util.HashMap<>();
	private static final java.util.concurrent.ExecutorService COVER_LOADER = java.util.concurrent.Executors
			.newFixedThreadPool(4, r -> {
				Thread t = new Thread(r, "BiaPy-cover-loader");
				t.setDaemon(true);
				return t;
			});

	/** The cover at the size of the cards (null if it could not be read). */
	private static java.awt.image.BufferedImage scaled(java.awt.image.BufferedImage img) {
		if (img == null) return null;
		double scale = Math.min((double) COVER_SIZE / img.getWidth(), (double) COVER_SIZE / img.getHeight());
		int w = Math.max(1, (int) (img.getWidth() * scale)), h = Math.max(1, (int) (img.getHeight() * scale));
		java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
		java.awt.Graphics2D g = out.createGraphics();
		g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
		g.drawImage(img.getScaledInstance(w, h, java.awt.Image.SCALE_SMOOTH), 0, 0, null);
		g.dispose();
		return out;
	}

	/** Downloads the cover image in the background (emoji icons are not available in Java fonts). */
	private static void loadCover(String url, JLabel target) {
		ImageIcon cached = COVERS.get(url);
		if (cached != null) {
			target.setIcon(cached);
			return;
		}
		// The cards are rebuilt as models are found: a cover being downloaded goes to every card waiting for it
		synchronized (COVERS_LOADING) {
			List<JLabel> waiting = COVERS_LOADING.get(url);
			if (waiting != null) {
				waiting.add(target);
				return;
			}
			waiting = new ArrayList<>();
			waiting.add(target);
			COVERS_LOADING.put(url, waiting);
		}
		COVER_LOADER.submit(() -> {
			ImageIcon loaded = null;
			java.io.File file = new java.io.File(COVERS_DIR, Integer.toHexString(url.hashCode()) + "_" + url.length() + ".png");
			try {
				java.awt.image.BufferedImage img = file.isFile() ? javax.imageio.ImageIO.read(file) : null;
				if (img == null) {
					java.net.URLConnection conn = new java.net.URL(url).openConnection();
					conn.setConnectTimeout(10000);
					conn.setReadTimeout(20000);
					try (java.io.InputStream in = conn.getInputStream()) {
						img = scaled(javax.imageio.ImageIO.read(in));
					}
					if (img != null && (COVERS_DIR.isDirectory() || COVERS_DIR.mkdirs()))
						javax.imageio.ImageIO.write(img, "png", file);
				}
				if (img != null) {
					loaded = new ImageIcon(img);
					COVERS.put(url, loaded);
				}
			} catch (Exception e) {
				// Keep the BioImage Model Zoo logo
			}
			List<JLabel> waiting;
			synchronized (COVERS_LOADING) {
				waiting = COVERS_LOADING.remove(url);
			}
			ImageIcon icon = loaded;
			if (icon != null) SwingUtilities.invokeLater(() -> {
				for (JLabel l : waiting) l.setIcon(icon);
			});
		});
	}
}
