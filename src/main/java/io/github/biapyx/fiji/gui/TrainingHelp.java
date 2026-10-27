package io.github.biapyx.fiji.gui;

import java.awt.Component;

/**
 * The explanations of the training tabs, each in a window: how to read the training curves (loss and metrics) and
 * what the training predictions are and how to configure them.
 */
final class TrainingHelp {

	private TrainingHelp() {}

	/**
	 * How to read the training curves: what the training and validation values are, what a good training looks like,
	 * what to change in BiaPy when it is not, and links to learn more.
	 *
	 * @param noise2void whether the job is a Noise2Void denoising, whose loss reads differently
	 */
	static void curves(Component parent, boolean noise2void) {
		UiUtils.helpWindow(parent, "How to read the training curves", curvesHtml(noise2void));
	}

	/** The body of {@link #curves} (package visible for tests). */
	static String curvesHtml(boolean noise2void) {
		return "<h2>What the curves show</h2>"
				+ "<p>After every epoch (a pass over all the training images) BiaPy measures the <b>loss</b>: the error "
				+ "that training tries to reduce, i.e. how far the model's predictions are from what they should be. The "
				+ "lower, the better. It is measured twice:</p>"
				+ "<ul>"
				+ "<li><b>Training loss</b>: on the training images, the ones the model learns from.</li>"
				+ "<li><b>Validation loss</b>: on the validation images, which the model never learns from. It tells how "
				+ "well the model works on images it has not seen, which is what matters in the end.</li>"
				+ "</ul>"
				+ "<p>The other charts are <b>metrics</b> (e.g. IoU, PSNR), easier to interpret than the loss. For some of "
				+ "them higher is better and for others lower: the (i) of each chart explains it.</p>"
				+ (noise2void
						? "<p>This job denoises with <b>Noise2Void</b>, which learns without clean images: its loss compares "
								+ "the predictions with noisy pixels, so it never gets close to zero and can look flat from "
								+ "early on. Judge the model by the training predictions instead.</p>"
						: "")

				+ "<h2>What a good training looks like</h2>"
				+ "<p>Both losses drop quickly in the first epochs and then more and more slowly, until they flatten out: "
				+ "the model has <b>converged</b> and more training will not improve it. The validation loss usually stays "
				+ "a bit above the training loss. The curves may look flat earlier than they are, as the vertical axis has "
				+ "to fit the high values of the first epochs: look at the last values too.</p>"

				+ "<h2>What to do when it does not</h2>"
				+ "<ul>"
				+ "<li><b>Both losses are still going down at the end</b>: the model can still improve. Train for more "
				+ "epochs (<i>TRAIN.EPOCHS</i>).</li>"
				+ "<li><b>The training loss keeps going down but the validation loss stops improving, or goes up</b>: the "
				+ "model is <b>overfitting</b>. It is memorizing the training images instead of learning what "
				+ "generalizes to new ones. More (or more varied) training images help the most, and so does data "
				+ "augmentation (<i>AUGMENTOR</i>). BiaPy keeps the model of the epoch with the lowest validation loss and "
				+ "stops after <i>TRAIN.PATIENCE</i> epochs without improvement, so the overfitted epochs are not used.</li>"
				+ "<li><b>Both losses stay high and flat</b>: the model is <b>underfitting</b>. It cannot learn the task as "
				+ "it is: try a larger model or another architecture, another learning rate (<i>TRAIN.LR</i>), and check "
				+ "that the labels are correct and consistent.</li>"
				+ "<li><b>The losses jump up and down, go up from the start or become NaN</b>: the learning rate is "
				+ "probably too high (<i>TRAIN.LR</i>). A very jumpy validation loss can also mean that there are too few "
				+ "validation images.</li>"
				+ "</ul>"

				+ "<h2>Learn more</h2>"
				+ "<ul>"
				+ "<li><a href='https://developers.google.com/machine-learning/crash-course/overfitting/interpreting-loss-curves'>"
				+ "Interpreting loss curves</a>, Google's Machine Learning Crash Course: examples of curves and what each "
				+ "of them means.</li>"
				+ "<li><a href='https://cs231n.github.io/neural-networks-3/'>Babysitting the learning process</a>, "
				+ "Stanford's CS231n notes: the loss, the learning rate and the gap between training and validation.</li>"
				+ "<li><a href='https://pmc.ncbi.nlm.nih.gov/articles/PMC6381354/'>Machine learning: applications of "
				+ "artificial intelligence to imaging and diagnosis</a>, Nichols, Chan and Baker, Biophysical Reviews "
				+ "(2019): a review of machine learning for imaging, with training, validation and overfitting.</li>"
				+ "</ul>";
	}

	/**
	 * What the training predictions are, which samples BiaPy follows, how to enable them and choose how often and
	 * how many, and what to look for.
	 *
	 * @param shown how many of the samples the tab shows at most
	 * @param noise2void whether the job is a Noise2Void denoising (no ground truth to compare with)
	 */
	static void predictions(Component parent, int shown, boolean noise2void) {
		UiUtils.helpWindow(parent, "About the training predictions", predictionsHtml(shown, noise2void));
	}

	/** The body of {@link #predictions} (package visible for tests). */
	static String predictionsHtml(int shown, boolean noise2void) {
		return "<h2>What they show</h2>"
				+ "<p>While the model trains, BiaPy predicts a few <b>samples</b> (patches of the images) every few "
				+ "epochs. They are always the same samples, so comparing the predictions over the epochs shows what the "
				+ "model is learning. Each sample is shown with its input, "
				+ (noise2void ? "its ground truth if the configuration loads it (with N2V_STRUCTMASK, the input as the "
						+ "model sees it while it trains instead), " : "its ground truth, ")
				+ "and the latest predictions, newest first.</p>"
				+ "<p>The samples come from the validation data (the training data if there is none). BiaPy picks the "
				+ "most informative ones it finds, e.g. those with the most labeled objects instead of just background, "
				+ "or one of each class in classification.</p>"

				+ "<h2>What to look for</h2>"
				+ "<p>The first predictions are usually blurry or noisy. They should become sharper and "
				+ (noise2void ? "less noisy, while keeping the structures of the image, " : "closer to the ground truth ")
				+ "as the training goes on. If they look the same for many epochs, the model has stopped improving: the "
				+ "training curves tell whether more training would help.</p>"

				+ "<h2>How to enable and configure them</h2>"
				+ "<p>Two variables of the configuration file (the YAML) control them:</p>"
				+ "<ul>"
				+ "<li><b>TRAIN.SAVE_TRAIN_PREDS_FREQ</b>: every how many epochs the samples are predicted. 1 predicts "
				+ "them after every epoch and -1 disables them. Predicting them takes a little time, so with very short "
				+ "epochs a larger value may speed up the training.</li>"
				+ "<li><b>TRAIN.SAVE_TRAIN_PREDS_NUM_SAMPLES</b>: how many samples are followed. This tab shows up to "
				+ shown + " of them; all of them are saved.</li>"
				+ "</ul>"
				+ "<p>The Wizard's configurations follow 2 samples. They are predicted after every epoch, except in "
				+ "complete trainings of segmentation and detection, which are long (about 1000 epochs): there every few "
				+ "epochs, about 100 times in the whole training. To change it, edit those variables in the YAML file with "
				+ "any text editor before running the job.</p>"
				+ "<p>The predictions are also saved as images in the <i>train_pred_samples</i> folder of the job's "
				+ "results, to look at them later.</p>";
	}
}
