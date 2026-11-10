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

    monkeypatch.setattr(biapy, "__version__", "3.7.3")
    cfg, _ = biapy_compat.build_config({"model_restrictions": dict(restrictions)})
    assert seen["data_constraints"]["DATA.N_CLASSES"] == 5
    assert cfg["DATA"]["NORMALIZATION"] == {"TYPE": "scale_range", "SCALE_RANGE": {"LOWER_PERC": 1.0}}
    assert cfg["MODEL"]["FIND_UNUSED_PARAMETERS"]
    calls = []
    models = biapy_compat.list_compatible_bmz_models(nchannels=3, callback=lambda *a: calls.append(a))
    assert [m["opts"]["DATA.PATCH_SIZE"][-1] for m in models] == [3]
    assert calls[0][2] is None and "1 channel" in calls[0][4]
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
    assert not biapy_compat.check_folder("f", "DATA.TEST.PATH", "SEMANTIC_SEG", "2D", train_channels=1)["error"]
    assert seen["check_kwargs"]["train_channels"] == 1  # BiaPy compares them itself
    biapy_compat.build_config(json.loads(json.dumps(data_answers)))
    assert seen["answers"]["detection_point_radius"] == 4  # BiaPy uses them itself
    assert seen["answers"]["sample_info"]["DATA.TRAIN.PATH"]["fingerprint"]["spacing"] is None
