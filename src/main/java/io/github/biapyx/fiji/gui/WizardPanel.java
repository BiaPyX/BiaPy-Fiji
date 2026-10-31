package io.github.biapyx.fiji.gui;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JDialog;
import javax.swing.ButtonGroup;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import ij.Prefs;
import io.github.biapyx.fiji.BiaPyFiji;
import io.github.biapyx.fiji.env.PythonBridge;
import io.github.biapyx.fiji.wizard.WizardModel;
import io.github.biapyx.fiji.wizard.WizardModel.Question;
import io.github.biapyx.fiji.wizard.WizardModel.Type;

/**
 * WIZARD tab: a questionnaire that creates a BiaPy configuration file, as in BiaPy-GUI. It has three
 * pages: where to save the configuration, the questions (with a table of contents on the left and the
 * Wizard's help next to each question) and a summary of the answers.
 */
public class WizardPanel extends JPanel {

	private static final long serialVersionUID = 1L;
	private static final String START = "start", QUESTIONS = "questions", SUMMARY = "summary";
	private static final String PREF_YAML_DIR = "biapy.wizard.yaml_dir";

	private final MainPanel main;
	private final WizardModel model = new WizardModel();
	private final CardLayout pages = new CardLayout();
	private final JPanel pagePanel = new JPanel(pages);

	// Start page
	private final JTextField yamlDir = new JTextField(30);
	private final JTextField yamlName = new JTextField("my_workflow", 20);

	// Questions page
	private final JTree toc = new JTree();
	private boolean updatingToc = false;
	private boolean tocLocked = false;
	private final JLabel questionNumber = UiUtils.title("", 18f);
	private final WrappingTextArea questionText = new WrappingTextArea("");
	private final JPanel answerArea = new JPanel(new BorderLayout());
	private final JEditorPane helpPane = UiUtils.htmlPane("");
	/** Question text, answer and help scroll together in one page; the help section is at its end. */
	private final WidthTrackingPanel page = new WidthTrackingPanel();
	private final JScrollPane pageScroll = new JScrollPane(page);
	private final JPanel helpSection = new JPanel(new BorderLayout());
	private final JButton backBtn = UiUtils.primaryButton("< Back", "Previous question");
	private final JButton nextBtn = UiUtils.primaryButton("Continue >",
			"Next question (or the summary after the last one)");
	private final JProgressBar busyBar = new JProgressBar();
	private final JLabel busyLabel = new JLabel(" ");
	private volatile PythonBridge runningBridge;

	// Summary page
	private final JPanel summaryList = new JPanel();
	private final JLabel summaryFile = new JLabel();

	public WizardPanel(MainPanel main) {
		super(new BorderLayout());
		this.main = main;
		setBackground(BiaPyFiji.WHITE);
		pagePanel.add(buildStartPage(), START);
		pagePanel.add(buildQuestionsPage(), QUESTIONS);
		pagePanel.add(buildSummaryPage(), SUMMARY);
		add(pagePanel, BorderLayout.CENTER);
		pages.show(pagePanel, START);
	}

	///////////////
	// Start page
	///////////////
	private JPanel buildStartPage() {
		JPanel p = new JPanel(new GridBagLayout());
		p.setBackground(BiaPyFiji.WHITE);
		p.setBorder(BorderFactory.createEmptyBorder(10, 30, 10, 30));
		GridBagConstraints c = new GridBagConstraints();
		c.gridx = 0;
		c.gridwidth = 3;
		c.insets = new Insets(4, 4, 4, 4);

		c.gridy = 0;
		p.add(new JLabel(BiaPyFiji.icon("wizard/wizard.png", 150)), c);

		JLabel msg = new JLabel("<html><div style='width:520px; text-align:center'><p>Hello, I'm the wizard of BiaPy, and "
				+ "I'll be guiding you through this process. During the questionnaire, use the help button (with my "
				+ "picture) to get more information about each question.</p><br><p>This questionnaire will guide you in creating a configuration "
				+ "file needed to run BiaPy. To start, please fill in the fields below to specify where the configuration "
				+ "file should be created. Once completed, click the \"Start\" button to begin the questionnaire.</p></div></html>");
		msg.setFont(msg.getFont().deriveFont(Font.PLAIN, 14f));
		msg.setHorizontalAlignment(JLabel.CENTER);
		c.gridy = 1;
		c.insets = new Insets(4, 4, 20, 4);
		p.add(msg, c);

		// Paths use the full width of the window
		c.gridwidth = 1;
		c.insets = new Insets(4, 4, 4, 4);
		c.anchor = GridBagConstraints.WEST;
		c.gridy = 2;
		c.gridx = 0;
		p.add(new JLabel("Folder to save the configuration file:"), c);
		c.gridx = 1;
		c.fill = GridBagConstraints.HORIZONTAL;
		c.weightx = 1;
		yamlDir.setText(Prefs.get(PREF_YAML_DIR, System.getProperty("user.home")));
		p.add(yamlDir, c);
		c.gridx = 2;
		c.fill = GridBagConstraints.NONE;
		c.weightx = 0;
		JButton browse = UiUtils.secondaryButton("Browse", "Choose the folder where the configuration file is saved");
		browse.addActionListener(e -> {
			File f = UiUtils.chooseDirectory(this, yamlDir.getText());
			if (f != null) yamlDir.setText(f.getAbsolutePath());
		});
		p.add(browse, c);
		c.gridy = 3;
		c.gridx = 0;
		p.add(new JLabel("Configuration file name:"), c);
		c.gridx = 1;
		c.fill = GridBagConstraints.HORIZONTAL;
		c.weightx = 1;
		p.add(yamlName, c);
		c.gridx = 2;
		c.fill = GridBagConstraints.NONE;
		c.weightx = 0;
		p.add(new JLabel(".yaml"), c);

		JButton start = UiUtils.primaryButton("Start", "Start answering the questions");
		start.addActionListener(e -> start());
		c.gridy = 4;
		c.gridx = 0;
		c.gridwidth = 3;
		c.anchor = GridBagConstraints.CENTER;
		c.insets = new Insets(24, 4, 4, 4);
		p.add(start, c);
		return p;
	}

	private void start() {
		String dir = yamlDir.getText().trim();
		String name = yamlName.getText().trim();
		if (dir.isEmpty() || name.isEmpty()) {
			UiUtils.error(this, "Please select a folder and a name for the configuration file.");
			return;
		}
		Prefs.set(PREF_YAML_DIR, dir);
		model.setCurrent(0);
		rebuildToc();
		showQuestion(0);
		pages.show(pagePanel, QUESTIONS);
	}

	/** Path of the configuration file that the wizard will create. */
	private File yamlFile() {
		String name = yamlName.getText().trim();
		if (!name.endsWith(".yaml") && !name.endsWith(".yml")) name += ".yaml";
		return new File(yamlDir.getText().trim(), name);
	}

	///////////////////
	// Questions page
	///////////////////
	private JPanel buildQuestionsPage() {
		JPanel p = new JPanel(new BorderLayout());
		p.setBackground(BiaPyFiji.WHITE);

		// Table of contents
		JPanel left = new JPanel(new BorderLayout(0, 6));
		left.setBackground(BiaPyFiji.WHITE);
		left.setBorder(BorderFactory.createEmptyBorder(12, 8, 12, 4));
		toc.setRootVisible(false);
		toc.setShowsRootHandles(false);
		if (toc.getUI() instanceof javax.swing.plaf.basic.BasicTreeUI) {
			javax.swing.plaf.basic.BasicTreeUI ui = (javax.swing.plaf.basic.BasicTreeUI) toc.getUI();
			ui.setLeftChildIndent(Math.max(2, ui.getLeftChildIndent() / 2));
			ui.setRightChildIndent(Math.max(2, ui.getRightChildIndent() / 2));
		}
		toc.setFont(toc.getFont().deriveFont(Font.PLAIN, 12f));
		toc.setCellRenderer(new TocRenderer());
		toc.addTreeSelectionListener(e -> {
			if (updatingToc) return;
			if (tocLocked) {
				SwingUtilities.invokeLater(() -> selectInToc(model.getCurrent()));
				return;
			}
			Object last = e.getPath().getLastPathComponent();
			if (last instanceof DefaultMutableTreeNode && ((DefaultMutableTreeNode) last).getUserObject() instanceof Question) {
				int target = ((Question) ((DefaultMutableTreeNode) last).getUserObject()).index;
				if (target == model.getCurrent()) return;
				// Rebuilding the tree inside its own selection event is unsafe
				SwingUtilities.invokeLater(() -> {
					readCurrentAnswer();
					// Leaving the workflow question through the list also needs the confirmation
					if (!confirmWorkflowOnLeave(target < model.getCurrent())) {
						selectInToc(model.getCurrent());
						return;
					}
					showQuestion(target);
				});
			}
		});
		javax.swing.ToolTipManager.sharedInstance().registerComponent(toc);
		JScrollPane tocScroll = new JScrollPane(toc, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
				JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		tocScroll.setPreferredSize(new Dimension(180, 0));
		left.add(UiUtils.title("Questions", 13f), BorderLayout.NORTH);
		left.add(tocScroll, BorderLayout.CENTER);
		JButton clear = UiUtils.secondaryButton("Clear answers", "Forget all the answers and start again from the first question");
		clear.setFont(clear.getFont().deriveFont(11f));
		clear.addActionListener(e -> {
			if (!UiUtils.confirm(this, "Are you sure you want to clear all answers?")) return;
			model.clear();
			previews.clear();
			confirmedWorkflow = null;
			pretrainedSuggestedKey = null;
			rebuildToc();
			showQuestion(0);
		});
		left.add(clear, BorderLayout.SOUTH);
		p.add(left, BorderLayout.WEST);

		// Question + answer + help, in a single scrolling page under a fixed title row
		JPanel center = new JPanel(new BorderLayout(0, 8));
		center.setBackground(BiaPyFiji.WHITE);
		center.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 16));

		JPanel header = new JPanel(new BorderLayout(10, 0));
		header.setOpaque(false);
		header.add(questionNumber, BorderLayout.CENTER);
		JButton goToHelp = UiUtils.secondaryButton("Go to help", "Scroll down to the Wizard's explanation of this question");
		goToHelp.setIcon(BiaPyFiji.icon("wizard/wizard.png", 32));
		goToHelp.addActionListener(e -> scrollToHelp());
		header.add(goToHelp, BorderLayout.EAST);
		center.add(header, BorderLayout.NORTH);

		questionText.setFont(questionNumber.getFont().deriveFont(Font.PLAIN, 15f));
		answerArea.setOpaque(false);
		helpPane.setOpaque(true);
		helpPane.setBackground(BiaPyFiji.WHITE);
		// Its caret must not scroll the page (e.g. to the help when its text is set)
		((javax.swing.text.DefaultCaret) helpPane.getCaret()).setUpdatePolicy(javax.swing.text.DefaultCaret.NEVER_UPDATE);
		helpSection.setOpaque(false);
		helpSection.setBorder(BorderFactory.createTitledBorder(BorderFactory.createLineBorder(BiaPyFiji.LIGHT_BLUE, 2),
				"Wizard help"));
		helpSection.add(helpPane, BorderLayout.CENTER);
		// BorderLayouts nested so that every part takes the page width and its own height
		JPanel answerAndHelp = new JPanel(new BorderLayout(0, 16));
		answerAndHelp.setOpaque(false);
		answerAndHelp.add(answerArea, BorderLayout.NORTH);
		answerAndHelp.add(helpSection, BorderLayout.CENTER);
		page.setLayout(new BorderLayout(0, 12));
		page.setBackground(BiaPyFiji.WHITE);
		page.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 6));
		page.add(questionText, BorderLayout.NORTH);
		page.add(answerAndHelp, BorderLayout.CENTER);
		pageScroll.setBorder(null);
		pageScroll.getViewport().setBackground(BiaPyFiji.WHITE); // below pages shorter than the window
		pageScroll.getVerticalScrollBar().setUnitIncrement(16);
		pageScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		center.add(pageScroll, BorderLayout.CENTER);

		JPanel bottom = new JPanel(new BorderLayout());
		bottom.setOpaque(false);
		JPanel busy = new JPanel(new BorderLayout(8, 0));
		busy.setOpaque(false);
		busyBar.setIndeterminate(true);
		busyBar.setVisible(false);
		busyBar.setPreferredSize(new Dimension(120, 14));
		busyLabel.setForeground(BiaPyFiji.GREY);
		busy.add(busyBar, BorderLayout.WEST);
		busy.add(busyLabel, BorderLayout.CENTER);
		busy.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 12));
		bottom.add(backBtn, BorderLayout.WEST);
		bottom.add(busy, BorderLayout.CENTER);
		bottom.add(nextBtn, BorderLayout.EAST);
		center.add(bottom, BorderLayout.SOUTH);
		p.add(center, BorderLayout.CENTER);

		backBtn.addActionListener(e -> {
			readCurrentAnswer();
			confirmWorkflowOnLeave(true);
			int prev = model.previousIndex();
			if (prev < 0) pages.show(pagePanel, START);
			else showQuestion(prev);
		});
		nextBtn.addActionListener(e -> {
			readCurrentAnswer();
			if (!confirmWorkflowOnLeave(false)) return; // the user wants to change the workflow
			int next = model.nextIndex();
			if (next >= model.size()) {
				if (!suggestPretrainedModels()) prepareSummary();
			} else {
				showQuestion(next);
			}
		});
		return p;
	}

	/** Workflow answer (question 2) the user already confirmed in the dialog; null if none yet. */
	private Integer confirmedWorkflow = null;

	/**
	 * The workflow is the most important answer: when the user leaves its question after choosing or changing
	 * it, a dialog explains the chosen workflow and asks to confirm it.
	 *
	 * @param back whether the user is going backwards. Then the choice is "keep it" or "undo the change"
	 *            (back to the previously confirmed workflow, or unanswered).
	 * @return false if the user wants to stay on the question to change the workflow
	 */
	private boolean confirmWorkflowOnLeave(boolean back) {
		int wq = model.questionFor("PROBLEM.TYPE");
		if (wq < 0 || model.getCurrent() != wq) return true;
		Object a = model.getAnswered(wq);
		if (!(a instanceof Integer) || a.equals(confirmedWorkflow)) return true;
		Question q = model.getQuestion(wq);
		String type = model.getAnswer("PROBLEM.TYPE").getAsString();
		String answer = q.answers.get((Integer) a);
		if (!back) {
			if (!WorkflowConfirmDialog.ask(this, type, answer, WorkflowConfirmDialog.Mode.CONTINUE)) return false;
			confirmedWorkflow = (Integer) a;
			return true;
		}
		if (WorkflowConfirmDialog.ask(this, type, answer, WorkflowConfirmDialog.Mode.BACK)) {
			confirmedWorkflow = (Integer) a;
		} else {
			if (confirmedWorkflow != null) model.setChoice(wq, confirmedWorkflow);
			else model.unsetChoice(wq);
			rebuildToc();
		}
		return true;
	}

	/** The widget holding the answer of the question being shown (to read text fields when moving away). */
	private JTextField currentTextField;

	private void readCurrentAnswer() {
		if (currentTextField == null) return;
		Question q = model.getQuestion(model.getCurrent());
		if (q.type == Type.PATH) model.setPath(q.index, currentTextField.getText());
		else if (q.type == Type.MODEL_BIAPY) model.setModel(q.index, currentTextField.getText());
		else if (q.type == Type.MODEL_OTHERS) {
			String t = currentTextField.getText().trim();
			if (!t.isEmpty() && !t.equals(model.getAnswered(q.index))) model.setModel(q.index, t);
		}
		rebuildToc();
	}

	private void showQuestion(int i) {
		model.setCurrent(i);
		Question q = model.getQuestion(i);
		questionNumber.setText("Question " + (i + 1));
		// Start every question from the top of the page (once it is laid out with its new content)
		SwingUtilities.invokeLater(() -> {
			page.revalidate();
			pageScroll.getViewport().setViewPosition(new java.awt.Point(0, 0));
		});
		questionText.setText(q.text);
		helpPane.setText(UiUtils.htmlPage(resolveImages(q.help)));

		answerArea.removeAll();
		currentTextField = null;
		switch (q.type) {
		case CHOICE:
			answerArea.add(buildChoice(q), BorderLayout.NORTH);
			break;
		case PATH:
			answerArea.add(buildPath(q), BorderLayout.NORTH);
			break;
		case REPRESENTATION:
			answerArea.add(buildRepresentation(q), BorderLayout.CENTER);
			break;
		default:
			answerArea.add(buildModel(q), BorderLayout.CENTER);
		}
		answerArea.revalidate();
		answerArea.repaint();
		selectInToc(i);
	}

	private JPanel buildChoice(Question q) {
		JPanel p = new JPanel();
		p.setOpaque(false);
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		ButtonGroup g = new ButtonGroup();
		Object sel = model.getAnswered(q.index);
		for (int a = 0; a < q.answers.size(); a++) {
			JRadioButton rb = new JRadioButton(q.answers.get(a));
			rb.setOpaque(false);
			rb.setFont(rb.getFont().deriveFont(14f));
			rb.setSelected(Integer.valueOf(a).equals(sel));
			final int ans = a;
			rb.addActionListener(e -> {
				model.setChoice(q.index, ans);
				rebuildToc();
			});
			g.add(rb);
			p.add(rb);
			p.add(Box.createVerticalStrut(4));
		}
		return p;
	}

	/** Scrolls the page so that the Wizard help is at its top. */
	private void scrollToHelp() {
		Runnable go = () -> {
			page.validate();
			int max = Math.max(0, page.getHeight() - pageScroll.getViewport().getHeight());
			int y = SwingUtilities.convertPoint(helpSection, 0, 0, page).y;
			pageScroll.getViewport().setViewPosition(new java.awt.Point(0, Math.min(y, max)));
		};
		go.run();
		// Again once pending layout changes (e.g. texts rewrapped to the page width) are done
		SwingUtilities.invokeLater(go);
	}

	private static final int REPRESENTATION_IMAGE_HEIGHT = 80;
	/** Wider images are made smaller, so the "Expand image" button fits next to them. */
	private static final int REPRESENTATION_IMAGE_MAX_WIDTH = 300;

	/**
	 * Answers judged from the data (e.g. instance representations from the shape of the instances): the ones
	 * that suit it as cards with an image, the recommended first, and below them the others with the reason
	 * why they are not offered.
	 */
	private JPanel buildRepresentation(Question q) {
		JPanel p = new JPanel(new BorderLayout(0, 14));
		p.setOpaque(false);
		String summary = model.dataSummary(q);
		if (summary != null) p.add(wrappingText("Your instances: " + summary, Font.PLAIN, BiaPyFiji.GREY), BorderLayout.NORTH);
		JPanel cards = new WidthTrackingPanel();
		cards.setLayout(new BoxLayout(cards, BoxLayout.Y_AXIS));
		cards.setBackground(BiaPyFiji.WHITE);
		fillRepresentationCards(q, cards);
		// The texts are wrapped to the width of the list: rebuild them when it changes
		cards.addComponentListener(new java.awt.event.ComponentAdapter() {
			private int lastWidth = -1;

			@Override
			public void componentResized(java.awt.event.ComponentEvent e) {
				if (Math.abs(cards.getWidth() - lastWidth) < 8) return;
				lastWidth = cards.getWidth();
				fillRepresentationCards(q, cards);
			}
		});
		cards.setBorder(BorderFactory.createLineBorder(BiaPyFiji.LIGHT_BLUE, 1));
		p.add(cards, BorderLayout.CENTER);
		return p;
	}

	private void fillRepresentationCards(Question q, JPanel cards) {
		cards.removeAll();
		List<WizardModel.Option> options = new ArrayList<>(model.options(q));
		options.sort((a, b) -> Boolean.compare(b.recommended, a.recommended));
		StringBuilder hidden = new StringBuilder();
		for (WizardModel.Option op : options) {
			if (op.available) cards.add(representationCard(q, op, cards));
			else hidden.append("<li><b>").append(q.answers.get(op.index)).append("</b>: ").append(op.reason).append("</li>");
		}
		if (hidden.length() > 0) {
			JLabel t = wrappedLabel("Not offered for your instances:<ul style='margin-left:14px'>" + hidden + "</ul>",
					cardsTextWidth(cards, 16), new JLabel().getFont().deriveFont(Font.PLAIN, 11f));
			t.setForeground(BiaPyFiji.GREY);
			t.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
			t.setAlignmentX(Component.LEFT_ALIGNMENT);
			cards.add(t);
		}
		cards.revalidate();
		cards.repaint();
	}

	/** Non-editable text that wraps to the available width. */
	private static WrappingTextArea wrappingText(String text, int style, Color color) {
		WrappingTextArea t = new WrappingTextArea(text);
		t.setFont(new JLabel().getFont().deriveFont(style, 12f));
		t.setForeground(color);
		return t;
	}

	/** Width available for text in the list of cards, minus {@code used} pixels (a guess before it is shown). */
	private static int cardsTextWidth(JPanel cards, int used) {
		int w = cards.getWidth() > 0 ? cards.getWidth() : 520;
		return Math.max(120, w - used - 8);
	}

	/**
	 * Label with HTML text wrapped to {@code width}: its height is the one of the text laid out at that width
	 * (a JLabel alone sizes HTML text as a single line).
	 */
	private static JLabel wrappedLabel(String html, int width, Font font) {
		JLabel l = new JLabel("<html>" + html + "</html>");
		l.setFont(font);
		l.setVerticalAlignment(SwingConstants.TOP);
		javax.swing.text.View view = (javax.swing.text.View) l.getClientProperty(javax.swing.plaf.basic.BasicHTML.propertyKey);
		if (view != null) {
			view.setSize(width, 0);
			l.setPreferredSize(new Dimension(width, (int) Math.ceil(view.getPreferredSpan(javax.swing.text.View.Y_AXIS))));
		}
		return l;
	}

	/** One answer: radio button and badge on top, and below them its image and the description and reason. */
	private JPanel representationCard(Question q, WizardModel.Option op, JPanel cards) {
		boolean selected = Integer.valueOf(op.index).equals(model.getAnswered(q.index));
		JPanel card = new JPanel(new BorderLayout(0, 4));
		card.setBackground(selected ? new Color(232, 243, 255) : BiaPyFiji.WHITE);
		card.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createMatteBorder(0, 0, 1, 0, BiaPyFiji.LIGHT_BLUE),
				BorderFactory.createEmptyBorder(6, 6, 6, 6)));

		JRadioButton rb = new JRadioButton(q.answers.get(op.index));
		rb.setOpaque(false);
		rb.setFont(rb.getFont().deriveFont(Font.BOLD, 13f));
		rb.setSelected(selected);
		// "Recommended" on top, centered, above the title
		JPanel top = new JPanel(new BorderLayout(0, 2));
		top.setOpaque(false);
		if (op.recommended) {
			JLabel rec = new JLabel("Recommended", SwingConstants.CENTER);
			rec.setFont(rec.getFont().deriveFont(Font.BOLD, 13f));
			rec.setForeground(BiaPyFiji.GREEN);
			top.add(rec, BorderLayout.NORTH);
		}
		top.add(rb, BorderLayout.CENTER);
		card.add(top, BorderLayout.NORTH);

		String image = op.index < q.answerImages.size() ? q.answerImages.get(op.index) : null;
		ImageIcon picIcon = image == null ? new ImageIcon() : BiaPyFiji.icon("wizard/" + image, REPRESENTATION_IMAGE_HEIGHT);
		if (picIcon.getIconWidth() > REPRESENTATION_IMAGE_MAX_WIDTH) // e.g. HoVer-Net's, with five panels
			picIcon = new ImageIcon(picIcon.getImage().getScaledInstance(REPRESENTATION_IMAGE_MAX_WIDTH,
					picIcon.getIconHeight() * REPRESENTATION_IMAGE_MAX_WIDTH / picIcon.getIconWidth(), java.awt.Image.SCALE_SMOOTH));
		JLabel pic = new JLabel(picIcon);
		if (pic.getIcon() == null || pic.getIcon().getIconWidth() <= 0) {
			pic.setText("(image not available)");
			pic.setForeground(BiaPyFiji.GREY);
		}
		pic.setHorizontalAlignment(SwingConstants.LEFT);
		String desc = op.index < q.answerDescriptions.size() ? q.answerDescriptions.get(op.index) : "";
		String reason = op.reason.isEmpty() ? ""
				: "<br><font color='#4090fd'><b>Reason:</b> <i>" + op.reason + "</i></font>";
		int textWidth = cardsTextWidth(cards, 22 + 12);
		JLabel text = wrappedLabel(desc + reason, textWidth, new JLabel().getFont().deriveFont(Font.PLAIN, 12f));
		JPanel imageRow = new JPanel();
		imageRow.setLayout(new BoxLayout(imageRow, BoxLayout.X_AXIS));
		imageRow.setOpaque(false);
		imageRow.add(pic);
		// The large version of the image (real data), in a window of its own
		String full = image == null ? null : "wizard/" + image.replaceAll("\\.png$", "_full.png");
		if (full != null && BiaPyFiji.resource(full) != null) {
			// Two lines, so it fits next to the widest images
			JButton expand = UiUtils.secondaryButton("<html>Expand<br>image</html>");
			expand.setMaximumSize(expand.getPreferredSize()); // not stretched by the row
			expand.setToolTipText("Show this example larger");
			expand.addActionListener(e -> showFullImage(q.answers.get(op.index), full));
			expand.setAlignmentY(Component.CENTER_ALIGNMENT);
			pic.setAlignmentY(Component.CENTER_ALIGNMENT);
			imageRow.add(Box.createHorizontalStrut(12));
			imageRow.add(expand);
		}
		imageRow.add(Box.createHorizontalGlue());
		JPanel body = new JPanel(new BorderLayout(0, 6));
		body.setOpaque(false);
		body.setBorder(BorderFactory.createEmptyBorder(2, 22, 0, 0)); // aligned with the radio button text
		body.add(imageRow, BorderLayout.NORTH);
		body.add(text, BorderLayout.CENTER);
		card.add(body, BorderLayout.CENTER);

		Runnable select = () -> {
			model.setChoice(q.index, op.index);
			rebuildToc();
			fillRepresentationCards(q, cards);
		};
		rb.addActionListener(e -> select.run());
		java.awt.event.MouseAdapter click = new java.awt.event.MouseAdapter() {
			@Override
			public void mouseClicked(java.awt.event.MouseEvent e) {
				select.run();
			}
		};
		for (Component c : new Component[] { card, pic, text, imageRow, top })
			c.addMouseListener(click);
		card.setMaximumSize(new Dimension(Integer.MAX_VALUE, card.getPreferredSize().height));
		card.setAlignmentX(Component.LEFT_ALIGNMENT);
		return card;
	}

	/** Window with an image of the plugin, scaled down to fit the screen if needed. */
	private void showFullImage(String title, String resource) {
		javax.swing.JDialog dialog = new javax.swing.JDialog(SwingUtilities.getWindowAncestor(this), title + ": example",
				java.awt.Dialog.ModalityType.APPLICATION_MODAL);
		JButton close = UiUtils.primaryButton("Close", "Close this window");
		close.addActionListener(e -> dialog.dispose());
		dialog.setContentPane(fullImageContent(resource, java.awt.Toolkit.getDefaultToolkit().getScreenSize(), close));
		dialog.getRootPane().setDefaultButton(close);
		dialog.pack();
		dialog.setLocationRelativeTo(SwingUtilities.getWindowAncestor(this));
		dialog.setVisible(true);
	}

	/** Contents of the window of {@link #showFullImage}: the image, at most 90% x 75% of the screen, and a button. */
	static JPanel fullImageContent(String resource, Dimension screen, JButton close) {
		ImageIcon icon = BiaPyFiji.icon(resource);
		double scale = Math.min(1.0, Math.min(0.9 * screen.width / icon.getIconWidth(),
				0.75 * screen.height / icon.getIconHeight()));
		if (scale < 1.0)
			icon = new ImageIcon(icon.getImage().getScaledInstance((int) (icon.getIconWidth() * scale),
					(int) (icon.getIconHeight() * scale), java.awt.Image.SCALE_SMOOTH));
		JPanel content = new JPanel(new BorderLayout(0, 10));
		content.setBackground(BiaPyFiji.WHITE);
		content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
		JLabel picture = new JLabel(icon);
		picture.setHorizontalAlignment(SwingConstants.CENTER);
		content.add(picture, BorderLayout.CENTER);
		JPanel buttons = new JPanel();
		buttons.setOpaque(false);
		buttons.add(close);
		content.add(buttons, BorderLayout.SOUTH);
		return content;
	}

	private JPanel buildPath(Question q) {
		JPanel p = new JPanel(new GridBagLayout());
		p.setOpaque(false);
		GridBagConstraints c = new GridBagConstraints();
		c.insets = new Insets(4, 0, 4, 6);
		c.gridx = 0;
		c.gridy = 0;
		c.weightx = 1;
		c.fill = GridBagConstraints.HORIZONTAL;
		JTextField field = new JTextField(model.getAnswered(q.index) == null ? "" : model.getAnswered(q.index).toString());
		currentTextField = field;
		p.add(field, c);
		c.weightx = 0;
		c.fill = GridBagConstraints.NONE;
		c.gridx = 1;
		JButton browse = UiUtils.secondaryButton("Browse", "Choose the folder with the images");
		p.add(browse, c);
		JButton check = UiUtils.primaryButton("Check data",
				"Read the images of the folder to check them and see examples; their sizes and values guide the configuration");
		c.gridx = 0;
		c.gridy = 1;
		c.gridwidth = 2;
		c.anchor = GridBagConstraints.CENTER;
		c.insets = new Insets(10, 0, 4, 0);
		p.add(check, c);
		// Plain text: an HTML label would keep the width of its previous text and wrap the new one
		JLabel status = new JLabel();
		status.setFont(status.getFont().deriveFont(Font.BOLD));
		c.gridy = 2;
		c.insets = new Insets(2, 0, 4, 0);
		p.add(status, c);
		String key = q.firstKey();
		String phase = key.contains("TRAIN") ? "TRAIN" : "TEST";
		Runnable refresh = () -> {
			boolean ok = model.isDataChecked(q) && field.getText().trim().equals(model.getAnswered(q.index));
			String labels = key.contains("GT_PATH") ? model.targetDataPath(phase) : "";
			status.setText(!ok ? "\u2191 Data not checked yet!"
					: labels.isEmpty() ? "Data checked!" : "Data checked! (targets: '" + labels + "' of the OME-Zarr images)");
			status.setForeground(ok ? BiaPyFiji.GREEN : BiaPyFiji.RED);
		};
		refresh.run();
		field.getDocument().addDocumentListener(new SimpleDocListener(refresh));
		browse.addActionListener(e -> {
			File f = UiUtils.chooseDirectory(this, field.getText());
			if (f != null) field.setText(f.getAbsolutePath());
		});
		check.addActionListener(e -> checkData(q, field.getText(), refresh, ""));
		// Targets stored in the raw OME-Zarr images, as their label images (e.g. the ground truth)
		String rawKey = "DATA." + phase + ".PATH";
		String raw = model.checkedFolder(rawKey);
		List<String> labels = key.contains("GT_PATH") ? model.omeZarrLabels(rawKey) : new ArrayList<>();
		if (raw != null && !labels.isEmpty()) {
			c.gridy = 3;
			c.insets = new Insets(10, 0, 2, 0);
			p.add(new JLabel("Or use as targets a label image stored in the raw OME-Zarr images:"), c);
			JPanel row = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 0));
			row.setOpaque(false);
			javax.swing.JComboBox<String> names = new javax.swing.JComboBox<>(labels.toArray(new String[0]));
			String current = model.targetDataPath(phase);
			if (current.startsWith("labels.")) names.setSelectedItem(current.substring("labels.".length()));
			row.add(names);
			JButton use = UiUtils.secondaryButton("Use as targets",
					"Check the label image chosen of each raw image as its target, instead of the images of a folder");
			use.addActionListener(e -> {
				field.setText(raw);
				checkData(q, raw, refresh, "labels." + names.getSelectedItem());
			});
			row.add(use);
			c.gridy = 4;
			c.insets = new Insets(2, 0, 4, 0);
			p.add(row, c);
		}
		return p;
	}

	private JPanel buildModel(Question q) {
		JPanel p = new JPanel(new BorderLayout(0, 8));
		p.setOpaque(false);
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setOpaque(false);
		JTextField field = new JTextField(model.getAnswered(q.index) == null ? "" : model.getAnswered(q.index).toString());
		currentTextField = field;
		row.add(field, BorderLayout.CENTER);
		p.add(row, BorderLayout.NORTH);
		if (q.type == Type.MODEL_BIAPY) {
			JButton browse = UiUtils.secondaryButton("Browse", "Choose the checkpoint of a model trained with BiaPy");
			browse.addActionListener(e -> {
				File f = UiUtils.chooseFile(this, field.getText(), "BiaPy checkpoint (*.pth, *.safetensors)", "pth",
						"safetensors");
				if (f != null) {
					field.setText(f.getAbsolutePath());
					model.setModel(q.index, f.getAbsolutePath());
					rebuildToc();
				}
			});
			row.add(browse, BorderLayout.EAST);
		} else {
			field.setEditable(false);
			JButton check = UiUtils.primaryButton("Check models",
					"Look for pretrained models of the BioImage Model Zoo that BiaPy can use for this workflow");
			row.add(check, BorderLayout.EAST);
			JPanel cards = new WidthTrackingPanel();
			cards.setLayout(new BoxLayout(cards, BoxLayout.Y_AXIS));
			cards.setBackground(BiaPyFiji.WHITE);
			cards.setBorder(BorderFactory.createTitledBorder("Compatible pretrained models from the BioImage Model Zoo"));
			p.add(cards, BorderLayout.CENTER);
			// Keep the models found while the workflow and dimensions stay the same
			if (modelsKey().equals(foundModelsKey))
				for (JsonObject m : foundModels) cards.add(modelCard(m, q, field, cards));
			check.addActionListener(e -> findModels(q, field, cards));
			// Coming from the reminder at the end of the questions: the search starts by itself
			if (searchModelsOnShow) {
				searchModelsOnShow = false;
				if (!modelsKey().equals(foundModelsKey)) SwingUtilities.invokeLater(() -> findModels(q, field, cards));
			}
		}
		return p;
	}

	/** Workflow and dimensions ({@link #modelsKey()}) for which a pretrained model was already suggested, or null. */
	private String pretrainedSuggestedKey = null;
	/** The model question is shown from that suggestion: it starts the search. */
	private boolean searchModelsOnShow = false;

	/**
	 * Once all questions are answered with a model built from scratch, a last reminder to look for a pretrained model
	 * in the BioImage Model Zoo (e.g. Cellpose-SAM for 2D instance segmentation): used as is or fine-tuned, it needs
	 * far less data and training. Asked once per workflow and dimensions, and not when a search found none.
	 *
	 * @return true if the user went to look for one (the model question is then shown and searching)
	 */
	private boolean suggestPretrainedModels() {
		// The UI tests accept every confirmation, which would take them to the search
		if (java.awt.GraphicsEnvironment.isHeadless()) return false;
		int source = model.questionFor("MODEL.SOURCE");
		int select = model.questionFor("MODEL.BMZ.SOURCE_MODEL_ID");
		if (source < 0 || select < 0 || !"biapy".equals(model.getAnswer("MODEL.SOURCE").getAsString())
				|| !"false".equals(model.getAnswer("MODEL.LOAD_CHECKPOINT").getAsString()))
			return false;
		String key = modelsKey();
		if (key.equals(pretrainedSuggestedKey)) return false;
		pretrainedSuggestedKey = key;
		int bmz = -1;
		com.google.gson.JsonArray sources = model.getQuestion(source).map.getAsJsonArray("MODEL.SOURCE");
		for (int i = 0; i < sources.size() && bmz < 0; i++) if ("bmz".equals(sources.get(i).getAsString())) bmz = i;
		if (bmz < 0) return false;

		String html = pretrainedSuggestionHtml(model.getAnswer("PROBLEM.TYPE").getAsString(),
				model.getAnswer("PROBLEM.NDIM").getAsString());
		if (!UiUtils.confirmHtml(this, "BiaPy Wizard - Pretrained models", html, "Look for pretrained models",
				"Continue from scratch"))
			return false;
		model.setChoice(source, bmz);
		rebuildToc();
		searchModelsOnShow = true;
		showQuestion(select);
		return true;
	}

	/** Message of {@link #suggestPretrainedModels()} for the workflow (PROBLEM.TYPE) and dimensions ("2D"/"3D"). */
	static String pretrainedSuggestionHtml(String workflow, String ndim) {
		StringBuilder html = new StringBuilder("<div style='width:440px'>");
		html.append("<h1>Is there a pretrained model you could start from?</h1>")
				.append("<p>You chose to build the model from scratch. The BioImage Model Zoo has pretrained models that ")
				.append("BiaPy can use as they are or fine-tune with your data, which usually needs far fewer annotated ")
				.append("images and much less training time than starting from scratch.</p>");
		if ("INSTANCE_SEG".equals(workflow) && "2D".equals(ndim))
			html.append("<p>For example, the Zoo has the <b>Cellpose-SAM</b> and <b>Cellpose-DINO</b> generalist models ")
					.append("to segment cells and nuclei, and fine-tuning a Cellpose model on one's own images is a common ")
					.append("way to get good results quickly.</p>");
		html.append("<p>Do you want to look for models that fit your workflow and dimensions now? It needs an internet ")
				.append("connection and may take a while. Your other answers are kept.</p></div>");
		return UiUtils.htmlPage(html.toString());
	}

	///////////////////////////////
	// Calls to the Python bridge
	///////////////////////////////
	/**
	 * Checks the data of the folder answered in question {@code q}. {@code dataPath}: path of the targets within the
	 * Zarr files of the folder (e.g. "labels.mito" for a label image of OME-Zarr images), empty for the files
	 * themselves.
	 */
	private void checkData(Question q, String folder, Runnable onDone, String dataPath) {
		folder = folder.trim();
		if (folder.isEmpty() || !new File(folder).isDirectory()) {
			UiUtils.error(this, "Please first select an existing folder to check.");
			return;
		}
		if (!model.isSet("PROBLEM.TYPE")) {
			UiUtils.info(this, "You need to answer the 'Workflow' question before checking the data.");
			return;
		}
		if (!model.isSet("PROBLEM.NDIM")) {
			UiUtils.info(this, "You need to answer the 'Dimensions' question before checking the data.");
			return;
		}
		model.setPath(q.index, folder);
		// The same folder already checked for another question (e.g. to train and to test): its check is reused
		Object[] reuse = dataPath.isEmpty() ? model.reusableCheck(q.index, folder) : null;
		if (reuse != null) {
			model.setDataChecked(q.index, (JsonObject) reuse[0], (JsonObject) reuse[1]);
			rebuildToc();
			onDone.run();
			UiUtils.info(this, "This folder was already checked as the " + folderRole((String) reuse[2])
					+ ", so its check is reused.");
			return;
		}
		JsonObject args = new JsonObject();
		args.addProperty("folder", folder);
		args.addProperty("key", q.firstKey());
		args.addProperty("workflow", model.getAnswer("PROBLEM.TYPE").getAsString());
		args.addProperty("ndim", model.getAnswer("PROBLEM.NDIM").getAsString());
		args.addProperty("data_path", dataPath);
		boolean isTarget = q.firstKey().contains("GT_PATH");
		boolean train = q.firstKey().contains("TRAIN");
		String title = (train ? "Training" : "Test") + (isTarget ? " targets" : " data");
		// Window with the progress of the check, which then shows the preview (null when headless)
		DataPreviewDialog dialog = DataPreviewDialog.open(this, title, folder);
		final boolean[] cancelled = { false }; // the user left the window before the check finished
		final boolean[] noPreview = { false }; // checked, but the preview could not be made
		String unit = isTarget && "DETECTION".equals(args.get("workflow").getAsString()) ? "CSV files" : "images";
		java.util.function.Consumer<String> steps = line -> {
			if (line.startsWith(STEP_PREFIX)) {
				String text = line.substring(STEP_PREFIX.length());
				// The data check window shows the progress; without it (headless), the bottom bar
				if (dialog != null) dialog.step(text);
				else busyLabel.setText(abbreviate(text));
			} else if (line.startsWith(PROGRESS_PREFIX)) {
				JsonObject p = com.google.gson.JsonParser.parseString(line.substring(PROGRESS_PREFIX.length())).getAsJsonObject();
				int done = p.get("done").getAsInt(), total = p.get("total").getAsInt();
				String name = p.get("name").isJsonNull() ? null : p.get("name").getAsString();
				if (dialog != null) dialog.progress(done, total, name, unit);
				else busyLabel.setText(done + "/" + total + " " + unit + " checked");
			}
		};
		java.util.function.Consumer<String> failed = msg -> {
			if (cancelled[0]) return;
			if (dialog != null) dialog.showError(msg);
			else UiUtils.error(this, msg);
			onDone.run();
		};
		final String checked = folder;
		// The check itself, reading this percentage of each Zarr/H5 image (in random blocks)
		java.util.function.IntPredicate check = percentage -> {
			args.addProperty("percentage", percentage);
			return runBridge(dialog != null ? "" : "Checking data in " + checked + " . . .", "check_data", args, steps, res -> {
				if (cancelled[0]) return;
				if (res.get("error").getAsBoolean()) {
					failed.accept("The following error was found when checking the folder:\n"
							+ res.get("error_message").getAsString());
					return;
				}
				model.setDataChecked(q.index, res.getAsJsonObject("data_constraints"), res.getAsJsonObject("sample_info"));
				rebuildToc();
				onDone.run();
				previewData(q, checked, title, dialog, steps, cancelled, noPreview);
			}, failed);
		};
		// Large Zarr/H5 images take long to read whole: their size is measured first and, if they are large, the user
		// chooses how much of them to check
		File[] chunked = new File(folder).listFiles((dir, name) -> name.matches("(?i).*\\.(zarr|n5|h5|hdf5|hdf)$"));
		boolean started;
		if (chunked != null && chunked.length > 0) {
			JsonObject sizeArgs = new JsonObject();
			sizeArgs.addProperty("folder", folder);
			started = runBridge(dialog != null ? "" : "Measuring the Zarr/H5 data in " + folder + " . . .", "data_size",
					sizeArgs, steps, size -> {
						if (cancelled[0]) return;
						int percentage = size.get("large").getAsBoolean() ? SampleSizeDialog.ask(this, checked, size) : 100;
						if (percentage > 0) check.test(percentage);
						else if (dialog != null) dialog.close();
					}, failed);
		} else {
			started = check.test(100);
		}
		if (!started || dialog == null) return;
		dialog.setOnCancel(() -> {
			cancelled[0] = true;
			PythonBridge b = runningBridge;
			if (b != null) b.cancel();
		});
		// "Choose another folder", or left before the end: the folder is not checked
		if (!dialog.waitForUser() && !noPreview[0] && model.isDataChecked(q)) {
			model.uncheckData(q.index);
			rebuildToc();
			onDone.run();
		}
	}

	/** "training raw images", "test targets"... for a data variable. */
	private static String folderRole(String key) {
		return (key.contains("TRAIN") ? "training " : "test ") + (key.contains("GT_PATH") ? "targets" : "raw images");
	}

	/** Prefix of the lines with the step the Python bridge is in (biapy_fiji_bridge.STEP_PREFIX). */
	private static final String STEP_PREFIX = "BIAPY_FIJI_STEP:";
	/** Prefix of the lines with the progress over the files (biapy_fiji_bridge.PROGRESS_PREFIX). */
	private static final String PROGRESS_PREFIX = "BIAPY_FIJI_PROGRESS:";

	/** Last preview of each phase ("TRAIN"/"TEST") and the folders it shows (keys "raw" and "target"). */
	private final java.util.Map<String, JsonObject> previews = new java.util.HashMap<>();

	/**
	 * A few of the images just checked next to their targets (or raw images, when the targets were checked
	 * first) and the problems found, shown in the check's window so the user confirms the data.
	 */
	private void previewData(Question q, String folder, String title, DataPreviewDialog dialog,
			java.util.function.Consumer<String> steps, boolean[] cancelled, boolean[] noPreview) {
		String key = q.firstKey();
		boolean isTarget = key.contains("GT_PATH");
		boolean train = key.contains("TRAIN");
		String raw = isTarget ? model.checkedFolder(train ? "DATA.TRAIN.PATH" : "DATA.TEST.PATH") : folder;
		String target = isTarget ? folder : model.checkedFolder(train ? "DATA.TRAIN.GT_PATH" : "DATA.TEST.GT_PATH");
		JsonObject args = new JsonObject();
		args.addProperty("workflow", model.getAnswer("PROBLEM.TYPE").getAsString());
		args.addProperty("ndim", model.getAnswer("PROBLEM.NDIM").getAsString());
		args.addProperty("raw", raw == null ? "" : raw);
		args.addProperty("target", target == null ? "" : target);
		args.addProperty("target_data_path", target == null ? "" : model.targetDataPath(train ? "TRAIN" : "TEST"));
		java.util.function.Consumer<String> failed = msg -> {
			if (cancelled[0]) return;
			// The data was checked: without a preview the user just closes the window
			noPreview[0] = true;
			if (dialog != null) dialog.showError("The data was checked, but its preview could not be made: " + msg);
		};
		runBridge(dialog != null ? "" : "Preparing a preview of the data . . .", "preview_data", args, steps, res -> {
			if (cancelled[0]) return;
			String intro;
			if (raw != null && target != null)
				intro = "Some of the raw images and their targets, paired as BiaPy will pair them (by file order). Check that "
						+ "each target belongs to its raw image and that both folders hold the right data.";
			else if (raw != null)
				intro = "Some of the images in <i>" + folder + "</i>. Check that they are the images you want to use"
						+ (isTarget ? "." : "; once the targets are checked they will be shown next to them.");
			else
				intro = "Some of the targets in <i>" + folder + "</i>. Check the raw images folder too: they will be shown "
						+ "next to each other once both are checked.";
			res.addProperty("raw", raw == null ? "" : raw);
			res.addProperty("target", target == null ? "" : target);
			previews.put(train ? "TRAIN" : "TEST", res);
			if (dialog != null) dialog.showPreview(intro, res);
			else DataPreviewDialog.report(title, intro, res);
		}, failed);
	}

	/** Models found by the last search and the workflow/dimensions they were searched for. */
	private final List<JsonObject> foundModels = new ArrayList<>();
	private String foundModelsKey = "";

	private String modelsKey() {
		return (model.isSet("PROBLEM.TYPE") ? model.getAnswer("PROBLEM.TYPE").getAsString() : "") + "|"
				+ (model.isSet("PROBLEM.NDIM") ? model.getAnswer("PROBLEM.NDIM").getAsString() : "");
	}

	private void findModels(Question q, JTextField field, JPanel cards) {
		if (!model.isSet("PROBLEM.TYPE") || !model.isSet("PROBLEM.NDIM")) {
			UiUtils.info(this, "Please answer the 'Dimensions' and 'Workflow' questions first.");
			return;
		}
		JsonObject args = new JsonObject();
		args.addProperty("workflow", model.getAnswer("PROBLEM.TYPE").getAsString());
		args.addProperty("ndim", model.getAnswer("PROBLEM.NDIM").getAsString());
		foundModels.clear();
		foundModelsKey = modelsKey();
		cards.removeAll();
		cards.revalidate();
		cards.repaint();
		final String[] progress = { "" };
		runBridge("Searching for compatible models . . .", "find_models", args, line -> {
			// Models are shown as soon as Python finds them
			if (line.startsWith("MODEL_FOUND:")) {
				JsonObject m = com.google.gson.JsonParser.parseString(line.substring(12)).getAsJsonObject();
				foundModels.add(m);
				cards.add(modelCard(m, q, field, cards));
				cards.revalidate();
				cards.repaint();
			} else if (line.startsWith("MODEL_PROGRESS:")) {
				String[] t = line.substring(15).trim().split(" ");
				progress[0] = t[0] + "/" + t[1] + " checked";
			} else {
				return;
			}
			busyLabel.setText(progress[0] + ", " + foundModels.size() + " compatible");
		}, res -> {
			if (foundModels.isEmpty()) noModelsFound();
		});
	}

	/**
	 * No compatible models: the wizard goes on with a model built from scratch, the first answer of the "Model
	 * source" question, which is shown so the user only has to continue.
	 */
	private void noModelsFound() {
		int source = model.questionFor("MODEL.SOURCE");
		if (source < 0) {
			UiUtils.info(this, "No compatible models were found.");
			return;
		}
		Question q = model.getQuestion(source);
		model.setChoice(source, 0);
		// There is nothing to suggest at the end of the questions
		pretrainedSuggestedKey = modelsKey();
		rebuildToc();
		showQuestion(source);
		UiUtils.info(this, "No compatible models were found in the BioImage Model Zoo for this workflow and dimensions.\n\n"
				+ "The wizard continues with a model built from scratch: '" + q.toc + "' is now set to\n'" + q.answers.get(0)
				+ "'. Click 'Continue' to go on.");
	}

	private static final java.util.Map<String, ImageIcon> COVERS = new java.util.concurrent.ConcurrentHashMap<>();
	private static final java.util.concurrent.ExecutorService COVER_LOADER = java.util.concurrent.Executors
			.newFixedThreadPool(4, r -> {
				Thread t = new Thread(r, "BiaPy-cover-loader");
				t.setDaemon(true);
				return t;
			});
	private static final int COVER_SIZE = 64;

	/** One model: cover image, name and description, and buttons to select it or open its BioImage.io page. */
	private JPanel modelCard(JsonObject m, Question q, JTextField field, JPanel cards) {
		String id = m.get("id").getAsString();
		String source = m.get("source").getAsString();
		boolean selected = (id + " (" + source + ")").equals(model.getAnswered(q.index));

		JPanel card = new JPanel(new BorderLayout(10, 0));
		card.setBackground(selected ? new Color(232, 243, 255) : BiaPyFiji.WHITE);
		card.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createMatteBorder(0, 0, 1, 0, BiaPyFiji.LIGHT_BLUE),
				BorderFactory.createEmptyBorder(6, 6, 6, 6)));

		JLabel cover = new JLabel(BiaPyFiji.icon("images/bmz_logo.png", COVER_SIZE));
		cover.setPreferredSize(new Dimension(COVER_SIZE, COVER_SIZE));
		String coverUrl = m.has("cover") ? m.get("cover").getAsString() : "";
		if (!coverUrl.isEmpty()) loadCover(coverUrl, cover);
		card.add(cover, BorderLayout.WEST);

		String desc = m.get("description").getAsString();
		if (desc.length() > 120) desc = desc.substring(0, 117) + "...";
		// "modest-cow 🐄": the emoji is an image (see EmojiIcons), most are missing in Java fonts
		JPanel titleRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
		titleRow.setOpaque(false);
		JLabel name = new JLabel(m.get("nickname").getAsString());
		name.setFont(name.getFont().deriveFont(Font.BOLD, 12f));
		titleRow.add(name);
		JLabel emoji = new JLabel();
		emoji.setBorder(BorderFactory.createEmptyBorder(0, 5, 0, 0));
		EmojiIcons.load(m.has("nickname_icon") ? m.get("nickname_icon").getAsString() : "", 16, emoji);
		titleRow.add(emoji);
		if (selected) {
			JLabel sel = new JLabel("  (selected)");
			sel.setFont(sel.getFont().deriveFont(Font.PLAIN, 12f));
			sel.setForeground(BiaPyFiji.GREEN);
			titleRow.add(sel);
		}
		JLabel text = new JLabel("<html><span style='color:#555555'>" + (m.has("name") ? m.get("name").getAsString() : "")
				+ "</span><br>" + desc + "</html>");
		text.setFont(text.getFont().deriveFont(Font.PLAIN, 12f));
		text.setVerticalAlignment(JLabel.TOP);
		// Let the text wrap to the available width
		text.setPreferredSize(new Dimension(10, 58));
		JPanel middle = new JPanel(new BorderLayout(0, 2));
		middle.setOpaque(false);
		middle.add(titleRow, BorderLayout.NORTH);
		middle.add(text, BorderLayout.CENTER);
		card.add(middle, BorderLayout.CENTER);

		JPanel buttons = new JPanel(new java.awt.GridLayout(2, 1, 0, 4));
		buttons.setOpaque(false);
		JButton select = UiUtils.primaryButton("Select", "Use this model");
		select.addActionListener(e -> {
			if (!UiUtils.confirm(this, "Do you want to select the '" + id + "' model\nfrom " + source + "?")) return;
			model.selectExternalModel(q.index, id, source,
					m.has("imposed_vars") && m.get("imposed_vars").isJsonObject() ? m.getAsJsonObject("imposed_vars") : null);
			field.setText(model.getAnswered(q.index).toString());
			rebuildToc();
			// Redraw to mark the selected card
			cards.removeAll();
			for (JsonObject o : foundModels) cards.add(modelCard(o, q, field, cards));
			cards.revalidate();
			cards.repaint();
		});
		JButton web = UiUtils.secondaryButton("BioImage.io \u2197", "Open the page of the model in the BioImage Model Zoo ("
				+ m.get("url").getAsString() + ")");
		web.addActionListener(e -> UiUtils.openUrl(m.get("url").getAsString()));
		buttons.add(select);
		buttons.add(web);
		JPanel east = new JPanel(new BorderLayout());
		east.setOpaque(false);
		east.add(buttons, BorderLayout.NORTH);
		card.add(east, BorderLayout.EAST);
		card.setMaximumSize(new Dimension(Integer.MAX_VALUE, card.getPreferredSize().height));
		card.setAlignmentX(Component.LEFT_ALIGNMENT);
		return card;
	}

	/** Downloads the cover image in the background (emoji icons are not available in Java fonts). */
	private static void loadCover(String url, JLabel target) {
		ImageIcon cached = COVERS.get(url);
		if (cached != null) {
			target.setIcon(cached);
			return;
		}
		COVER_LOADER.submit(() -> {
			try {
				java.net.URLConnection conn = new java.net.URL(url).openConnection();
				conn.setConnectTimeout(10000);
				conn.setReadTimeout(20000);
				java.awt.image.BufferedImage img;
				try (java.io.InputStream in = conn.getInputStream()) {
					img = javax.imageio.ImageIO.read(in);
				}
				if (img == null) return;
				double scale = Math.min((double) COVER_SIZE / img.getWidth(), (double) COVER_SIZE / img.getHeight());
				ImageIcon icon = new ImageIcon(img.getScaledInstance(Math.max(1, (int) (img.getWidth() * scale)),
						Math.max(1, (int) (img.getHeight() * scale)), java.awt.Image.SCALE_SMOOTH));
				COVERS.put(url, icon);
				SwingUtilities.invokeLater(() -> target.setIcon(icon));
			} catch (Exception e) {
				// Keep the BioImage Model Zoo logo
			}
		});
	}

	private interface ResultHandler {
		void handle(JsonObject result);
	}

	/** Runs a bridge command in the background showing a busy indicator; the handler runs on the EDT. */
	private void runBridge(String message, String command, JsonObject args, ResultHandler handler) {
		runBridge(message, command, args, line -> busyLabel.setText(abbreviate(line)), handler, null);
	}

	/** Same, with a custom handler (run on the EDT) for each line Python prints. */
	private void runBridge(String message, String command, JsonObject args, java.util.function.Consumer<String> lineHandler,
			ResultHandler handler) {
		runBridge(message, command, args, lineHandler, handler, null);
	}

	/**
	 * Same, with a custom handler (run on the EDT) for each line Python prints and for errors (null: an error
	 * dialog).
	 *
	 * @return whether the command was started
	 */
	private boolean runBridge(String message, String command, JsonObject args, java.util.function.Consumer<String> lineHandler,
			ResultHandler handler, java.util.function.Consumer<String> errorHandler) {
		if (runningBridge != null) {
			UiUtils.info(this, "Please wait until the current process finishes.");
			return false;
		}
		if (!checkEnvironmentReady()) return false;
		setBusy(message);
		PythonBridge bridge = new PythonBridge();
		runningBridge = bridge;
		new Thread(() -> {
			JsonObject res = null;
			String error = null;
			try {
				res = bridge.call(command, args, line -> SwingUtilities.invokeLater(() -> lineHandler.accept(line)));
			} catch (Exception ex) {
				error = ex.getMessage();
			}
			final JsonObject fr = res;
			final String fe = error;
			SwingUtilities.invokeLater(() -> {
				runningBridge = null;
				setBusy(null);
				if (fe == null) handler.handle(fr);
				else if (errorHandler != null) errorHandler.accept(fe);
				else UiUtils.error(this, fe);
			});
		}, "BiaPy-wizard-" + command).start();
		return true;
	}

	/**
	 * The data checks, model search and configuration export run in BiaPy's Python environment, so it must be
	 * installed first. Otherwise the user would only see a confusing Python error.
	 */
	private boolean checkEnvironmentReady() {
		switch (main.getEnvState()) {
		case "ok":
			String problem = BiaPyFiji.wizardProblem(main.getEnvInfo());
			if (problem == null) return true;
			int update = UiUtils.choose(this, "This step can't run with BiaPy " + main.biapyVersion() + ": " + problem
					+ ".\nThe BiaPy version is selected in the HOME tab.\n\nYour answers in the Wizard are kept.",
					"Go to HOME", "Cancel");
			if (update == 0) main.showPage(MainPanel.HOME);
			return false;
		case "checking":
			UiUtils.info(this, "BiaPy's Python environment is still being checked. Please try again in a few seconds.");
			return false;
		default:
			int choice = UiUtils.choose(this, "This step needs BiaPy installed, and its Python environment is not ready yet.\n"
					+ "Install it (or select an existing Python with BiaPy) in the HOME tab.\n\n"
					+ "Your answers in the Wizard are kept.", "Go to HOME", "Cancel");
			if (choice == 0) main.showPage(MainPanel.HOME);
			return false;
		}
	}

	/** @param message null when idle; empty to only disable the buttons (the progress is shown elsewhere) */
	private void setBusy(String message) {
		boolean busy = message != null;
		busyBar.setVisible(busy && !message.isEmpty());
		busyLabel.setText(busy && !message.isEmpty() ? message : " ");
		backBtn.setEnabled(!busy);
		nextBtn.setEnabled(!busy);
		tocLocked = busy; // not setEnabled(false): the disabled tree look truncates the names
	}

	private static String abbreviate(String s) {
		return s.length() > 110 ? s.substring(0, 107) + "..." : s;
	}

	//////////////////
	// Summary page
	//////////////////
	private JPanel buildSummaryPage() {
		JPanel p = new JPanel(new BorderLayout(0, 10));
		p.setBackground(BiaPyFiji.WHITE);
		p.setBorder(BorderFactory.createEmptyBorder(16, 24, 16, 24));
		p.add(UiUtils.title("Summary of your answers", 20f), BorderLayout.NORTH);
		summaryList.setLayout(new BoxLayout(summaryList, BoxLayout.Y_AXIS));
		summaryList.setBackground(BiaPyFiji.WHITE);
		JScrollPane sp = new JScrollPane(summaryList);
		sp.getVerticalScrollBar().setUnitIncrement(16);
		p.add(sp, BorderLayout.CENTER);

		JPanel bottom = new JPanel(new BorderLayout());
		bottom.setOpaque(false);
		summaryFile.setPreferredSize(new Dimension(100, 40));
		bottom.add(summaryFile, BorderLayout.CENTER);
		JButton back = UiUtils.primaryButton("< Back", "Back to the questions to change an answer");
		back.addActionListener(e -> {
			pages.show(pagePanel, QUESTIONS);
			showQuestion(model.getCurrent());
		});
		JButton create = UiUtils.primaryButton("Create configuration file",
				"Save the configuration (.yaml) for these answers; it can then be run in the Run tab");
		create.addActionListener(e -> createConfig());
		summaryFile.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 12));
		bottom.add(back, BorderLayout.WEST);
		bottom.add(create, BorderLayout.EAST);
		p.add(bottom, BorderLayout.SOUTH);
		return p;
	}

	private static final int SUMMARY_THUMB = 140;

	/** A component centered in a row as wide as the summary. */
	private static JPanel centeredRow(Component c) {
		JPanel row = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
		row.setOpaque(false);
		row.add(c);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
		return row;
	}

	/**
	 * Columns with a few images of each folder checked (training/test raw images and targets), from the
	 * previews shown when they were checked, if they still show the folders answered.
	 */
	private JPanel summaryData() {
		JPanel cols = new JPanel(new FlowLayout(FlowLayout.CENTER, 18, 0));
		cols.setOpaque(false);
		for (String phase : new String[] { "TRAIN", "TEST" }) {
			JsonObject pv = previews.get(phase);
			if (pv == null) continue;
			String raw = model.checkedFolder("DATA." + phase + ".PATH");
			String target = model.checkedFolder("DATA." + phase + ".GT_PATH");
			String name = phase.equals("TRAIN") ? "Training" : "Test";
			if (raw != null && raw.equals(pv.get("raw").getAsString()))
				cols.add(summaryColumn(name + " raw images", pv, "raw"));
			if (target != null && target.equals(pv.get("target").getAsString()))
				cols.add(summaryColumn(name + " targets", pv, "target"));
		}
		return cols;
	}

	private static JPanel summaryColumn(String title, JsonObject preview, String prefix) {
		JPanel col = new JPanel();
		col.setOpaque(false);
		col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
		JLabel h = new JLabel(title);
		h.setFont(h.getFont().deriveFont(Font.BOLD, 13f));
		h.setAlignmentX(Component.CENTER_ALIGNMENT);
		col.add(h);
		int n = 0;
		for (JsonElement e : preview.getAsJsonArray("samples")) {
			if (n == 2) break;
			JsonObject smp = e.getAsJsonObject();
			JLabel img = new JLabel();
			if (smp.has(prefix + "_png")) {
				ImageIcon ic = DataPreviewDialog.thumbnail(smp.get(prefix + "_png").getAsString(), SUMMARY_THUMB);
				if (ic == null) continue;
				img.setIcon(ic);
				img.setBorder(BorderFactory.createLineBorder(BiaPyFiji.LIGHT_BLUE));
			} else if (smp.has(prefix + "_text")) {
				img.setText(smp.get(prefix + "_text").getAsString());
				img.setFont(img.getFont().deriveFont(Font.BOLD, 13f));
			} else {
				continue;
			}
			img.setAlignmentX(Component.CENTER_ALIGNMENT);
			col.add(Box.createVerticalStrut(6));
			col.add(img);
			n++;
		}
		return col;
	}

	/** The instance representation chosen, as its card in the question (without the radio button). */
	private JPanel summaryRepresentation(Question q, int answer) {
		WizardModel.Option op = null;
		for (WizardModel.Option o : model.options(q)) if (o.index == answer) op = o;
		JPanel card = new JPanel(new BorderLayout(0, 6));
		card.setBackground(new Color(232, 243, 255));
		card.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(BiaPyFiji.LIGHT_BLUE),
				BorderFactory.createEmptyBorder(8, 10, 8, 10)));
		JLabel name = new JLabel(q.answers.get(answer), SwingConstants.CENTER);
		name.setFont(name.getFont().deriveFont(Font.BOLD, 14f));
		card.add(name, BorderLayout.NORTH);
		String image = answer < q.answerImages.size() ? q.answerImages.get(answer) : null;
		JLabel pic = new JLabel(image == null ? new ImageIcon() : BiaPyFiji.icon("wizard/" + image, 100), SwingConstants.CENTER);
		String desc = answer < q.answerDescriptions.size() ? q.answerDescriptions.get(answer) : "";
		String reason = op == null || op.reason.isEmpty() ? ""
				: "<br><font color='#4090fd'><b>Reason:</b> <i>" + op.reason + "</i></font>";
		int width = Math.max(pic.getPreferredSize().width, 420);
		JPanel body = new JPanel(new BorderLayout(0, 6));
		body.setOpaque(false);
		body.add(pic, BorderLayout.NORTH);
		body.add(wrappedLabel(desc + reason, width, new JLabel().getFont().deriveFont(Font.PLAIN, 12f)), BorderLayout.CENTER);
		card.add(body, BorderLayout.CENTER);
		return card;
	}

	private void showSummary() {
		summaryList.removeAll();
		// Workflow, the data checked and the instance representation, centered on top
		if (model.isSet("PROBLEM.TYPE") && model.isSet("PROBLEM.NDIM")) {
			JLabel wf = new JLabel(WorkflowConfirmDialog.workflowName(model.getAnswer("PROBLEM.TYPE").getAsString()) + " - "
					+ model.getAnswer("PROBLEM.NDIM").getAsString());
			wf.setFont(wf.getFont().deriveFont(Font.BOLD, 18f));
			wf.setForeground(BiaPyFiji.BLUE);
			summaryList.add(centeredRow(wf));
			summaryList.add(Box.createVerticalStrut(12));
		}
		JPanel data = summaryData();
		if (data.getComponentCount() > 0) {
			summaryList.add(centeredRow(data));
			summaryList.add(Box.createVerticalStrut(14));
		}
		for (Question q : model.getQuestions())
			if (q.type == Type.REPRESENTATION && model.isVisible(q.index) && model.getAnswered(q.index) instanceof Integer) {
				JLabel h = new JLabel(q.toc);
				h.setFont(h.getFont().deriveFont(Font.BOLD, 13f));
				summaryList.add(centeredRow(h));
				summaryList.add(Box.createVerticalStrut(4));
				summaryList.add(centeredRow(summaryRepresentation(q, (Integer) model.getAnswered(q.index))));
				summaryList.add(Box.createVerticalStrut(14));
			}
		JPanel resources = summaryResources();
		if (resources != null) {
			summaryList.add(resources);
			summaryList.add(Box.createVerticalStrut(10));
		}
		JLabel answersTitle = new JLabel("Your answers");
		answersTitle.setFont(answersTitle.getFont().deriveFont(Font.BOLD, 13f));
		answersTitle.setBorder(BorderFactory.createEmptyBorder(4, 4, 2, 4));
		answersTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
		summaryList.add(answersTitle);
		for (Question q : model.getQuestions()) {
			if (!model.isVisible(q.index)) continue;
			Object a = model.getAnswered(q.index);
			String answer;
			if (a == null) answer = "<span style='color:#ff3300'>Not answered</span>";
			else answer = "<span style='color:#4090fd'>" + (a instanceof Integer ? q.answers.get((Integer) a) : a.toString())
					+ "</span>";
			if (a != null && q.type == Type.PATH && !model.isDataChecked(q))
				answer += " &nbsp;<span style='color:#ff3300'>(data not checked)</span>";
			JLabel l = new JLabel("<html><div style='width:520px'><b>" + q.text + "</b><br>" + answer + "</div></html>");
			l.setBorder(BorderFactory.createEmptyBorder(6, 4, 6, 4));
			l.setAlignmentX(Component.LEFT_ALIGNMENT);
			summaryList.add(l);
		}
		summaryFile.setText("<html>Configuration file:<br><b>" + yamlFile().getAbsolutePath() + "</b></html>");
		summaryList.revalidate();
		summaryList.repaint();
		pages.show(pagePanel, SUMMARY);
	}

	/** What the job needs (BiaPy's estimate of the RAM, GPU memory and disk), shown in the summary; null if unknown. */
	private List<String> resourcesLines;
	/** How long the estimate may take before the Wizard says it is working on it. */
	private static final int ESTIMATE_POPUP_DELAY_MS = 300;

	/**
	 * Shows the summary once BiaPy has estimated the resources the job needs (planning the configuration without
	 * writing it). That is usually immediate; when it takes longer (e.g. BiaPy is still being imported after Fiji
	 * started) a window says the Wizard is working on it.
	 */
	private void prepareSummary() {
		resourcesLines = null;
		if (model.checkReadyToExport() != null) { // answers missing: nothing to estimate yet
			showSummary();
			return;
		}
		JsonObject args = new JsonObject();
		args.add("answers", model.toJson());
		nextBtn.setEnabled(false);
		JDialog[] working = { null };
		javax.swing.Timer popup = new javax.swing.Timer(ESTIMATE_POPUP_DELAY_MS, e -> {
			if (java.awt.GraphicsEnvironment.isHeadless()) return;
			working[0] = workingDialog();
			working[0].setVisible(true);
		});
		popup.setRepeats(false);
		popup.start();
		new Thread(() -> {
			List<String> lines = null;
			try {
				JsonObject res = new io.github.biapyx.fiji.env.PythonBridge().call("estimate_resources", args, null);
				JsonElement resources = res.get("resources");
				if (resources != null && resources.isJsonObject()) {
					lines = new ArrayList<>();
					for (JsonElement l : resources.getAsJsonObject().getAsJsonArray("lines")) lines.add(l.getAsString());
				}
			} catch (Exception e) {
				// Not estimated (e.g. a BiaPy version that does not): the summary has no such section
			}
			List<String> result = lines;
			SwingUtilities.invokeLater(() -> {
				popup.stop();
				if (working[0] != null) working[0].dispose();
				nextBtn.setEnabled(true);
				resourcesLines = result;
				showSummary();
			});
		}, "BiaPy-resources").start();
	}

	/** "The Wizard is brewing your configuration", while BiaPy estimates the resources (it closes by itself). */
	private JDialog workingDialog() {
		JDialog d = new JDialog(SwingUtilities.getWindowAncestor(this), "BiaPy Wizard", java.awt.Dialog.ModalityType.MODELESS);
		d.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
		d.setContentPane(workingPanel());
		d.pack();
		d.setLocationRelativeTo(this);
		return d;
	}

	/** The contents of {@link #workingDialog()} (package visible for tests). */
	static JPanel workingPanel() {
		JPanel p = new JPanel(new BorderLayout(16, 0));
		p.setBackground(BiaPyFiji.WHITE);
		p.setBorder(BorderFactory.createEmptyBorder(16, 18, 16, 22));
		p.add(new JLabel(BiaPyFiji.icon("wizard/wizard.png", 96)), BorderLayout.WEST);
		JPanel text = new JPanel();
		text.setOpaque(false);
		text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
		JLabel title = new JLabel("The Wizard is brewing your configuration . . .");
		title.setFont(title.getFont().deriveFont(Font.BOLD, 15f));
		JLabel sub = new JLabel("Estimating the memory and disk space your job will need");
		sub.setFont(sub.getFont().deriveFont(Font.PLAIN, 12f));
		sub.setForeground(BiaPyFiji.GREY);
		javax.swing.JProgressBar bar = new javax.swing.JProgressBar();
		bar.setIndeterminate(true);
		for (javax.swing.JComponent c : new javax.swing.JComponent[] { title, sub, bar }) c.setAlignmentX(Component.LEFT_ALIGNMENT);
		text.add(Box.createVerticalGlue());
		text.add(title);
		text.add(Box.createVerticalStrut(4));
		text.add(sub);
		text.add(Box.createVerticalStrut(12));
		text.add(bar);
		text.add(Box.createVerticalGlue());
		p.add(text, BorderLayout.CENTER);
		return p;
	}

	/** "Estimated resources" for the summary, from {@link #resourcesLines}; null when they are unknown. */
	private JPanel summaryResources() {
		if (resourcesLines == null) return null;
		JPanel box = new JPanel(new BorderLayout(0, 2));
		box.setOpaque(false);
		box.setBorder(BorderFactory.createEmptyBorder(4, 4, 2, 4));
		box.setAlignmentX(Component.LEFT_ALIGNMENT);
		JLabel title = new JLabel("Estimated resources");
		title.setFont(title.getFont().deriveFont(Font.BOLD, 13f));
		StringBuilder sb = new StringBuilder("<html><div style='width:520px'>");
		for (String l : resourcesLines) {
			int colon = l.indexOf(':');
			sb.append(colon > 0 ? "<b>" + l.substring(0, colon) + "</b>" + l.substring(colon) : l).append("<br>");
		}
		sb.append("<span style='color:#777777'>Approximate, from measurements of BiaPy's runs.</span></div></html>");
		JLabel body = new JLabel(sb.toString());
		body.setFont(body.getFont().deriveFont(Font.PLAIN, 12f));
		body.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
		box.add(title, BorderLayout.NORTH);
		box.add(body, BorderLayout.CENTER);
		return box;
	}

	private void createConfig() {
		String problem = model.checkReadyToExport();
		if (problem != null) {
			UiUtils.info(this, problem);
			return;
		}
		File yaml = yamlFile();
		if (yaml.exists() && !UiUtils.confirm(this, "The file '" + yaml + "' already exists. Do you want to overwrite it?"))
			return;
		if (!UiUtils.confirm(this, "Do you want to finish the wizard and create the configuration based on your answers?"))
			return;
		JsonObject args = new JsonObject();
		args.add("answers", model.toJson());
		args.addProperty("yaml_file", yaml.getAbsolutePath());
		pages.show(pagePanel, QUESTIONS);
		runBridge("Creating configuration file . . .", "build_config", args, res -> {
			if (res.get("error").getAsBoolean()) {
				showSummary();
				UiUtils.error(this, res.get("error_message").getAsString());
				return;
			}
			UiUtils.info(this, "Configuration file created:\n" + yaml.getAbsolutePath());
			main.getRunPanel().setConfigFile(yaml, true);
			main.showPage(MainPanel.RUN);
			model.clear();
			previews.clear();
			confirmedWorkflow = null;
			pretrainedSuggestedKey = null;
			pages.show(pagePanel, START);
		});
	}

	////////////
	// TOC
	////////////
	private void rebuildToc() {
		updatingToc = true;
		DefaultMutableTreeNode root = new DefaultMutableTreeNode("Wizard");
		List<DefaultMutableTreeNode> secNodes = new ArrayList<>();
		for (String s : model.getSections()) {
			DefaultMutableTreeNode n = new DefaultMutableTreeNode(s);
			secNodes.add(n);
			root.add(n);
		}
		for (Question q : model.getQuestions())
			if (model.isVisible(q.index)) secNodes.get(q.section).add(new DefaultMutableTreeNode(q));
		toc.setModel(new DefaultTreeModel(root));
		for (int r = 0; r < toc.getRowCount(); r++) toc.expandRow(r);
		updatingToc = false;
		selectInToc(model.getCurrent());
	}

	private void selectInToc(int qi) {
		updatingToc = true;
		DefaultMutableTreeNode root = (DefaultMutableTreeNode) toc.getModel().getRoot();
		java.util.Enumeration<?> en = root.depthFirstEnumeration();
		while (en.hasMoreElements()) {
			DefaultMutableTreeNode n = (DefaultMutableTreeNode) en.nextElement();
			if (n.getUserObject() instanceof Question && ((Question) n.getUserObject()).index == qi) {
				toc.setSelectionPath(new TreePath(n.getPath()));
				break;
			}
		}
		toc.repaint();
		updatingToc = false;
	}

	/** Answered questions in blue, as in BiaPy-GUI. */
	private class TocRenderer extends DefaultTreeCellRenderer {
		private static final long serialVersionUID = 1L;

		@Override
		public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel, boolean expanded,
				boolean leaf, int row, boolean hasFocus) {
			super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);
			setIcon(null);
			Object uo = ((DefaultMutableTreeNode) value).getUserObject();
			if (uo instanceof Question) {
				Question q = (Question) uo;
				setFont(tree.getFont());
				setText(q.toc);
				setToolTipText(q.toc);
				if (!sel) setForeground(model.isAnswered(q.index) ? BiaPyFiji.BLUE : Color.BLACK);
			} else {
				setFont(tree.getFont().deriveFont(Font.BOLD));
				setToolTipText(null);
			}
			return this;
		}
	}

	///////////
	// Utils
	///////////
	// "<p align='center'><img ... src='{img:file}' .../></p>" in the help texts (see export_wizard_questions.py)
	private static final Pattern IMG = Pattern.compile("(?:<p[^>]*>\\s*)?<img[^>]*src='\\{img:([\\w.-]+)\\}'[^>]*>(?:\\s*</p>)?(?:\\s*<br>)?");

	/**
	 * Replaces the {img:file} placeholders of the help texts by the images of the jar, with the same captions
	 * as in the workflow confirmation, and lists the references they cite.
	 */
	private static String resolveImages(String html) {
		Matcher m = IMG.matcher(html);
		StringBuffer sb = new StringBuffer();
		while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(WorkflowConfirmDialog.figure(m.group(1), 380)));
		m.appendTail(sb);
		return WorkflowConfirmDialog.withReferences(sb.toString());
	}


	private static class SimpleDocListener implements javax.swing.event.DocumentListener {
		private final Runnable r;

		SimpleDocListener(Runnable r) {
			this.r = r;
		}

		@Override
		public void insertUpdate(javax.swing.event.DocumentEvent e) {
			r.run();
		}

		@Override
		public void removeUpdate(javax.swing.event.DocumentEvent e) {
			r.run();
		}

		@Override
		public void changedUpdate(javax.swing.event.DocumentEvent e) {
			r.run();
		}
	}
}
