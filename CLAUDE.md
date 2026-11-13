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
  - `gui/`: pages and dialogs (`WizardPanel`, `RunPanel`, `JobPanel`, `ApplyModelPanel`, ...). All popups go through
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

## Apply model

`Plugins > BiaPy > Apply model` (`ApplyModel_Plugin`, `gui/ApplyModelWindow` with `gui/ApplyModelPanel`) applies a
model to an image open in Fiji; `Plugins > BiaPy > Apply entire workflow` is the main window.
- "Downloads" (broom icon: a link card in the main window's home, in place of Notebooks, and centered under the
  environment box of Apply model's Installation tab) opens `gui/ModelFoldersDialog`: BiaPy's folder (zoo models kept by
  Apply model) and bioimageio's (zoo models of the jobs, which BiaPy downloads through bioimageio), their sizes
  (measured in the background, with a spinner; Refresh) and "Open folder" (bridge `cache_folders`); no delete button
  on purpose (the user deletes them). The environment box says what its spinner waits for after "ready": env_verify.
- Short and direct: as small as its content. Two tabs: "Installation" (`HomePanel(null)`: only the BiaPy version
  and the environment box, no welcome, links or shortcuts) and "Apply model", disabled until the
  environment is "ok" and supported, then shown. The environment state is `gui/EnvMonitor`, shared by both windows
  (`MainPanel`'s env methods delegate to it): installing in one updates the other. `EnvMonitor.addListener` sends the
  last state at once: the check runs once per Fiji session, so a window opened later would stay empty otherwise.
- Steps: 1 model, 2 image, 3 outputs, 4 device, 5 output info. No blank space: every text says something from the
  start ("No model selected yet.", one line with the image and whether the model takes it, red if not; keep these
  messages short). Step 3 is a two-column grid with short labels (details in Step 5); the window fits its height when
  a model is described (the previous outputs stay, disabled, meanwhile). The progress bar, with what is being done, shares the row of
  the buttons. Step 1, the device and Apply are disabled while a model is described or runs, and the image while it is
  applied; Cancel stops it (`PythonBridge.cancel` also stops a
  command waiting for the server, e.g. behind a zoo search). Selecting a zoo model stops a search still running.
- Step 1, the model, in two tabs: "Checkpoint file" (a `.pth`; `.safetensors` have no configuration) or "BioImage
  Model Zoo", a zoo model chosen in
  `gui/ZooModelsDialog` (the search of every workflow and dimensions, grouped by workflow, dimensions and channels;
  kept for the Fiji session). The list of models is `gui/ModelList`, shared with the wizard's zoo model question.
- Zoo search cache (both searches): `<Fiji>/biapy/cache/bmz_models.json` (~100 KB), the core's
  `bmz_search.CompatibilityCache` (`cache=`; `biapy_compat._CompatibilityCache` for 3.7.3): the checks of each model
  by nickname + `last_modified` + search, dropped when BiaPy's version or blacklist change. A repeated search takes
  ~1 s instead of ~40 s. Covers (scaled to the cards) and emoji are kept in `cache/covers` and `cache/emoji`.
- Step 4, the device: one entry per GPU of the environment check (`EnvMonitor`), Apple's GPU and the CPU; a single
  device (one image). Changing it does nothing until Apply.
- Nothing is downloaded or loaded until Apply (user's request): choosing a model only describes it (bridge
  `describe_model`, no GPU, no download: the `.pth`'s configuration, or the zoo search entry the dialog returns, whose
  `imposed_vars` are what BiaPy sets from the model; also its size, from the zoo's file list, and whether a copy is
  kept) to offer its outputs and check the image; Apply refuses an image the model can't take before anything runs,
  with a full message naming the model (`problem(imp, info, name)`; Step 2's line is the short one).
- Zoo models are downloaded by the plugin, not by bioimageio (which fetches each file whole into memory, so it can't
  report progress, and checks every URL again on each load: ~12 s even when cached): core
  `biapy.models.download_bmz_model` (+ `bmz_model_files`, `downloaded_bmz_model`, `BMZ_MODELS_CACHE`; copies in
  `biapy_compat.zoo_downloads` for 3.7.3) streams the files, reporting `DOWNLOAD_PROGRESS:<done> <total>` (the status
  shows the percentage, size and time left), and BiaPy loads the local description (builds in ~1 s instead of ~12 s).
  "Keep a copy on this computer (X MB)" keeps it in the system's cache folder (`pooch.os_cache("biapy")/bmz_models/
  <nickname>`, e.g. ~/.cache/biapy/bmz_models), after confirming the space; otherwise it goes to a temporary folder
  deleted once the model is loaded. A kept copy is updated when its files change in the zoo and used offline;
  `biapy_download.json` lists the files there (a canceled download resumes) and names the description once complete;
  the bridge prints `DOWNLOAD_KEPT` then (the window shows it kept even if building the model fails after). Ticking
  "keep" for a model loaded already downloads it then (no rebuild). Loading
  the local copy writes nothing in bioimageio's cache (no duplicate). Python runs with `PYTHONUTF8=1`
  (`PythonEnv.environment`): BiaPy 3.7.3 opens a local description without an encoding, and on Windows (cp1252) the
  emoji of zoo descriptions failed (UnicodeDecodeError; fixed in the core with encoding="utf-8"). Then `load_model` (`BiaPy.load_workflow_from_model` on "gpu" +
  `workflow.prepare_model()`) and it stays loaded in the server. `describe_model` copies the workflows' rule for
  post-processing (median filter, synapse points); `load_model`'s answer wins. CUDA reads `CUDA_VISIBLE_DEVICES` once
  per process, so a process that built a model on a device can't use another: `load_model` answers
  `{"restart": true}` and the panel restarts the server (`PythonBridge.restartServer`, between commands). `apply_model` runs BiaPy's `predict(image,
  return_prediction=False)`, which writes the usual test folders in a temporary folder (emptied each time).
- The image goes to Python as a `.npy` file, (z, y, x, c) or (y, x, c), keeping 8/16-bit types (BiaPy's normalization
  depends on them). A 2D model on a stack is applied to each slice; only the current frame of a time series is used.
- Step 3, the outputs, depend on the workflow (`ApplyModelPanel.outputs`): raw output (`per_image`/`full_image`),
  final predictions (instances, classes, points) and post-processed ones when the model post-processes. Points go to
  a point selection on the image and a table; the class of classification to a table. Windows are named
  "BiaPy <output> - <image>", with " (2)", " (3)"... after the output if Fiji has the title already
  (`Results.uniqueTitle`). Semantic and instance
  label images get Fiji's glasbey_on_dark LUT (`Results.labels`, display range 0-255).
- Step 5, "Output info" (`ApplyModelPanel.outputsHtml`): what each output selected is and how to look at it, and how
  the model is applied, for the model's workflow (`load_model` returns classes, the raw channels of instance
  segmentation, detection thresholds; texts of `WorkflowInfoDialog.description`/`channelList`). The raw channels
  come from the bridge's `_instance_outputs` (core `instance_channel_names`: counts of R, A, E_*, classes), shared with
  the main window's Workflow info; every channel code BiaPy accepts needs a `WorkflowInfoDialog.CHANNELS` text (I and
  We are not predicted). Zoo cards name the channels of sets that aren't the wizard's ("Foreground + Contour + Mask").
- The bridge writes its lines with `sys.stdout.write`: once a model is loaded, BiaPy's `print` puts the time first.

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
  (`ModelList.matchesSearch`): words separated by spaces, all of which must be in the nickname, name or tags,
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
- `gui.ApplyModelHarness <shots dir> <2D instance seg .pth> <2D 1-channel image> <results/<job> of a 3D detection test>
  <results/<job> of a classification test> <dir>`: "Apply model" with images in ImageJ's batch mode (loading, raw
  output and instances, a stack slice by slice, an RGB image refused, points and class tables). It writes `.npy`
  files and TIFFs of the same images in `<dir>`: compare them in Python (`np.load` vs `tifffile`, axes moved to
  (z, y, x, c)). The results folders come from `BiaPy(...).predict(image, return_prediction=False)`.

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
- User-visible text is in American English, as BiaPy's (analyze, normalize, color, center, labeled, gray, canceled).
- No new Python dependencies; Java uses only what Fiji ships (gson, snakeyaml, commons-compress, JNA).
