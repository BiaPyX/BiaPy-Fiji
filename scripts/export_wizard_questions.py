"""
Regenerates src/main/resources/biapy/wizard/questions.json from BiaPy-GUI's settings.py,
so the Fiji wizard asks exactly the same questions as the GUI wizard.

Usage (from an env with BiaPy and BiaPy-GUI requirements installed):
    QT_QPA_PLATFORM=offscreen python scripts/export_wizard_questions.py /path/to/BiaPy-GUI
"""
import json
import os
import re
import sys

# Imported before BiaPy-GUI's folder is in the path: its "biapy" folder would shadow BiaPy
from biapy.wizard.instance_seg import REPRESENTATIONS

gui_dir = os.path.abspath(sys.argv[1])
os.chdir(gui_dir)
sys.path.insert(0, gui_dir)

from PySide6.QtWidgets import QApplication  # noqa: E402

app = QApplication([])

# settings.py only needs ui_utils.resource_path; importing the real ui_utils drags in the whole GUI
import types  # noqa: E402

ui_utils_stub = types.ModuleType("ui_utils")
ui_utils_stub.resource_path = lambda rel: os.path.join(gui_dir, rel)
sys.modules["ui_utils"] = ui_utils_stub
from settings import Settings  # noqa: E402

s = Settings().settings
n = s["wizard_number_of_questions"]


# Texts that differ in the Fiji plugin: only the BioImage Model Zoo is offered (no Torchvision) and the
# list of models appears inside the wizard page instead of a new window
FIJI_TEXT_CHANGES = [
    (r"\s*and <a href='https://pytorch.org/vision/stable/models.html'>Torchvision</a>", ""),
    (r"a new window will appear, displaying", "a list will appear below the question, displaying"),
    (r"Nosei2Void", "Noise2Void"),
    (r"segmenation", "segmentation"),
    (r"\(rigth\)", "(right)"),
    (
        r"since frequently clean images are simply unavailable\.",
        "since frequently clean images are simply unavailable. If you do have clean versions of your noisy images, "
        "BiaPy can also use them as targets (supervised training): the Wizard will ask about it.",
    ),
]


# Supervised denoising (BiaPy's PROBLEM.DENOISING.LOAD_GT_DATA): the Fiji wizard asks whether clean target
# images are available, and then asks for them like for the other workflows with targets.
DENOISING_GT = "PROBLEM.DENOISING.LOAD_GT_DATA"
DENOISING_GT_QUESTION = {
    "toc": "Clean targets",
    "text": "Do you have clean (noise-free) versions of your noisy images to use as targets?",
    "type": "choice",
    "answers": ["No, I only have the noisy images", "Yes, I have clean target images"],
    "map": {DENOISING_GT: [False, True]},
    "visible": False,
    "condition": {"and_cond": [["PROBLEM.TYPE", "DENOISING"]], "or_cond": []},
    "help": "<p>BiaPy can train a denoising model in two ways:</p><ul>"
    "<li><p><strong>'No, I only have the noisy images'</strong>: self-supervised training with Noise2Void. Only the "
    "noisy images are needed, which is useful because clean images are frequently unavailable.</p></li>"
    "<li><p><strong>'Yes, I have clean target images'</strong>: supervised training. Each noisy image has a clean "
    "version of the same field of view and size, used as the target to learn from. This usually gives better "
    "results. The Wizard will ask for the folder with the clean images: they are paired with the noisy ones by "
    "file name order, so both folders must contain the same number of images.</p></li></ul>",
}
DENOISING_GT_HELP = (
    "<p>For <strong>Denoising</strong> (only when clean targets are available): the clean version of each noisy "
    "image, with the same size.</p>"
)


# How long to train (biapy.wizard.config_builder.TRAINING_LENGTHS, not a BiaPy variable): a quick training, e.g. to
# show how BiaPy works in a course, or a complete one, as long as nnU-Net's. Asked whenever the model is trained.
TRAINING_LENGTH_QUESTION = {
    "toc": "Training length",
    "text": "How long should the training be?",
    "type": "choice",
    "answers": [
        "Quick: to check that everything works or to try BiaPy",
        "Complete: for the best results (it can take a day)",
    ],
    "map": {"training_length": ["quick", "full"]},
    "visible": False,
    "condition": {"and_cond": [["TRAIN.ENABLE", True]], "or_cond": []},
    "help": "<p>How much the model is trained, which decides how long it takes:</p><ul>"
    "<li><p><strong>Quick</strong>: a short training, to check that the data and the configuration work, or to see "
    "how BiaPy works, e.g. in a course. In segmentation and detection it is 2% of a complete training: on a recent "
    "NVIDIA GPU about 20 minutes for 2D images and 35 minutes for 3D ones (longer on laptops, Apple Silicon or "
    "without a GPU). The results are usually already reasonable, but not the best the model can give.</p></li>"
    "<li><p><strong>Complete</strong>: as long as <a href='https://github.com/MIC-DKFZ/nnUNet'>nnU-Net</a>, a reference "
    "method for biomedical segmentation, trains: 1000 epochs of 250 batches. It gives the best results but can take "
    "a day on such a GPU. It stops earlier when the model stops improving (on the validation data).</p></li></ul>"
    "<p>A quick training can be continued later: select the model it produced (<em>checkpoints</em> folder of the "
    "job) as a pretrained BiaPy model and train it again.</p>",
}


# BiaPy-GUI's object size questions, which it uses to choose the patch size, are not asked: the plugin decides
# the patch size from the data or uses a fixed one (biapy.wizard.planning)
OBJECT_SIZE_KEYS = ["DATA.PATCH_SIZE_XY", "DATA.PATCH_SIZE_Z"]


# Instance representation: options from biapy.wizard.instance_seg; the Wizard offers the ones that suit
# the shape of the instances found when the training masks are checked ("options_from")

REPRESENTATION_KEY = "instance_representation"


def representation_question(section):
    ids = list(REPRESENTATIONS)
    return {
        "section": section,
        "toc": "Instance representation",
        "text": "How should the network represent the instances? The options shown suit the shape of the instances "
        "in your training masks.",
        "type": "representation",
        "answers": [REPRESENTATIONS[r]["name"] for r in ids],
        "answer_ids": ids,
        "answer_descriptions": [REPRESENTATIONS[r]["description"] for r in ids],
        "answer_images": ["instance_representations/{}.png".format(r) for r in ids],
        "options_from": "DATA.TRAIN.GT_PATH",
        "map": {REPRESENTATION_KEY: ids},
        "visible": False,
        # Only when the model is trained from scratch (a pretrained one keeps its own), once the training masks
        # were checked
        "condition": {
            "and_cond": [
                ["PROBLEM.TYPE", "INSTANCE_SEG"],
                ["MODEL.SOURCE", "biapy"],
                ["MODEL.LOAD_CHECKPOINT", False],
                ["TRAIN.ENABLE", True],
                ["CHECKED DATA.TRAIN.GT_PATH", 1],
            ],
            "or_cond": [],
        },
        "help": "<p>Instance segmentation networks do not predict the instances directly but an intermediate "
        "<strong>representation</strong> of them (e.g. their foreground and contours), from which the instances are "
        "built afterwards. Which one works best depends on the shape of the instances.</p>"
        "<p>When the training masks were checked, the shape of their instances was measured (size, convexity, "
        "elongation, branching, how often they touch each other and whether they are star-convex, i.e. every point "
        "sees their center in a straight line). Only the representations that suit them are offered, and the "
        "recommended one is marked. The others are listed after them with the reason why they are not offered.</p>"
        "<ul><li><strong>Foreground + contour</strong>: works for any shape, the contours separate touching instances.</li>"
        "<li><strong>Foreground + central point</strong> / <strong>distance to the border</strong>: compact, roundish "
        "instances, marked by a single point or distance peak.</li>"
        "<li><strong>Foreground + skeleton</strong>: elongated, thin or branched instances.</li>"
        "<li><strong>StarDist</strong>: star-convex instances, e.g. nuclei, even when crowded.</li>"
        "<li><strong>Cellpose</strong> flows: irregular, crowded cells.</li>"
        "<li><strong>HoVer-Net</strong>: compact instances such as nuclei in histology images.</li>"
        "<li><strong>Affinities</strong>: dense 3D instances that fill the volume, such as neurons in electron "
        "microscopy.</li></ul>",
    }


def apply_fiji_question_changes(questions):
    """
    Remove the object size questions, insert the clean-targets and training length questions after 'Phases', let the target
    questions apply to denoising too and insert the instance representation question after the training
    targets.
    """
    questions[:] = [q for q in questions if next(iter(q["map"])) not in OBJECT_SIZE_KEYS]
    phases = next(i for i, q in enumerate(questions) if "TRAIN.ENABLE" in q["map"])
    new_q = dict(DENOISING_GT_QUESTION, section=questions[phases]["section"])
    questions.insert(phases + 1, new_q)
    questions.insert(phases + 2, dict(TRAINING_LENGTH_QUESTION, section=questions[phases]["section"]))

    def denoising_group(extra):
        return {"and_cond": extra + [["PROBLEM.TYPE", "DENOISING"], [DENOISING_GT, True]], "or_cond": []}

    for q in questions:
        key = next(iter(q["map"]))
        extra = None
        if key == "DATA.TRAIN.GT_PATH":
            extra = [["TRAIN.ENABLE", True]]
        elif key == "DATA.TEST.LOAD_GT":
            extra = [["TEST.ENABLE", True]]
        elif key == "DATA.TEST.GT_PATH":
            extra = [["TEST.ENABLE", True], ["DATA.TEST.LOAD_GT", True]]
        if extra is not None:
            # Visible when any of the groups holds (the original condition, or denoising with clean targets)
            q["condition"] = {"any_of": [q["condition"], denoising_group(extra)]}
            if key.endswith("GT_PATH"):
                q["help"] += DENOISING_GT_HELP
    train_gt = next(i for i, q in enumerate(questions) if next(iter(q["map"])) == "DATA.TRAIN.GT_PATH")
    questions.insert(train_gt + 1, representation_question(questions[train_gt]["section"]))
    for i, q in enumerate(questions):
        q["id"] = "Q{}".format(i + 1)
    return questions


def clean_html(html):
    for pattern, repl in FIJI_TEXT_CHANGES:
        html = re.sub(pattern, repl, html)
    # Images are referenced as {img:<file>} and resolved inside the jar by the plugin
    html = re.sub(r"[^'\"]*images[/\\]wizard[/\\]([\w.-]+\.png)", r"{img:\1}", html)
    # The Qt layout used many <br> after floating images; Swing does not float them
    html = re.sub(r"(<br>\s*){2,}", "<br>", html)
    html = re.sub(r"<div style='float:center;'>\s*(<img[^>]*>)\s*</div>", r"<p align='center'>\1</p>", html)
    html = re.sub(r"\s{2,}", " ", html)
    return html.strip()


questions = []
for i in range(n):
    qid = "Q{}".format(i + 1)
    answers = s["wizard_possible_answers"][i]
    qtype = {"PATH": "path", "MODEL_BIAPY": "model_biapy", "MODEL_OTHERS": "model_others"}.get(answers[0], "choice")
    sec, _ = s["wizard_from_question_index_to_toc"][i]
    toc_index = s["wizard_from_question_index_to_toc"][i][1]
    q = {
        "id": qid,
        "section": sec,
        "toc": s["wizard_sections"][sec][1][toc_index],
        "text": s["wizard_questions"][i],
        "type": qtype,
        "answers": answers if qtype == "choice" else [],
        "map": s["wizard_variable_to_map"][qid],
        "visible": s["wizard_question_visible_default"][i],
        "help": clean_html(s["wizard_question_additional_info"][i]),
    }
    if qid in s["wizard_question_condition"]:
        q["condition"] = s["wizard_question_condition"][qid]
    questions.append(q)

questions = apply_fiji_question_changes(questions)

out = {
    "sections": [sec[0] for sec in s["wizard_sections"]],
    "questions": questions,
}
dst = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "main", "resources", "biapy", "wizard", "questions.json")
with open(dst, "w", encoding="utf8") as f:
    json.dump(out, f, indent=1, ensure_ascii=False)
print("Written", os.path.normpath(dst), "with", n, "questions")
