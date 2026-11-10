# BiaPy-Fiji

Fiji plugin for BiaPy: a Java (Swing) UI that drives the BiaPy Python package, installed by the plugin in its own
environment. The BiaPy core is a separate repo (`/data2/dfranco/BiaPy`) with its own releases. Fiji users run
**released** BiaPy versions.

## First, in every session: purge the fixes a BiaPy release made unnecessary

`biapy_compat.py` holds temporary fixes for released BiaPy versions. Each one is gated by the release that includes it
(`NEXT_RELEASE`, or a version string). Before any other work:
1. Get the latest BiaPy release:
   `curl -s https://pypi.org/pypi/biapy/json | python3 -c "import json,sys; print(json.load(sys.stdin)['info']['version'])"`.
2. Compare it with `biapy.min_version` in `src/main/resources/biapy/biapy-fiji.properties` and with the versions the
   fixes in `biapy_compat.py` are gated on.
3. If a release at least as new as a fix's version is out, purge, and tell the user it was done:
   - Check that the release really includes each fix: `git -C /data2/dfranco/BiaPy show v<X.Y.Z>:<file>`. If it
     doesn't, keep the fix and move its gate to the next release.
   - Set `biapy.version` and `biapy.min_version` to the new release. Older BiaPy installs are then asked to update.
   - Delete the fixes it includes from `biapy_compat.py`, and their asserts from `src/test/python/test_bridge.py`. A
     wrapper left with no fixes calls the core directly; if a wrapper has nothing left, make the bridge import the
     core function again.
   - Set `NEXT_RELEASE` to the release after the new one, for future fixes.
   - Run the tests below and rebuild.

## Layout

- `src/main/java/io/github/biapyx/fiji/`
  - `gui/`: pages and dialogs (`WizardPanel`, `RunPanel`, `JobPanel`, ...). All popups go through
    `UiUtils.htmlPage` / `UiUtils.confirmHtml` / help windows, in the same format.
  - `wizard/WizardModel.java`: questionnaire state, conditions and navigation.
  - `env/`: environment install, BiaPy versions, GPUs, `PythonBridge` (runs the Python side).
- `src/main/resources/biapy/python/`: extracted next to each other at runtime. Every file must be listed in
  `PythonBridge.FILES`.
  - `biapy_fiji_bridge.py`: one `cmd_*` function per command the Java side sends. It only calls BiaPy and passes
    results back: no wizard logic and no workarounds.
  - `biapy_compat.py`: fixes for bugs of released BiaPy versions (see below).
  - `biapy_run.py`: entry point for multi-GPU runs (`torch.distributed.run`).
- `src/main/resources/biapy/wizard/questions.json`: the wizard's questions, **edited by hand**.
  There is no generator: edit it directly (ids `Q<n>` follow the order; nothing in the code uses them).
- `src/test/java/...`: headless harnesses (not unit tests), run by hand.

## Where a change goes

The core is the source of truth for wizard logic: data checks, config building, zoo search and planning
(`biapy.wizard.*`, `biapy.models.bmz_search`). It is shared with the notebooks and BiaPy-GUI.

When that logic has a bug:
1. Fix it in the core repo. The user commits and releases the core themselves; don't wait for a release.
2. Make the plugin work with the current release through `biapy_compat.py`, never by editing the bridge:
   - The bridge calls `biapy_compat.build_config`, `biapy_compat.list_compatible_bmz_models` and
     `biapy_compat.check_folder`. They take the same arguments as the core functions and return the same results.
     Wrap any other core function the same way.
   - Gate each fix with `needed(<first release with the fix>)`. Use `NEXT_RELEASE` for fixes made after the last
     release, and `needed(None)` for things not fixed in the core.
   - Once the oldest BiaPy the plugin supports includes a fix, delete it.
3. A fix applied on top of an already-fixed core must not change anything (the dev checkout reports the last
   released version, so it gets the fixes too).

UI-only changes (Java, `questions.json`) stay in the plugin.

Exception: `RunPanel.mpsConfig` (Java) patches the config for Apple GPUs ("--gpu mps" ran on the CPU in old BiaPy).

## Wizard decisions

- Question order: Problem description (dimensions, workflow, tracking), then Data (phases, clean targets, data
  folders, voxel size), then Model (model source, checkpoint, zoo model, instance representation, point size,
  training length). The data is checked before the model is chosen.
- Voxel size (type `voxel_size`, answer `voxel_size <folder key>` = [z, y, x]): asked after a raw folder whose 3D
  images have no calibration (`n_calibrated` 0; `WizardModel.dataFacts` "UNCALIBRATED <key>", read by the conditions
  but not sent). Training images when the job trains; test images only for test-only detection with ground truth (the
  only case where BiaPy uses their spacing). Empty fields = unknown. 2D is not asked: only the Z/XY ratio is used.
- Point size (detection, training; type `point_size`): `detection_point_radius` / `detection_point_radius_z`, "auto" or
  a number (Z empty = "auto"). The core puts these answers into the fingerprints
  (`biapy.wizard.common.answers_into_fingerprints`). "Show the points" draws the radius on full-resolution crops of the
  training data (`points_crop` of the core preview, added by `biapy_compat.preview` on older releases; drawn by
  `PointsPreview` as BiaPy paints them) with the radius the plan gives (`estimate_resources` returns the config).
- Changing the model source, workflow or dimensions forgets the zoo model and its `model_restrictions`.
- Answers longer than the answer area wrap (`WizardPanel.wrapIfLong`); keep details in the question text or help.
- The zoo search gets `nchannels` (`WizardModel.dataChannels`, see below) and lists only models for that number of
  channels. Cellpose zoo models come in 1- and 3-channel versions.
- The zoo model question shows "Model selected:" (text, not editable), a centered "Find models" button, and the list of
  models with a search field (magnifier icon) at its top right. The search filters the models found on the Java side
  (`WizardPanel.matchesSearch`): words separated by spaces, all of which must be in the nickname, name or tags,
  ignoring case; empty shows them all.
- The zoo model question can't be entered (Continue, Back or the question list) until "Phases" is answered and
  every folder asked is checked (`WizardPanel.mayEnter`). Changing a data folder resets the zoo model chosen.
- The model's channels are the training images' when the job trains, otherwise the test images'
  (`WizardModel.dataChannels`). They also go to the configuration as `DATA.PATCH_SIZE_C`.
- The test images must have the training images' channels. Their check gets `train_channels` (core
  `check_folder(..., train_channels=)`, also used by `biapy check-data`) and fails otherwise. Re-checking the training
  images with another number of channels un-checks the test images (`WizardModel.setDataChecked` returns true) and
  tells the user.
- There is no "use a pretrained model?" reminder at the end of the questions.
- Checking the data is mandatory: a folder question can't be left forward (Continue or the question list) until its
  data is checked; going back is allowed. "Check data" glows (`BeaconFrame`, a halo around the button) while a folder is set but unchecked.
- A model's imposed settings come in `answers["model_restrictions"]`, the search result's `imposed_vars`.

## Testing

Use the scratchpad for everything. Environment details are in the auto-memory (`biapy-test-env`).
- Python env: `/home/dfranco/miniforge3/envs/BiaPy_env` with `LD_LIBRARY_PATH=$ENV/lib`. It imports the dev core
  checkout.
- Test plugin fixes against the **release** users have:
  `git -C /data2/dfranco/BiaPy archive v<X.Y.Z> biapy | tar -x -C <scratch>/biapyXYZ`, then run with
  `PYTHONPATH=<scratch>/biapyXYZ`.
- Also test the dev core with `biapy.__version__` set to `NEXT_RELEASE`. That checks the compat fixes switch off
  and the core fixes do the work.
- Call the bridge's `cmd_*` functions from a script, with answers passed through `json.dumps`/`loads` as the Java
  side sends them (`sys.path.insert(0, ".../src/main/resources/biapy/python")`).
- Zoo models: `MODEL.BMZ.SOURCE_MODEL_ID` is the nickname. Load descriptions with
  `biapy.models.load_bmz_model_description`, not `bioimageio.core.load_description`.
- Java harnesses: `-Djava.awt.headless=true -Duser.home=<scratch>/home`, with the scratchpad as cwd. Otherwise the
  user's ImageJ prefs and `BiaPy-Fiji/biapy/bridge-*` get written.
- Pick a free GPU with `nvidia-smi` first.
- `src/test/python/test_bridge.py` (pytest; install pytest with `pip --target <scratch>` and add it to
  `PYTHONPATH`): bridge files shipped, serve mode, `biapy_compat` fixes.
- `WizardFlowHarness <shots dir> <data dir with train/x train/y test/x test/y> <yaml dir>`: the whole wizard flow
  for 2D semantic segmentation, with screenshots, and fails if a folder question can be left unchecked.
  Run it with `-Dbiapy.python=<env python>`.
- `ModelFlowHarness <shots dir> <folder with single-channel 2D test images>`: zoo model question (real zoo search,
  a few minutes), search "DINO cellpose", selection, and summary.
- `WizardChannelsCheck` (no Python): channels from the training images, test images un-checked when they no longer
  match, zoo model reset when the data or the model source changes.
- `DetectionFlowHarness <shots dir> <3D TIFs without calibration> <CSV points> <yaml dir>`: voxel size and point
  size questions (with "Show the points"), and the configuration they give.
- `InstanceFlowHarness <shots dir> <raw 2D images> <instance masks> <yaml dir>`: instance representation question.
- `gui.PointsPreviewCheck` (no Python): point size answers and the targets drawn.
- `gui.ResultsBoxCheck` (no Python): metrics of the results box (training log and `test_results_metrics.csv`).

## Build (after every requested change)

No JDK/Maven on PATH. Create them once:
`conda create -p <scratch>/jdk -c conda-forge openjdk=17 maven`.
1. Delete any `__pycache__` under `src/`. Then
   `rm -rf target/classes target/test-classes target/generated-sources target/generated-test-sources target/maven-archiver target/maven-status target/*.jar`.
2. `JAVA_HOME=<jdk> PATH=<jdk>/bin:$PATH mvn -o -q package -DskipTests`. The jar must stay Java 8 compatible.
3. `dist/biapy-fiji-<version>-install.zip` contains only `plugins/biapy-fiji-<version>.jar`, with the directory
   entry.
4. Compare the new jar's non-class files with the previous jar and report the differences.

Release: publishing a GitHub release whose tag matches the version in `pom.xml` makes
`.github/workflows/update_site.yml` upload the jar to https://sites.imagej.net/BiaPyX/.

## Conventions

- The user commits and pushes; don't commit unless asked.
- Comments explain why, not history.
- No new Python dependencies; Java uses only what Fiji ships (gson, snakeyaml, commons-compress, JNA).
