# BiaPy for Fiji

Fiji plugin to run [BiaPy](https://biapyx.github.io) deep-learning workflows. It mimics
[BiaPy-GUI](https://github.com/BiaPyX/BiaPy-GUI) inside a single window with three tabs:

- **HOME**: links (project, forum, docs, templates, notebooks, citation), the status of the Python
  environment (install BiaPy, or point to an existing Python with BiaPy) and detected GPUs.
- **WIZARD**: the same questionnaire as BiaPy-GUI. It checks your data and creates a configuration file. After each
  data folder is checked it shows a preview (raw images next to their targets) and warns about anything that looks
  wrong for the workflow (BiaPy's `biapy.wizard.preview`, `gui/DataPreviewDialog.java`); the summary at the end
  shows the workflow, some of those images and the instance representation chosen.
- **RUN WORKFLOW**: select a configuration, output folder, job name and device, run BiaPy and follow its output.

Menu entry: `Plugins > BiaPy > Run BiaPy`.

## Install

In Fiji: `Help > Update...` > `Manage update sites`, check **BiaPy** (or, until it is in the list, `Add Unlisted Site`
with the URL `https://sites.imagej.net/BiaPyX/`), `Apply and Close`, `Apply changes` and restart Fiji. Then, in
`Plugins > BiaPy > Run BiaPy`, the HOME tab installs BiaPy.

It works in both Fiji-Stable (Java 8) and Fiji-Latest (Java 21). The plugin is a single jar
(`plugins/biapy-fiji-<version>.jar`): the libraries it uses (gson, snakeyaml, commons-compress, JNA) come with Fiji.

## How it runs BiaPy

Same approach as [SAMJ](https://github.com/segment-anything-models-java/SAMJ), without its JDLL dependency:
micromamba is downloaded (`env/MicromambaInstaller.java`) and creates a Python environment inside the Fiji folder
(`<Fiji>/biapy/envs/biapy`, the same layout as SAMJ's), where `biapy` is installed with pip. Then:

- BiaPy jobs run as a child process (`python -c "import biapy; biapy.main()" --config ...`) whose output is
  streamed into the RUN WORKFLOW tab and which can be stopped.
- Everything the Wizard needs from Python (data checks, BioImage Model Zoo model search and
  building the configuration) goes through `src/main/resources/biapy/python/biapy_fiji_bridge.py`. It prints
  logs and finishes each command with one `BIAPY_FIJI_RESULT:<json>` line that the Java side parses. The logic
  itself is BiaPy's (`biapy.wizard`, shared with BiaPy-GUI), so the plugin needs a BiaPy version that has it
  (`biapy.min_version` in `biapy-fiji.properties`; HOME checks `biapy.wizard.API_VERSION`).
- The Wizard's commands go to a Python process kept alive between them (`biapy_fiji_bridge.py serve`). It is
  started as soon as the environment is detected and imports BiaPy and PyTorch in the background, so the user
  doesn't wait for them; stopping a command kills it and the next one starts it again.

Users can also select an existing Python with BiaPy installed (HOME > "Use existing Python...").

## Code layout

```
src/main/java/io/github/biapyx/fiji/
  BiaPy_Plugin.java          ImageJ entry point (single window per session)
  BiaPyFiji.java             versions, links, colours, resource helpers
  env/PythonEnv.java         where the Python is + environment variables to run it
  env/BiaPyEnvManager.java   installation with micromamba + pip
  env/Platform.java, Processes.java  OS checks, and stopping a job with every process it started (also on Java 8)
  env/ProcessRunner.java     runs a process streaming its output, cancellable
  env/PythonBridge.java      calls biapy_fiji_bridge.py commands (wizard ones in the kept-alive process)
  gui/MainWindow.java        the JFrame; content is in gui/MainPanel.java (sidebar + tabs)
  gui/HomePanel.java, WizardPanel.java, RunPanel.java
  wizard/WizardModel.java    wizard answers, conditional questions, navigation (port of BiaPy-GUI logic)
src/main/resources/biapy/
  wizard/questions.json      generated from BiaPy-GUI (scripts/export_wizard_questions.py)
  python/biapy_fiji_bridge.py  thin layer over biapy.wizard
  python/biapy_run.py        launcher of the BiaPy runs
src/test/python/             tests of the bridge (files shipped, serve mode)
```

### Configuration adapted to the data

This lives in BiaPy (`biapy/wizard/`), so BiaPy-GUI can use it too. Checking a folder in the Wizard
(`biapy.wizard.data_check`) also extracts a *fingerprint* of the data (`biapy/wizard/fingerprint.py`): image shapes, voxel spacing (from OME-TIFF/ImageJ metadata), intensity
statistics and, for semantic segmentation masks, the labels, class frequencies, foreground fraction and object
sizes (for instance masks, also their shape; for detection, the distance between points). It travels in the
`sample_info` of each folder to `build_config` (`biapy.wizard.config_builder`), where the workflow's strategy
(`biapy.wizard.planning.PLANNERS`)
adapts the configuration, following [nnU-Net](https://github.com/MIC-DKFZ/nnUNet)'s rules:

- **All of them** (`common.py`, `unet.py`): plain U-Net (`unet`, instance norm, leaky ReLU, 32 feature maps
  doubling up to 320/512) whose downsampling and 1x3x3/3x3x3 kernels follow the voxel spacing, patch and batch
  size from the median image shape and the GPU memory (model fitted to measurements of BiaPy's U-Net),
  foreground oversampling when the foreground is scarce, and no elastic deformations.
- **Semantic segmentation** (`semantic_seg.py`): Dice + cross-entropy loss and the number of classes from all
  the labels found.
- **Instance segmentation** (`instance_seg.py`): the shape of the instances of the training masks is measured
  (solidity, elongation, thickness, branching, star-convexity, touching) and the Wizard asks for the
  representation the network predicts, offering only the ones that suit them, with a recommended one and the
  reason for each: foreground + contour, + central point, + distance to the border, + skeleton, StarDist,
  Cellpose flows, HoVer-Net and, for dense 3D instances such as neurons in electron microscopy, affinities. The
  images of that question (`wizard/instance_representations/`) are drawn by
  `scripts/make_representation_images.py` from real training data, a different dataset for each representation
  (dsb2018, MitoEM, SNEMI3D, Lizard, Omnipose), with the channels computed by BiaPy itself; "Expand image" shows
  the large version (`<id>_full.png`).
- **Detection** (`detection.py`): from the distance between the points of the CSV files, the size of the
  training disks and, for testing, the radius to merge close detections, the minimum distance between peaks
  and the matching tolerance.
- The other workflows have no strategy yet.

The plan is logged and written as a comment at the top of the YAML. Unlike BiaPy-GUI, the Wizard does not ask
for the size of the objects to choose the patch size (`scripts/export_wizard_questions.py` removes those
questions). When the patch size is not planned (no strategy for the workflow, or a pretrained model), it is the
one of the checkpoint to load (BiaPy refuses a different one), or a fixed 256x256 / 20x256x256
(`FIXED_PATCH_SIZE`); denoising and super-resolution keep BiaPy-GUI's own defaults.
Tests: `pytest tests/test_wizard.py` in BiaPy; the GPU memory model is fitted with BiaPy's
`tests/calibrate_unet_memory.py`.

### Keeping in sync with BiaPy-GUI

```bash
# Wizard questions/help texts (needs an env with BiaPy and BiaPy-GUI requirements, e.g. PySide6)
QT_QPA_PLATFORM=offscreen python scripts/export_wizard_questions.py /path/to/BiaPy-GUI
```

The data checks, preview and configuration need no syncing: they are BiaPy's (`biapy.wizard`).

## Build and install

Requires a JDK and Maven. The plugin targets Java 8 (`scijava.jvm.version`), the Java of Fiji-Stable.

```bash
mvn package
# copy the plugin into a Fiji installation
cp target/biapy-fiji-<version>.jar /path/to/Fiji.app/plugins/
```

### Publishing to the update site

The site is `https://sites.imagej.net/BiaPyX/`. Publishing a GitHub release does it automatically and attaches the jar
to the release (`.github/workflows/update_site.yml`, with the repository secret `UPDATE_SITE_PASSWORD`): bump `<version>` in
`pom.xml` first and tag the release with it (`0.2.0` or `v0.2.0`), or the workflow stops.

To do it by hand instead, publish a version (not a `-SNAPSHOT`): build it and upload the jar with
an up-to-date Fiji used only for that (run `Help > Update...` in it first: the Updater refuses to upload from a Fiji
whose libraries are older than the ones the jar was built against, and records those libraries as its dependencies):

```bash
mvn package
UPDATE_SITE_PASSWORD=... scripts/upload_update_site.sh /path/to/Fiji.app            # add --simulate to try first
```

The script uses Fiji's command-line Updater (`--update edit-update-site` / `--update upload`) and removes the password
from that Fiji's list of update sites afterwards. Use a Fiji-Stable (Java 8) one, so the dependencies recorded are
the ones both Fiji-Stable and Fiji-Latest have.

### Development

Point the plugin to an existing BiaPy environment without touching ImageJ preferences:

```bash
java -Dbiapy.python=/path/to/env/bin/python -cp target/classes:$(cat cp.txt) io.github.biapyx.fiji.BiaPy_Plugin
```

The test sources contain headless harnesses that render the window to PNGs and drive the Wizard and a run
(`ScreenshotHarness`, `WizardFlowHarness`, `RunFlowHarness`); run them with `-Djava.awt.headless=true`.

## Third-party content

The emoji of the BioImage Model Zoo models are shown with [Twemoji](https://github.com/jdecked/twemoji) images
(© Twitter, Inc and other contributors, licensed under [CC-BY 4.0](https://creativecommons.org/licenses/by/4.0/)),
downloaded when the model list is displayed.

The image comparing semantic segmentation, instance segmentation and detection in the Wizard's workflow
confirmation (`src/main/resources/biapy/wizard/confirm/sem_inst_det_workflow_diff.jpg`) was adapted from
https://manipulation.csail.mit.edu/segmentation.html. The electron microscopy example is from the BiaPy documentation.
