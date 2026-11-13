"""
Fixes for bugs of released BiaPy versions, so the plugin works with them until a release includes the fix. The
bridge calls BiaPy's wizard through the functions of this module instead of BiaPy's own.

Each fix is fixed in BiaPy itself and names the first release that includes it (None: not fixed in BiaPy yet). It is
only applied to older versions. Once the oldest BiaPy the plugin supports includes a fix, delete it from here.
"""
from packaging.version import Version

# First BiaPy release after 3.7.3, which includes the fixes made in BiaPy on 2026-11-05
NEXT_RELEASE = "3.7.4"

# Zoo models whose packaged ResUNet_SE builds Squeeze-and-Excitation blocks that its forward never uses, so their
# parameters get no gradient and DDP training fails unless it looks for unused parameters (not fixed in BiaPy)
BMZ_MODELS_WITH_UNUSED_PARAMETERS = {"committed-octopus", "demanding-dromedary", "gorgeous-whale", "venomous-swan"}


def needed(fixed_in):
    """Whether the BiaPy installed is older than the release that includes a fix (None: no release does)."""
    import biapy

    return fixed_in is None or Version(biapy.__version__) < Version(fixed_in)


def build_config(answers, gpus=None):
    """biapy.wizard.config_builder.build_config (same arguments and result), with the fixes it needs."""
    from biapy.wizard import config_builder

    # BiaPy versions that do not choose how long to train would write the answer as a variable
    if "training_length" in answers and not hasattr(config_builder, "TRAINING_LENGTHS"):
        answers.pop("training_length")
        print("This BiaPy version does not choose how long to train: its default number of epochs is used", flush=True)

    restrictions = answers.get("model_restrictions") or {}
    # Without targets (inference only) 2 classes were taken, rejecting the models with more
    if needed(NEXT_RELEASE) and restrictions.get("DATA.N_CLASSES", 2) > 2:
        answers.setdefault("data_constraints", {}).setdefault("DATA.N_CLASSES", restrictions["DATA.N_CLASSES"])

    # The answers about the data (voxel size of uncalibrated images, detection point radius) were not known: they
    # would be written as variables, and the voxel size would not be used
    point_radius, spacing_line = {}, None
    if needed(NEXT_RELEASE):
        for key in [k for k in answers if k.startswith("voxel_size ") or k.startswith("detection_point_radius")]:
            value = answers.pop(key)
            fp = ((answers.get("sample_info") or {}).get(key[len("voxel_size "):]) or {}).get("fingerprint")
            if key.startswith("detection_point_radius") and value not in ("auto", -1):
                point_radius["z" if key.endswith("_z") else "xy"] = value
            elif key.startswith("voxel_size ") and value != -1 and fp:
                fp["spacing"], fp["n_calibrated"] = [float(v) for v in value], fp["n_files"]
                spacing_line = "image metadata ({0} of {0} images calibrated)".format(fp["n_files"])

    cfg, report = config_builder.build_config(answers, gpus)

    if spacing_line:
        report["lines"] = [l.replace(spacing_line, "given in the Wizard (no calibration found)") for l in report.get("lines", [])]
    if point_radius and cfg["PROBLEM"]["TYPE"] == "DETECTION":
        r = _set_point_radius(cfg, answers.get("sample_info", {}), point_radius)
        line = "Training targets: disks of radius {} px around each point (chosen in the Wizard)".format(
            r[-1] if len(set(r)) == 1 else "x".join(map(str, r)) + " (z, y, x)")
        lines = [l for l in report.get("lines", []) if not l.startswith("Training targets:")]
        report["lines"] = lines + [line]

    # The normalization a Zoo model imposes was replaced (and a percentile clipping added), which then failed in
    # check_configuration or normalized the images differently
    imposed = {k: v for k, v in restrictions.items() if k.startswith("DATA.NORMALIZATION.")}
    if needed(NEXT_RELEASE) and imposed:
        cfg["DATA"]["NORMALIZATION"].pop("PERC_CLIP", None)
        for key, value in imposed.items():
            config_builder.create_dict_from_key(key, value, cfg)

    model = cfg.get("MODEL", {})
    if needed(None) and model.get("SOURCE") == "bmz" and model.get("BMZ", {}).get("SOURCE_MODEL_ID") in BMZ_MODELS_WITH_UNUSED_PARAMETERS:
        model["FIND_UNUSED_PARAMETERS"] = True
    return cfg, report


def _set_point_radius(cfg, sample_info, chosen):
    """CENTRAL_POINT_DILATION with the radii chosen in the Wizard, as biapy.wizard.detection.point_dilation."""
    from biapy.wizard import common

    det = cfg["PROBLEM"].setdefault("DETECTION", {})
    planned = list(det.get("CENTRAL_POINT_DILATION", [2]))
    xy = planned[-1] if chosen.get("xy", "auto") == "auto" else int(chosen["xy"])
    if cfg["PROBLEM"]["NDIM"] != "3D":
        det["CENTRAL_POINT_DILATION"] = [xy, xy]
        return det["CENTRAL_POINT_DILATION"]
    z = chosen.get("z", "auto")
    if z == "auto":
        raw_fp = common.fingerprint(sample_info, "DATA.TRAIN.PATH", "images")
        spacing = common.choose_spacing(raw_fp, None, 3)[0] if raw_fp else [1.0, 1.0, 1.0]
        z = max(1 if xy else 0, int(round(xy / (spacing[0] / spacing[-1]))))
    det["CENTRAL_POINT_DILATION"] = [int(z), xy, xy]
    return det["CENTRAL_POINT_DILATION"]


def preview(workflow, ndim, raw_folder=None, target_folder=None, target_data_path=""):
    """biapy.wizard.preview.preview (same arguments and result), with the fixes it needs."""
    from biapy.wizard import preview as core

    out = core.preview(workflow, ndim, raw_folder, target_folder, target_data_path=target_data_path)
    # The full-resolution crops where the point size question draws the points were not made
    if needed(NEXT_RELEASE) and workflow == "DETECTION" and raw_folder and target_folder:
        import os

        from biapy.wizard.data_check import preview_image

        is_3d = ndim == "3D"
        for s in out["samples"]:
            if "target_png" not in s or "points_crop" in s:  # no points drawn: large Zarr/H5 images, read in part
                continue
            raw, _ = preview_image(os.path.join(raw_folder, s["raw_name"]), is_3d)
            pts = core._read_points(os.path.join(target_folder, s["target_name"]), is_3d)
            s.update(_points_crop(raw, pts, is_3d, core) or {})
    return out


def _points_crop(raw, pts, is_3d, core):
    """biapy.wizard.preview.points_crop of BiaPy > 3.7.3."""
    import numpy as np

    crop_side, max_dz = 128, 10
    p = pts.astype(int)
    if not is_3d:
        p = np.concatenate([np.zeros((len(p), 1), int), p], axis=1)
        raw = raw[None]
    p = p[((p >= 0) & (p < raw.shape[:3])).all(1)]
    if len(p) == 0:
        return None
    half = crop_side // 2
    rng = np.random.default_rng(0)
    cand = p[rng.choice(len(p), min(len(p), 200), replace=False)]
    ref = p[rng.choice(len(p), min(len(p), 5000), replace=False)]
    near = (np.abs(ref[None, :, 1:] - cand[:, None, 1:]) < half).all(-1)
    near &= np.abs(ref[None, :, 0] - cand[:, None, 0]) <= max_dz
    z, cy, cx = cand[near.sum(1).argmax()]
    h, w = raw.shape[1:3]
    y0, x0 = int(np.clip(cy - half, 0, max(0, h - crop_side))), int(np.clip(cx - half, 0, max(0, w - crop_side)))
    crop = raw[z, y0 : y0 + crop_side, x0 : x0 + crop_side]
    rel = p - [z, y0, x0]
    keep = (np.abs(rel[:, 0]) <= max_dz) & (rel[:, 1:] >= 0).all(1)
    keep &= (rel[:, 1] < crop.shape[0]) & (rel[:, 2] < crop.shape[1])
    return {"points_crop_png": core._png(core._intensity_rgb(crop)), "points_crop": rel[keep].tolist()}


def list_compatible_bmz_models(nchannels="all", callback=None, cache=None, **kwargs):
    """biapy.models.bmz_search.list_compatible_bmz_models (same arguments and result), with the fixes it needs."""
    from biapy.models import bmz_search

    search = bmz_search.list_compatible_bmz_models
    if not needed(NEXT_RELEASE):
        return search(nchannels=nchannels, callback=callback, cache=cache, **kwargs)

    # Its package in the zoo is incomplete, so it can't be downloaded
    from biapy.models import BMZ_MODEL_BLACKLIST

    BMZ_MODEL_BLACKLIST.setdefault("romantic-shark", {
        "all": "its package in the BioImage Model Zoo is incomplete (its documentation.md is missing), so it can't "
        "be downloaded"})

    # The models were not filtered by the channels of the images
    def fits(info):
        return nchannels == "all" or info["opts"]["DATA.PATCH_SIZE"][-1] == nchannels

    def checked(done, total, info, nickname, reason):
        if info is not None and not fits(info):
            info, reason = None, "it expects images with {} channel(s), not {}".format(
                info["opts"]["DATA.PATCH_SIZE"][-1], nchannels
            )
        if callback is not None:
            callback(done, total, info, nickname, reason)

    # The checks were not kept between searches (cache): every model was checked each time
    check, find = bmz_search.check_bmz_model_compatibility, bmz_search.find_bmz_models
    checks, models = (_CompatibilityCache(cache, check) if cache else None), []

    def find_models():
        models.extend(find())
        return models

    if checks is not None:
        bmz_search.check_bmz_model_compatibility = lambda model, workflow_specs=None: checks.check(model, workflow_specs)
        bmz_search.find_bmz_models = find_models
    try:
        found = [m for m in search(callback=checked, **kwargs) if fits(m)]
    finally:
        bmz_search.check_bmz_model_compatibility, bmz_search.find_bmz_models = check, find
    if checks is not None:
        checks.save(models)
    return found


class _CompatibilityCache:
    """biapy.models.bmz_search.CompatibilityCache (BiaPy after 3.7.3), with the check to run."""

    def __init__(self, path, check):
        import json

        import biapy
        from biapy.models import BMZ_MODEL_BLACKLIST

        self.path, self.check_fn = path, check
        self.stamp = [1, biapy.__version__, sorted(BMZ_MODEL_BLACKLIST)]
        self.results = {}
        try:
            with open(path, "r", encoding="utf8") as f:
                data = json.load(f, object_hook=_from_json)
            if data.get("stamp") == self.stamp:
                self.results = data["results"]
        except (OSError, ValueError, KeyError, AttributeError):
            pass

    @staticmethod
    def _model(model):
        nickname = model.get("nickname") or model.get("alias")
        changed = (model.get("raw") or {}).get("last_modified")
        return None if not nickname or changed is None else "{}|{}".format(nickname, changed)

    def check(self, model, workflow_specs):
        name = self._model(model)
        key = None if name is None else "{}|{}|{}|{}".format(
            name, workflow_specs["workflow_type"], workflow_specs["ndim"], workflow_specs["nclasses"])
        if key in self.results:
            return self.results[key]
        compat = self.check_fn(model, workflow_specs=workflow_specs)
        if key is not None:
            self.results[key] = compat
        return compat

    def save(self, models):
        import json
        import os

        live = {self._model(m) for m in models}
        results = {k: v for k, v in self.results.items() if k.rsplit("|", 3)[0] in live}
        try:
            os.makedirs(os.path.dirname(os.path.abspath(self.path)), exist_ok=True)
            with open(self.path + ".tmp", "w", encoding="utf8") as f:
                json.dump({"stamp": self.stamp, "results": _to_json(results)}, f, default=str)
            os.replace(self.path + ".tmp", self.path)
        except OSError:
            pass


def _to_json(value):
    if isinstance(value, tuple):
        return {"__tuple__": [_to_json(v) for v in value]}
    if isinstance(value, list):
        return [_to_json(v) for v in value]
    if isinstance(value, dict):
        return {k: _to_json(v) for k, v in value.items()}
    return value


def _from_json(d):
    return tuple(d["__tuple__"]) if len(d) == 1 and "__tuple__" in d else d


def check_folder(*args, train_channels=None, **kwargs):
    """biapy.wizard.data_check.check_folder (same arguments and result), with the fixes it needs."""
    from biapy.wizard.data_check import check_folder as check

    if not needed(NEXT_RELEASE):
        return check(*args, train_channels=train_channels, **kwargs)

    # The test images were not compared with the training ones, whose number of channels the model takes
    res = check(*args, **kwargs)
    channels = res["data_constraints"].get("DATA.PATCH_SIZE_C")
    if res["error"] or train_channels is None or channels is None or channels == train_channels:
        return res
    return {
        "error": True,
        "error_message": "These images have {} channel(s), but the training images have {}: the model takes {} "
        "channel(s), so these images must have the same number (e.g. 1 for grayscale, 3 for RGB). Convert them and check "
        "them again.".format(channels, train_channels, train_channels),
        "data_constraints": {},
        "sample_info": {},
    }


def predict(biapy, image, **kwargs):
    """BiaPy.predict (same arguments and result), with the fixes it needs."""
    # A second predict() on the same instance failed in classification: the predictions of the previous one were kept
    if needed(NEXT_RELEASE):
        biapy.workflow.all_pred, biapy.workflow.all_gt, biapy.workflow.metrics_per_test_file = [], [], []
    return biapy.predict(image, **kwargs)


def zoo_downloads():
    """
    biapy.models' (bmz_model_files, downloaded_bmz_model, download_bmz_model, BMZ_MODELS_CACHE): keeping zoo models on
    disk to load them without the network. Copies of them before they were in BiaPy.
    """
    if not needed(NEXT_RELEASE):
        from biapy.models import BMZ_MODELS_CACHE, bmz_model_files, download_bmz_model, downloaded_bmz_model

        return bmz_model_files, downloaded_bmz_model, download_bmz_model, BMZ_MODELS_CACHE

    import os

    import pooch
    import requests
    import yaml

    from biapy.models import BMZ_HYPHA_FILES_URL, find_bmz_description_url

    cache, marker_name = os.path.join(str(pooch.os_cache("biapy")), "bmz_models"), "biapy_download.json"

    def bmz_model_files(model_id, timeout=30):
        r = requests.get(BMZ_HYPHA_FILES_URL.format(model_id), timeout=timeout)
        r.raise_for_status()
        return [{"name": str(f["name"]), "size": int(f.get("size") or 0), "last_modified": f.get("last_modified")}
                for f in r.json() if isinstance(f, dict) and f.get("type", "file") == "file"]

    def downloaded_bmz_model(model_id, folder=None):
        folder = folder or os.path.join(cache, model_id)
        try:
            with open(os.path.join(folder, marker_name), "r", encoding="utf8") as f:
                path = os.path.join(folder, yaml.safe_load(f)["description"])
        except Exception:
            return None
        return path if os.path.isfile(path) else None

    def download_bmz_model(model_id, folder=None, callback=None, timeout=30):
        folder = folder or os.path.join(cache, model_id)
        marker = os.path.join(folder, marker_name)
        try:
            files = bmz_model_files(model_id, timeout=timeout)
        except requests.RequestException:
            path = downloaded_bmz_model(model_id, folder)
            if path is None:
                raise
            return path
        try:
            with open(marker, "r", encoding="utf8") as f:
                had = {(x["name"], x["size"], x["last_modified"]) for x in yaml.safe_load(f)["files"]}
        except Exception:
            had = set()
        todo = [f for f in files if (f["name"], f["size"], f["last_modified"]) not in had
                or not os.path.isfile(os.path.join(folder, f["name"]))]

        os.makedirs(folder, exist_ok=True)
        # The files that are there, and the description once they all are (downloaded_bmz_model)
        kept = [f for f in files if f not in todo]

        def save(description=None):
            with open(marker, "w", encoding="utf8") as out:
                yaml.safe_dump({"description": description, "files": kept}, out)

        total, done = sum(f["size"] for f in todo), 0
        base = BMZ_HYPHA_FILES_URL.format(model_id)
        for f in todo:
            part = os.path.join(folder, f["name"] + ".part")
            with requests.get(base + f["name"], stream=True, timeout=timeout) as r:
                r.raise_for_status()
                with open(part, "wb") as out:
                    for chunk in r.iter_content(chunk_size=1 << 20):
                        out.write(chunk)
                        done += len(chunk)
                        if callback is not None:
                            callback(done, total)
            os.replace(part, os.path.join(folder, f["name"]))
            kept.append(f)
            save()
        url = find_bmz_description_url(model_id, timeout=timeout)
        if url is None:
            raise ValueError("The BioImage Model Zoo model '{}' has no description among its files".format(model_id))
        save(url.rsplit("/", 1)[-1])
        return os.path.join(folder, url.rsplit("/", 1)[-1])

    return bmz_model_files, downloaded_bmz_model, download_bmz_model, cache
