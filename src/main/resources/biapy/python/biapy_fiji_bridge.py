"""
Bridge between the BiaPy Fiji plugin (Java) and Python.

Every command prints free-form log lines and ends with exactly one line:
    BIAPY_FIJI_RESULT:<json>
which is what the Java side parses. The wizard logic (data checks, preview and configuration) is BiaPy's own
(biapy.wizard), shared with BiaPy-GUI, so both front-ends produce the same configuration files. Fixes for bugs of
released BiaPy versions go to biapy_compat.py, not here.

Usage:
    python biapy_fiji_bridge.py <command> [--json <file with arguments>]
    python biapy_fiji_bridge.py serve

"serve" keeps the process alive and runs one command per line read from stdin (a JSON object with "command"
and "args"), each ending with its result line as above. BiaPy's modules are imported in the background as it
starts, so the wizard does not wait for them (importing PyTorch and BiaPy takes several seconds).
"""
import argparse
import json
import os
import sys
import threading
import traceback

RESULT_PREFIX = "BIAPY_FIJI_RESULT:"
# Lines with the step a command is in, shown by the Java side (e.g. in the data check window)
STEP_PREFIX = "BIAPY_FIJI_STEP:"
# Lines with the progress of a command over files, as JSON: {"done", "total", "name"} (name null when the files
# are all read and the data is analyzed)
PROGRESS_PREFIX = "BIAPY_FIJI_PROGRESS:"
# Modules the wizard commands use, imported in the background when serving
WARM_UP_MODULES = [
    "biapy.wizard.data_check",
    "biapy.wizard.preview",
    "biapy.wizard.config_builder",
    "biapy.engine.check_configuration",
    "biapy.data.data_manipulation",
    "biapy.models",
]


def log(msg):
    # Not print(): once a model is loaded, BiaPy's print puts the time before each line, hiding the prefixes below
    sys.stdout.write("{}\n".format(msg))
    sys.stdout.flush()


def step(msg):
    log(STEP_PREFIX + msg)


def progress(done, total, name):
    log(PROGRESS_PREFIX + json.dumps({"done": done, "total": total, "name": name}))


def wizard():
    """biapy.wizard, with a clear message for the BiaPy versions without it."""
    try:
        import biapy.wizard

        return biapy.wizard
    except ImportError:
        raise RuntimeError(
            "The BiaPy version installed does not include the wizard used by the plugin. Install a newer BiaPy "
            "version from the HOME tab."
        ) from None


#############
# Commands  #
#############
def cmd_env_quick(args):
    """
    Fast check (~0.2 s): versions from the installed packages' metadata and sources, without importing them
    (importing BiaPy versions without the wizard takes 10+ s).
    """
    import importlib.metadata as md
    import importlib.util
    import platform
    import re

    info = {"python": sys.version.split()[0], "executable": sys.executable, "wizard_api": None}
    for pkg, key in [("biapy", "biapy_version"), ("torch", "torch_version")]:
        try:
            info[key] = md.version(pkg)
        except md.PackageNotFoundError:
            info[key] = None

    def source_value(path, name):
        with open(path, "r", encoding="utf8") as fh:
            m = re.search(r"""^{}\s*=\s*["']?([^"'\s#]+)""".format(name), fh.read(), re.M)
        return m.group(1) if m else None

    # The metadata can be stale for editable installs (pip install -e): read biapy's __version__ from its
    # source, which find_spec locates without executing it. The same for the wizard's interface version.
    try:
        spec = importlib.util.find_spec("biapy")
        if spec is not None and spec.origin:
            info["biapy_version"] = source_value(spec.origin, "__version__") or info["biapy_version"]
            wizard_init = os.path.join(os.path.dirname(spec.origin), "wizard", "__init__.py")
            if os.path.isfile(wizard_init):
                api = source_value(wizard_init, "API_VERSION")
                info["wizard_api"] = int(api) if api and api.isdigit() else None
    except Exception:
        pass
    # Where BiaPy was installed from, when not from a release (e.g. a branch's zip, or a folder with -e)
    try:
        direct_url = md.distribution("biapy").read_text("direct_url.json")
        info["biapy_source"] = json.loads(direct_url).get("url") if direct_url else None
    except Exception:
        info["biapy_source"] = None
    # (the GPUs are listed by the Java side with nvidia-smi, whatever the BiaPy version)
    info["mps_available"] = sys.platform == "darwin" and platform.machine() == "arm64"
    return info


def cmd_env_verify(args):
    """
    Slower check run in the background after env_quick: imports PyTorch to confirm it works and whether it can
    use the GPU.
    """
    info = {}
    try:
        import torch

        info["torch_version"] = torch.__version__
        info["cuda_available"] = bool(torch.cuda.is_available())
        info["cuda_devices"] = torch.cuda.device_count() if torch.cuda.is_available() else 0
        info["mps_available"] = bool(getattr(torch.backends, "mps", None) and torch.backends.mps.is_available())
    except Exception as e:
        info["torch_error"] = "{}: {}".format(type(e).__name__, e)

    # pydantic 2.14+ breaks bioimageio.spec before 0.5.14.3 (every BiaPy release so far): no BioImage Model Zoo
    # model loads. Installations made by this plugin version avoid it (BiaPyEnvManager.fixPydantic)
    import importlib.metadata as md

    def version(pkg):
        try:
            return tuple(int(x) for x in md.version(pkg).split(".")[:4] if x.isdigit())
        except Exception:
            return None

    spec, pydantic = version("bioimageio.spec"), version("pydantic")
    if spec and pydantic and spec < (0, 5, 14, 3) and pydantic >= (2, 14):
        info["bmz_warning"] = (
            "BioImage Model Zoo models can't be loaded: pydantic {} is too new for this BiaPy. Fix it with "
            "'Reinstall BiaPy' > 'Continue / repair'.".format(md.version("pydantic"))
        )
    return info


def cmd_check_data(args):
    """Checks a folder selected in the wizard and gathers its fingerprint (biapy.wizard.data_check)."""
    wizard()
    from biapy.wizard.config_builder import to_jsonable

    from biapy_compat import check_folder

    step("Reading and analyzing the files of the folder . . .")
    return to_jsonable(
        check_folder(
            args["folder"], args["key"], args["workflow"], args["ndim"], progress=progress,
            percentage=float(args.get("percentage", 100)), data_path=args.get("data_path", ""),
            tracking=bool(args.get("tracking", False)), train_channels=args.get("train_channels"),
        )
    )


def cmd_data_size(args):
    """Size of the Zarr/H5 images of a folder, to ask how much of them to check when they are large."""
    wizard()
    from biapy.wizard.data_check import folder_size

    step("Measuring the Zarr/H5 images of the folder . . .")
    return folder_size(args["folder"])


def cmd_preview_data(args):
    """
    A few raw images next to their targets (base64 PNGs) and warnings about anything that looks wrong for the
    workflow, shown after a folder is checked. ``raw`` and ``target`` are folders (either may be empty).
    """
    wizard()
    from biapy_compat import preview

    step("Preparing the preview of the images . . .")
    return preview(
        args["workflow"], args["ndim"], args.get("raw") or None, args.get("target") or None,
        target_data_path=args.get("target_data_path", ""),
    )


def cmd_find_models(args):
    """
    The models of the BioImage Model Zoo that BiaPy can use for the workflow, from BiaPy's own search
    (biapy.models.bmz_search, shared with BiaPy's notebooks; Torchvision models are left out on purpose). Each
    compatible one is printed as soon as it is found:
        MODEL_PROGRESS:<checked> <total>
        MODEL_FOUND:<json>
    """
    try:
        import biapy.models.bmz_search  # noqa: F401
    except ImportError:
        raise RuntimeError(
            "This BiaPy version can't search the BioImage Model Zoo from the plugin: please update BiaPy "
            "('Reinstall BiaPy' in the HOME tab)."
        )
    from biapy.wizard.config_builder import to_jsonable

    from biapy_compat import list_compatible_bmz_models

    def entry(info):
        # BiaPy loads Model Zoo models by their nickname (e.g. "wild-whale"), which is the "id" that goes to
        # MODEL.BMZ.SOURCE_MODEL_ID; "name" is only shown to the user
        out = {k: v for k, v in info.items() if k != "opts"}
        out["source"] = "BioImage Model Zoo"
        out["imposed_vars"] = to_jsonable(info["opts"])
        return out

    # The search hides what is printed meanwhile (quiet): the download bars of bioimageio, on the same stream for
    # the plugin, put escape codes before some lines, which were then missed. These lines go to the real stdout.
    out = sys.stdout

    def emit(line):
        out.write(line + "\n")
        out.flush()

    def checked(done, total, info, nickname, reason):
        if info is None:
            emit("  {}: not compatible ({})".format(nickname, reason))
        else:
            emit("MODEL_FOUND:" + json.dumps(entry(info)))
        emit("MODEL_PROGRESS:{} {}".format(done, total))

    log("Getting the list of models from the BioImage Model Zoo . . .")
    models = list_compatible_bmz_models(
        workflow=args["workflow"], ndim=args["ndim"], nchannels=args.get("nchannels", "all"), quiet=True,
        callback=checked, cache=args.get("cache"),
    )
    log("{} compatible models found".format(len(models)))
    return {"models": [entry(m) for m in models]}


def cmd_build_config(args):
    """Configuration file from the wizard's answers, adapted to the data (biapy.wizard.config_builder)."""
    wizard()
    from biapy.wizard.config_builder import WizardError, to_jsonable, write_config

    from biapy_compat import build_config

    try:
        cfg, report = build_config(args["answers"], args.get("gpus"))
    except WizardError as e:
        return {"error": True, "error_message": str(e)}
    if report["applied"]:
        log("Configuration adapted to the data:")
    else:
        log("Default configuration used ({})".format(report["reason"]))
    for line in report.get("lines", []):
        log("  " + line)
    write_config(cfg, report, args["yaml_file"], tool="the BiaPy Fiji plugin")
    log("Configuration file written: {}".format(args["yaml_file"]))
    return {"error": False, "yaml_file": args["yaml_file"], "config": to_jsonable(cfg), "plan": to_jsonable(report)}


def cmd_estimate_resources(args):
    """
    RAM, GPU memory and disk the job of the wizard's answers would need (its plan, without writing the
    configuration). "resources" is None with BiaPy versions that don't estimate them.
    """
    wizard()
    from biapy.wizard.config_builder import WizardError, to_jsonable

    from biapy_compat import build_config

    try:
        cfg, report = build_config(args["answers"], args.get("gpus"))
    except WizardError as e:
        return {"resources": None, "error_message": str(e)}
    return {"resources": to_jsonable(report.get("resources")), "config": to_jsonable(cfg)}


def _instance_outputs(cfg):
    """The channels of the raw prediction of instance segmentation (per_image), in order: how many each one takes."""
    from biapy.data.pre_processing import instance_channel_names

    inst = cfg.PROBLEM.INSTANCE_SEG
    opts = dict(inst.DATA_CHANNELS_EXTRA_OPTS[0]) if inst.DATA_CHANNELS_EXTRA_OPTS else {}
    names = instance_channel_names(inst.DATA_CHANNELS, opts, 3 if cfg.PROBLEM.NDIM == "3D" else 2)
    outputs = [
        {"channel": ch, "count": sum(1 for c, _ in names if c == ch), "type": opts.get(ch, {}).get("type")}
        for ch in dict.fromkeys(c for c, _ in names)
    ]
    if cfg.DATA.N_CLASSES > 2:
        outputs.append({"channel": "class", "count": int(cfg.DATA.N_CLASSES), "type": None})
    return outputs


def cmd_workflow_info(args):
    """
    The settings of a configuration that the "Workflow info" window explains, as BiaPy resolves them (e.g. the
    watershed channels it chooses when the configuration leaves them empty).
    """
    from biapy._biapy import validate_config

    cfg = validate_config(args["yaml_file"], check_data_paths=False)
    p = cfg.PROBLEM
    out = {
        "workflow": p.TYPE,
        "ndim": p.NDIM,
        "n_classes": int(cfg.DATA.N_CLASSES),
        "train": bool(cfg.TRAIN.ENABLE),
        "test": bool(cfg.TEST.ENABLE),
        "by_chunks": bool(cfg.TEST.BY_CHUNKS.ENABLE),
        "tracking": bool(p.TRACKING.ENABLE),
    }
    # Zarr files the job writes, all as OME-Zarr images: the results predicted by chunks and the training masks of
    # instance segmentation and detection created from Zarr images
    def has_zarr(folder):
        try:
            return any(f.endswith(".zarr") for f in os.listdir(folder))
        except OSError:
            return False

    out["zarr_outputs"] = out["by_chunks"] or (
        out["train"] and p.TYPE in ("INSTANCE_SEG", "DETECTION") and has_zarr(cfg.DATA.TRAIN.PATH)
    )
    out["ome_zarr_version"] = str(cfg.DATA.OME_ZARR.VERSION)
    if p.TYPE == "INSTANCE_SEG":
        inst = p.INSTANCE_SEG
        ws = inst.WATERSHED
        out["instance"] = {
            "channels": list(inst.DATA_CHANNELS),
            "outputs": _instance_outputs(cfg),
            "creation": inst.INSTANCE_CREATION_PROCESS,
            "watershed": {
                "seed_channels": list(ws.SEED_CHANNELS),
                "seed_ths": [str(x) for x in ws.SEED_CHANNELS_THRESH],
                "topographic_surface": ws.TOPOGRAPHIC_SURFACE_CHANNEL,
                "growth_mask_channels": list(ws.GROWTH_MASK_CHANNELS),
                "growth_mask_ths": [str(x) for x in ws.GROWTH_MASK_CHANNELS_THRESH],
            },
            "stardist": {"prob_thresh": inst.STARDIST.PROB_THRESH, "nms_iou_thresh": inst.STARDIST.NMS_IOU_THRESH},
            "cellpose": {"fg_thresh": inst.CELLPOSE.FG_THRESH, "flow_threshold": inst.CELLPOSE.FLOW_THRESHOLD},
            "embedseg": {"fg_thresh": inst.EMBEDSEG.FG_THRESH, "seed_thresh": inst.EMBEDSEG.SEED_THRESH},
            "agglomeration": {
                "fragment_seed_th": inst.AGGLOMERATION.FRAGMENT_SEED_TH,
                "fragment_growth_th": inst.AGGLOMERATION.FRAGMENT_GROWTH_TH,
                "merge_th": inst.AGGLOMERATION.MERGE_TH,
            },
        }
    elif p.TYPE == "DETECTION":
        out["detection"] = {
            "min_th": [float(x) for x in cfg.TEST.DET_MIN_TH_TO_BE_PEAK]
            if isinstance(cfg.TEST.DET_MIN_TH_TO_BE_PEAK, (list, tuple)) else float(cfg.TEST.DET_MIN_TH_TO_BE_PEAK),
            "th_type": cfg.TEST.DET_TH_TYPE,
            "min_distance": int(cfg.TEST.DET_PEAK_LOCAL_MAX_MIN_DISTANCE),
        }
    return out


def cmd_read_config(args):
    """Returns the YAML as JSON so the Run page can show a summary."""
    import yaml

    with open(args["yaml_file"], "r", encoding="utf8") as f:
        cfg = yaml.safe_load(f)
    return {"config": json.loads(json.dumps(cfg, default=str))}


def cmd_citations(args):
    """
    Works to cite for a configuration (biapy.citations): BiaPy, the model architecture, the methods it uses... and
    the references of its BioImage Model Zoo model, if any. Older BiaPy versions don't have biapy.citations.
    """
    try:
        from biapy.citations import get_citations
    except ImportError:
        raise RuntimeError("The BiaPy version installed does not list the works to cite. Install a newer BiaPy version "
                           "from the HOME tab.") from None
    import yaml
    from yacs.config import CfgNode as CN

    from biapy._biapy import validate_config
    from biapy.config.config import Config

    try:
        cfg = validate_config(args["yaml_file"], check_data_paths=False)
    except Exception as e:
        # A configuration BiaPy would refuse still has the variables the citations depend on
        log("Configuration not validated ({}): using it with BiaPy's defaults".format(e))
        with open(args["yaml_file"], "r", encoding="utf8") as f:
            cfg = Config("citations", "citations")._C.clone()
            cfg.defrost()
            cfg.merge_from_other_cfg(CN(yaml.safe_load(f) or {}))
    extra = []
    if str(cfg.MODEL.SOURCE).lower() == "bmz" and cfg.MODEL.BMZ.SOURCE_MODEL_ID:
        try:
            from biapy.models import load_bmz_model_description

            model_id = cfg.MODEL.BMZ.SOURCE_MODEL_ID
            for c in load_bmz_model_description(model_id).cite or []:
                # Grouped (BiaPy versions without groups ignore it): their texts are often just roles, e.g. "data"
                extra.append({"text": str(c.text), "doi": str(c.doi) if c.doi else None, "url": str(c.url) if c.url else None,
                              "reason": "Pretrained model from the BioImage Model Zoo: its own references",
                              "group": "The references of the BioImage Model Zoo model '{}'".format(model_id),
                              "group_url": "https://bioimage.io/#/artifacts/{}".format(model_id)})
        except Exception as e:
            log("Could not read the references of the model {} ({})".format(cfg.MODEL.BMZ.SOURCE_MODEL_ID, e))
    return {"citations": [c.to_dict() for c in get_citations(cfg, extra=extra)]}


#: The model applied to the images open in Fiji, kept loaded between images
# "device": the one of the first model built in this process. CUDA reads CUDA_VISIBLE_DEVICES once per process, so
# a model can't move to another device afterwards: the server is restarted for it (cmd_load_model)
_applied = {"key": None, "biapy": None, "device": None}


def _device():
    """The GPU with the most free memory (as nvidia-smi numbers them), Apple's GPU, or "" (CPU)."""
    import subprocess

    import torch

    if torch.cuda.is_available():
        if os.environ.get("CUDA_VISIBLE_DEVICES"):
            return "0"
        try:
            free = subprocess.run(["nvidia-smi", "--query-gpu=memory.free", "--format=csv,noheader,nounits"],
                                  capture_output=True, text=True, timeout=20).stdout.split()
            free = [int(f) for f in free]
            return str(free.index(max(free)))
        except Exception:
            return "0"
    mps = getattr(torch.backends, "mps", None)
    return "mps" if mps is not None and mps.is_available() else ""


def _model(args, source=None):
    """
    The BiaPy instance of the model of args ("model": a .pth checkpoint or a BioImage Model Zoo nickname), built from
    "source" if given (the zoo model downloaded).
    """
    from biapy import BiaPy

    if not hasattr(BiaPy, "predict"):
        raise RuntimeError("The BiaPy version installed can't apply a model to an image open in Fiji. Install a newer "
                           "BiaPy version in the 'Installation' tab.")
    # "gpu": the device chosen (a GPU number, "mps" or "" for the CPU); without it, the GPU with most free memory
    key = (args["model"], args["out_dir"], args.get("gpu"))
    if _applied["key"] != key:
        _applied["key"], _applied["biapy"] = None, None
        device = args["gpu"] if "gpu" in args else _device()
        if _applied["device"] not in (None, device):
            raise RuntimeError("This Python process already used another device: restart it to use {}.".format(
                _device_name(device)))
        _applied["device"] = device
        log("Building the model {} . . .".format(args["model"]))
        _applied["biapy"] = BiaPy.load_workflow_from_model(source or args["model"], result_dir=args["out_dir"],
                                                           name="apply", gpu=device)
        # The raw output of the model is written too, to be opened in Fiji if asked
        _applied["biapy"].update_config({"TEST.SAVE_MODEL_RAW_OUTPUT": True})
        _applied["key"] = key
    return _applied["biapy"]


def _device_name(device):
    return "GPU " + device if device.isdigit() else device.upper() or "CPU"


def _model_info(cfg, post_processing):
    """What "Apply model" needs to know of a model: its workflow, the images it takes and what its outputs show."""
    out = {
        "workflow": cfg.PROBLEM.TYPE,
        "ndim": cfg.PROBLEM.NDIM,
        "channels": int(list(cfg.DATA.PATCH_SIZE)[-1]),
        "tracking": bool(cfg.PROBLEM.TRACKING.ENABLE),
        "post_processing": bool(post_processing),
        "classes": int(cfg.DATA.N_CLASSES),
    }
    if cfg.PROBLEM.TYPE == "INSTANCE_SEG":
        out["instance_outputs"] = _instance_outputs(cfg)
    if cfg.PROBLEM.TYPE == "DETECTION":
        out["min_th"] = str(cfg.TEST.DET_MIN_TH_TO_BE_PEAK)
        out["min_distance"] = str(cfg.TEST.DET_PEAK_LOCAL_MAX_MIN_DISTANCE)
    return out


def cmd_describe_model(args):
    """
    What a model is (as load_model returns it) without downloading it, building it or touching the GPU: the
    configuration inside a .pth checkpoint ("model"), or the options of a BioImage Model Zoo model found by find_models
    ("zoo": its search entry: workflow_type, ndim and imposed_vars, what BiaPy sets from the model).
    """
    from yacs.config import CfgNode as CN

    from biapy import BiaPy
    from biapy.config.config import Config, update_dependencies
    from biapy.engine.check_configuration import convert_old_model_cfg_to_current_version

    zoo = args.get("zoo")
    download = None
    if zoo:
        from biapy_compat import zoo_downloads

        files, downloaded, _, cache = zoo_downloads()
        try:
            size = sum(f["size"] for f in files(args["model"]))
        except Exception:
            size = None
        download = {"size": size, "saved": downloaded(args["model"]) is not None,
                    "folder": os.path.join(cache, args["model"])}
        raw = {"PROBLEM": {"TYPE": zoo["workflow_type"], "NDIM": zoo["ndim"]}}
    else:
        raw = BiaPy._config_from_checkpoint(args["model"])
    manager = Config("describe", "describe")
    manager._C.merge_from_other_cfg(CN(convert_old_model_cfg_to_current_version(raw)))
    update_dependencies(manager)
    cfg = manager.get_cfg_defaults()
    if zoo:
        opts = zoo["imposed_vars"]
        cfg.merge_from_list([x for k in opts if k.startswith("PROBLEM.") or k.startswith("DATA.") for x in (k, opts[k])])
        update_dependencies(cfg)
    # shortcut: the workflows' rule for post_processing["per_image"] (base_workflow: median filter; instance_seg:
    # synapse points), copied as they can't be built without the GPU; load_model returns the workflow's own
    inst = cfg.PROBLEM.INSTANCE_SEG
    post = (cfg.TEST.POST_PROCESSING.MEDIAN_FILTER and not (cfg.PROBLEM.NDIM == "2D" and cfg.TEST.ANALIZE_2D_IMGS_AS_3D_STACK)) or (
        cfg.PROBLEM.TYPE == "INSTANCE_SEG" and inst.TYPE != "regular"
        and (inst.SYNAPSES.REMOVE_CLOSE_PRE_POINTS_RADIUS > 0 or inst.SYNAPSES.REMOVE_CLOSE_POST_POINTS_RADIUS > 0)
    )
    out = _model_info(cfg, post)
    if download:
        out["download"] = download
    return out


def cmd_load_model(args):
    """
    Builds a model to apply it to the images open in Fiji, downloading it if needed: a BiaPy .pth checkpoint or a
    BioImage Model Zoo nickname ("model"; "keep": keep it in the cache folder), on "gpu", with its results written in
    "out_dir", and loads its weights there.
    It is kept for the next images. Returns what it is (as describe_model), or {"restart": true} if this process can't
    use that device: the server must be restarted.
    """
    if "gpu" in args and _applied["device"] not in (None, args["gpu"]):
        return {"restart": True}
    import shutil
    import tempfile
    import time

    # A zoo model is downloaded first (kept in the cache folder if "keep" or if it already is, else only while it is
    # built), with its progress as DOWNLOAD_PROGRESS:<bytes done> <bytes in all> lines and DOWNLOAD_KEPT once kept
    source, temporary = None, None
    built = _applied["key"] == (args["model"], args["out_dir"], args.get("gpu"))
    if not args["model"].endswith(".pth"):
        from biapy_compat import zoo_downloads

        _, downloaded, download, _ = zoo_downloads()
        kept = downloaded(args["model"]) is not None
    # Built already: downloaded only to keep it now
    if not args["model"].endswith(".pth") and (not built or (args.get("keep") and not kept)):
        if not args.get("keep") and not kept:
            temporary = tempfile.mkdtemp(prefix="biapy_zoo_model_")
        out, last = sys.stdout, [0.0]

        def progress(done, total):
            if time.time() - last[0] > 0.5 or done >= total:
                last[0] = time.time()
                out.write("DOWNLOAD_PROGRESS:{} {}\n".format(done, total))
                out.flush()

        source = download(args["model"], temporary, callback=progress)
        if temporary is None:
            out.write("DOWNLOAD_KEPT\n")  # complete, even if building the model fails below
            out.flush()
    try:
        b = _model(args, source)
        if not b.workflow.model_prepared:
            log("Loading the model {} on {} . . .".format(args["model"], _device_name(_applied["device"])))
            b.workflow.prepare_model()
    finally:
        if temporary:
            shutil.rmtree(temporary, ignore_errors=True)
    return _model_info(b.cfg, b.workflow.post_processing.get("per_image"))


def cmd_cache_folders(args):
    """
    Where models are downloaded: BiaPy's folder (the zoo models "Apply model" keeps) and bioimageio's (the zoo models
    of the jobs, downloaded by BiaPy through bioimageio).
    """
    from bioimageio.spec import settings

    from biapy_compat import zoo_downloads

    return {"biapy": zoo_downloads()[3], "bioimageio": str(settings.cache_path)}


def cmd_apply_model(args):
    """
    Applies the model of cmd_load_model to an image (a .npy file, "image": (y, x, c) in 2D, (z, y, x, c) in 3D). Its
    results are in the usual folders of a test (raw output in per_image, instances in per_image_instances...) of the
    "results_dir" returned, which only holds this image's.
    """
    import shutil

    import numpy as np

    from biapy_compat import predict

    b = _model(args)
    results = b.cfg.PATHS.RESULT_DIR.PATH
    shutil.rmtree(results, ignore_errors=True)
    predict(b, np.load(args["image"]), return_prediction=False)
    return {"results_dir": results}


COMMANDS = {
    "env_quick": cmd_env_quick,
    "env_verify": cmd_env_verify,
    "check_data": cmd_check_data,
    "data_size": cmd_data_size,
    "preview_data": cmd_preview_data,
    "find_models": cmd_find_models,
    "build_config": cmd_build_config,
    "estimate_resources": cmd_estimate_resources,
    "workflow_info": cmd_workflow_info,
    "read_config": cmd_read_config,
    "citations": cmd_citations,
    "cache_folders": cmd_cache_folders,
    "describe_model": cmd_describe_model,
    "load_model": cmd_load_model,
    "apply_model": cmd_apply_model,
}


def run_command(command, args):
    """Runs a command and prints its result line."""
    try:
        result = {"ok": True, "result": COMMANDS[command](args)}
    except Exception as e:
        log(traceback.format_exc())
        result = {"ok": False, "error": "{}: {}".format(type(e).__name__, e), "traceback": traceback.format_exc()}
    sys.stdout.flush()
    log(RESULT_PREFIX + json.dumps(result))


def warm_up():
    import importlib

    for module in WARM_UP_MODULES:
        try:
            importlib.import_module(module)
        except Exception:
            pass  # reported by the command that needs it


def serve():
    import sysconfig

    # Python < 3.12 fills these in a shared dict without a lock: a command reading them while the warm-up does
    # (e.g. importing torch) got it half filled ("AttributeError: 'userbase'"). Filled here, before the thread
    sysconfig.get_config_vars()
    # Imported in the background while the user answers; a command that needs a module meanwhile waits for it
    threading.Thread(target=warm_up, name="warm-up", daemon=True).start()
    for line in sys.stdin.buffer:  # bytes: the requests are UTF-8 whatever the platform's encoding
        line = line.decode("utf8").strip()
        if not line:
            continue
        request = json.loads(line)
        run_command(request["command"], request.get("args") or {})


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=sorted(COMMANDS) + ["serve"])
    parser.add_argument("--json", help="JSON file with the command arguments")
    a = parser.parse_args()
    if a.command == "serve":
        serve()
        return
    args = {}
    if a.json:
        with open(a.json, "r", encoding="utf8") as f:
            args = json.load(f)
    run_command(a.command, args)


if __name__ == "__main__":
    main()
