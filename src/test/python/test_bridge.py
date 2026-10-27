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
    requests = [
        {"command": "check_data", "args": {"folder": str(folder), "key": "DATA.TRAIN.PATH", "workflow": "SEMANTIC_SEG", "ndim": "2D"}},
        {"command": "check_data", "args": {"folder": str(tmp_path / "nope"), "key": "DATA.TRAIN.PATH", "workflow": "SEMANTIC_SEG", "ndim": "2D"}},
        {"command": "unknown", "args": {}},
    ]
    out = subprocess.run(
        [sys.executable, BRIDGE, "serve"],
        input="".join(json.dumps(r) + "\n" for r in requests),
        capture_output=True, text=True, timeout=300,
    ).stdout
    results = [json.loads(line[len(RESULT_PREFIX):]) for line in out.splitlines() if line.startswith(RESULT_PREFIX)]
    assert len(results) == 3  # one per request, the process survives errors
    assert results[0]["ok"] and not results[0]["result"]["error"]
    assert results[0]["result"]["data_constraints"]["DATA.TRAIN.PATH"] == 3
    assert results[1]["ok"] and results[1]["result"]["error"]
    assert not results[2]["ok"]
