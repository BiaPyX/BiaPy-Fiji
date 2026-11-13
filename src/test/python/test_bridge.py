"""
Tests of the Python bridge (biapy_fiji_bridge.py). The wizard's logic is BiaPy's (biapy.wizard) and is tested
in BiaPy (tests/test_wizard.py); here only what the plugin adds: the files shipped and the "serve" mode.
Needs a BiaPy with biapy.wizard in the Python running the tests:
    python -m pytest src/test/python/test_bridge.py
"""
import glob
import json
import os
import re
import subprocess
import sys
from types import SimpleNamespace

import numpy as np
import pytest

HERE = os.path.dirname(os.path.abspath(__file__))
PYTHON_DIR = os.path.join(HERE, "..", "..", "main", "resources", "biapy", "python")
BRIDGE = os.path.join(PYTHON_DIR, "biapy_fiji_bridge.py")
RESULT_PREFIX = "BIAPY_FIJI_RESULT:"


def test_python_files_shipped():
    # The Java side extracts an explicit list of files from the jar (PythonBridge.FILES)
    java = os.path.join(HERE, "..", "..", "main", "java", "io", "github", "biapyx", "fiji", "env", "PythonBridge.java")
    block = re.search(r"FILES = \{([^}]*)\}", open(java).read()).group(1)
    listed = set(re.findall(r'"([^"]+)"', block))
    present = {os.path.relpath(f, PYTHON_DIR) for f in glob.glob(os.path.join(PYTHON_DIR, "**", "*.py"), recursive=True)}
    assert present == listed


def test_serve(tmp_path):
    pytest.importorskip("biapy.wizard")
    import tifffile

    folder = tmp_path / "x"
    folder.mkdir()
    for i in range(3):
        tifffile.imwrite(str(folder / "{}.tif".format(i)), np.zeros((32, 32), np.uint8))
    one = tmp_path / "one"
    one.mkdir()
    tifffile.imwrite(str(one / "stack.tif"), np.zeros((32, 32), np.uint8))
    requests = [
        {"command": "check_data", "args": {"folder": str(folder), "key": "DATA.TRAIN.PATH", "workflow": "SEMANTIC_SEG", "ndim": "2D"}},
        {"command": "check_data", "args": {"folder": str(tmp_path / "nope"), "key": "DATA.TRAIN.PATH", "workflow": "SEMANTIC_SEG", "ndim": "2D"}},
        {"command": "unknown", "args": {}},
        # A time-lapse to track: its frames (one file per frame), and a folder with a single file
        {"command": "check_data", "args": {"folder": str(folder), "key": "DATA.TEST.PATH", "workflow": "INSTANCE_SEG", "ndim": "2D", "tracking": True}},
        {"command": "check_data", "args": {"folder": str(one), "key": "DATA.TEST.PATH", "workflow": "INSTANCE_SEG", "ndim": "2D", "tracking": True}},
    ]
    out = subprocess.run(
        [sys.executable, BRIDGE, "serve"],
        input="".join(json.dumps(r) + "\n" for r in requests),
        capture_output=True, text=True, timeout=300,
    ).stdout
    results = [json.loads(line[len(RESULT_PREFIX):]) for line in out.splitlines() if line.startswith(RESULT_PREFIX)]
    assert len(results) == 5  # one per request, the process survives errors
    assert results[0]["ok"] and not results[0]["result"]["error"]
    assert results[0]["result"]["data_constraints"]["DATA.TRAIN.PATH"] == 3
    assert results[1]["ok"] and results[1]["result"]["error"]
    assert not results[2]["ok"]
    assert not results[3]["result"]["error"]
    assert "one image per frame" in results[4]["result"]["error_message"]


def test_compat_fixes(monkeypatch):
    """biapy_compat applies its fixes to BiaPy versions older than the release that includes them, and only to those."""
    pytest.importorskip("biapy.wizard")
    import biapy
    from biapy.models import bmz_search
    from biapy.wizard import config_builder

    sys.path.insert(0, PYTHON_DIR)
    import biapy_compat

    seen = {}

    def fake_build_config(answers, gpus=None):
        seen["data_constraints"] = dict(answers.get("data_constraints", {}))
        seen["answers"] = json.loads(json.dumps(answers))
        norm = {"TYPE": "zero_mean_unit_variance", "PERC_CLIP": {"ENABLE": True, "LOWER_PERC": 0.2, "UPPER_PERC": 99.8}}
        return {"PROBLEM": {"TYPE": "DETECTION", "NDIM": "3D"}, "DATA": {"NORMALIZATION": norm},
                "MODEL": {"SOURCE": "bmz", "BMZ": {"SOURCE_MODEL_ID": "gorgeous-whale"}}}, {"lines": []}

    def fake_search(callback=None, **kwargs):
        models = [{"opts": {"DATA.PATCH_SIZE": (8, 8, c)}} for c in (1, 3)]
        for i, m in enumerate(models):
            callback(i + 1, len(models), m, "m{}".format(i), "")
        return models

    def fake_check_folder(folder, key, workflow, ndim, **kwargs):
        seen["check_kwargs"] = kwargs
        return {"error": False, "error_message": "", "data_constraints": {"DATA.PATCH_SIZE_C": 3}, "sample_info": {}}

    from biapy.wizard import data_check

    monkeypatch.setattr(data_check, "check_folder", fake_check_folder)
    monkeypatch.setattr(config_builder, "build_config", fake_build_config)
    monkeypatch.setattr(bmz_search, "list_compatible_bmz_models", fake_search)
    restrictions = {"DATA.N_CLASSES": 5, "DATA.NORMALIZATION.TYPE": "scale_range",
                    "DATA.NORMALIZATION.SCALE_RANGE.LOWER_PERC": 1.0}

    from biapy.models import BMZ_MODEL_BLACKLIST

    monkeypatch.delitem(BMZ_MODEL_BLACKLIST, "romantic-shark", raising=False)  # as in 3.7.3
    monkeypatch.setattr(biapy, "__version__", "3.7.3")
    cfg, _ = biapy_compat.build_config({"model_restrictions": dict(restrictions)})
    assert seen["data_constraints"]["DATA.N_CLASSES"] == 5
    assert cfg["DATA"]["NORMALIZATION"] == {"TYPE": "scale_range", "SCALE_RANGE": {"LOWER_PERC": 1.0}}
    assert cfg["MODEL"]["FIND_UNUSED_PARAMETERS"]
    calls = []
    models = biapy_compat.list_compatible_bmz_models(nchannels=3, callback=lambda *a: calls.append(a))
    assert [m["opts"]["DATA.PATCH_SIZE"][-1] for m in models] == [3]
    assert calls[0][2] is None and "1 channel" in calls[0][4]
    assert "incomplete" in BMZ_MODEL_BLACKLIST["romantic-shark"]["all"]
    used = SimpleNamespace(workflow=SimpleNamespace(all_pred=np.array(2), all_gt=[], metrics_per_test_file=[{}]),
                           predict=lambda image, **kw: (image, kw))
    assert biapy_compat.predict(used, "img", return_prediction=False) == ("img", {"return_prediction": False})
    assert used.workflow.all_pred == [] and used.workflow.metrics_per_test_file == []  # left by the previous image
    res = biapy_compat.check_folder("f", "DATA.TEST.PATH", "SEMANTIC_SEG", "2D", train_channels=1)
    assert res["error"] and "training images have 1" in res["error_message"]
    assert "train_channels" not in seen["check_kwargs"]  # unknown to that BiaPy
    assert not biapy_compat.check_folder("f", "DATA.TEST.PATH", "SEMANTIC_SEG", "2D", train_channels=3)["error"]
    data_answers = {"voxel_size DATA.TRAIN.PATH": [2.0, 0.5, 0.5], "detection_point_radius": 4, "detection_point_radius_z": "auto",
                    "sample_info": {"DATA.TRAIN.PATH": {"fingerprint": {"kind": "images", "n_files": 2, "n_calibrated": 0, "spacing": None}}}}
    cfg, report = biapy_compat.build_config(json.loads(json.dumps(data_answers)))
    assert not any(k.startswith(("voxel_size", "detection_point")) for k in seen["answers"])  # unknown to that BiaPy
    fp = seen["answers"]["sample_info"]["DATA.TRAIN.PATH"]["fingerprint"]
    assert fp["spacing"] == [2.0, 0.5, 0.5] and fp["n_calibrated"] == 2
    assert cfg["PROBLEM"]["DETECTION"]["CENTRAL_POINT_DILATION"] == [1, 4, 4]  # Z scaled by the voxel size
    assert "(chosen in the Wizard)" in report["lines"][-1]

    # A release with the fixes: BiaPy's own result is kept (the models it lists are not filtered again)
    monkeypatch.setattr(biapy, "__version__", biapy_compat.NEXT_RELEASE)
    cfg, _ = biapy_compat.build_config({"model_restrictions": dict(restrictions)})
    assert "DATA.N_CLASSES" not in seen["data_constraints"]
    assert cfg["DATA"]["NORMALIZATION"]["TYPE"] == "zero_mean_unit_variance"
    assert cfg["MODEL"]["FIND_UNUSED_PARAMETERS"]  # not fixed in BiaPy
    assert len(biapy_compat.list_compatible_bmz_models(nchannels=3, callback=lambda *a: None)) == 2
    used.workflow.all_pred = "kept"
    biapy_compat.predict(used, "img")
    assert used.workflow.all_pred == "kept"  # BiaPy resets it itself
    assert not biapy_compat.check_folder("f", "DATA.TEST.PATH", "SEMANTIC_SEG", "2D", train_channels=1)["error"]
    assert seen["check_kwargs"]["train_channels"] == 1  # BiaPy compares them itself
    biapy_compat.build_config(json.loads(json.dumps(data_answers)))
    assert seen["answers"]["detection_point_radius"] == 4  # BiaPy uses them itself
    assert seen["answers"]["sample_info"]["DATA.TRAIN.PATH"]["fingerprint"]["spacing"] is None


def test_compat_search_cache(monkeypatch, tmp_path):
    """The zoo search keeps its checks in a file (BiaPy's own cache after 3.7.3, biapy_compat's before)."""
    pytest.importorskip("biapy.wizard")
    import biapy
    from biapy.models import bmz_search

    sys.path.insert(0, PYTHON_DIR)
    import biapy_compat

    models = [{"nickname": n, "raw": {"last_modified": 1, "manifest": {"name": n}}} for n in ("a", "b")]
    checked = []

    def check(model, workflow_specs=None):
        checked.append(model["nickname"])
        return {"error": False, "reason_message": "", "opts": {"DATA.PATCH_SIZE": (8, 8, 1)},
                "workflow_info": {"workflow_type": "SEMANTIC_SEG", "ndim": "2D"}}

    monkeypatch.setattr(bmz_search, "find_bmz_models", lambda: models)
    monkeypatch.setattr(bmz_search, "check_bmz_model_compatibility", check)
    versions = ["3.7.3"] + ([biapy_compat.NEXT_RELEASE] if hasattr(bmz_search, "CompatibilityCache") else [])
    for version in versions:
        monkeypatch.setattr(biapy, "__version__", version)
        cache = str(tmp_path / version / "bmz.json")
        checked.clear()
        first = biapy_compat.list_compatible_bmz_models(nchannels=1, cache=cache)
        assert sorted(checked) == ["a", "b"] and len(first) == 2
        checked.clear()
        again = biapy_compat.list_compatible_bmz_models(nchannels=1, cache=cache)
        assert checked == [] and [m["nickname"] for m in again] == [m["nickname"] for m in first], version
        assert again[0]["opts"]["DATA.PATCH_SIZE"] == (8, 8, 1)
        assert bmz_search.check_bmz_model_compatibility is check  # left as it was


def test_apply_model_device_and_channels(monkeypatch):
    """A process that built a model on a device asks to be restarted for another; the raw channels are counted."""
    pytest.importorskip("biapy.wizard")
    from biapy.config.config import Config

    sys.path.insert(0, PYTHON_DIR)
    import biapy_fiji_bridge as bridge

    monkeypatch.setitem(bridge._applied, "device", "2")
    assert bridge.cmd_load_model({"model": "m.pth", "out_dir": "/tmp", "gpu": ""}) == {"restart": True}
    with pytest.raises(RuntimeError):
        bridge._model({"model": "m.pth", "out_dir": "/tmp", "gpu": "3"})

    cfg = Config("/tmp", "x").get_cfg_defaults()
    cfg.PROBLEM.NDIM = "2D"
    cfg.PROBLEM.INSTANCE_SEG.DATA_CHANNELS = ["F", "C", "M"]
    assert [(o["channel"], o["count"]) for o in bridge._instance_outputs(cfg)] == [("F", 1), ("C", 1), ("M", 1)]
    cfg.PROBLEM.INSTANCE_SEG.DATA_CHANNELS = ["F", "R"]
    cfg.PROBLEM.INSTANCE_SEG.DATA_CHANNELS_EXTRA_OPTS = [{"R": {"nrays": 16}}]
    cfg.DATA.N_CLASSES = 3
    assert [(o["channel"], o["count"]) for o in bridge._instance_outputs(cfg)] == [("F", 1), ("R", 16), ("class", 3)]
    # A zoo model described from its search entry, without downloading it
    zoo = {"workflow_type": "INSTANCE_SEG", "ndim": "3D", "imposed_vars": {
        "PROBLEM.INSTANCE_SEG.DATA_CHANNELS": ["F", "C", "M"], "DATA.PATCH_SIZE": [80, 80, 80, 3]}}
    d = bridge.cmd_describe_model({"model": "x", "zoo": zoo})
    assert (d["channels"], d["ndim"], d["post_processing"]) == (3, "3D", False)
    assert [o["channel"] for o in d["instance_outputs"]] == ["F", "C", "M"]


def test_compat_zoo_downloads(monkeypatch, tmp_path):
    """A zoo model kept on disk (BiaPy's download_bmz_model after 3.7.3, biapy_compat's copy before)."""
    pytest.importorskip("biapy.wizard")
    import requests

    import biapy.models as models

    sys.path.insert(0, PYTHON_DIR)
    import biapy_compat

    files = {"rdf.yaml": b"type: model", "weights.pt": b"0" * 10}
    got = []

    class Response:
        def __init__(self, url):
            got.append(url.rsplit("/", 1)[-1])

        def __enter__(self):
            return self

        def __exit__(self, *a):
            pass

        def raise_for_status(self):
            pass

        def json(self):
            return [{"type": "file", "name": n, "size": len(d), "last_modified": 1} for n, d in files.items()]

        def iter_content(self, chunk_size):
            yield files[got[-1]]

    monkeypatch.setattr(requests, "get", lambda url, stream=False, timeout=30: Response(url))
    monkeypatch.setattr(models, "find_bmz_description_url", lambda model_id, timeout=30: "https://x/files/rdf.yaml")
    listed, downloaded, download, cache = biapy_compat.zoo_downloads()
    assert sum(f["size"] for f in listed("m")) == 21 and cache.endswith("bmz_models")
    folder = str(tmp_path / "m")
    path = download("m", folder)
    assert path == os.path.join(folder, "rdf.yaml") and downloaded("m", folder) == path
    got.clear()
    download("m", folder)
    assert got == [""]  # only the list of files
