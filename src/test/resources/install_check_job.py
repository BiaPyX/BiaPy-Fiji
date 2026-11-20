"""
Writes a tiny 2D instance segmentation dataset (random disks) and the configuration of a short BiaPy job on it (train
1 epoch with data-loader workers, then test) and an image to apply its model to, for InstallCheck.
Usage: install_check_job.py <folder>
"""
import os
import sys

import numpy as np
import tifffile
import yaml

root = sys.argv[1]
rng = np.random.default_rng(0)
yy, xx = np.mgrid[:128, :128]
for split, n in (("train", 6), ("test", 2)):
    os.makedirs(os.path.join(root, split, "x"), exist_ok=True)
    os.makedirs(os.path.join(root, split, "y"), exist_ok=True)
    for i in range(n):
        label = np.zeros((128, 128), np.uint16)
        for j in range(8):
            cy, cx, r = rng.integers(12, 116), rng.integers(12, 116), rng.integers(5, 11)
            label[(yy - cy) ** 2 + (xx - cx) ** 2 <= r * r] = j + 1
        image = ((label > 0) * 150 + rng.normal(50, 20, label.shape)).clip(0, 255).astype(np.uint8)
        tifffile.imwrite(os.path.join(root, split, "x", f"im{i}.tif"), image)
        tifffile.imwrite(os.path.join(root, split, "y", f"im{i}.tif"), label)
np.save(os.path.join(root, "image.npy"), image[..., None])  # for Apply model: (y, x, c)

# Like BiaPy's 2D instance segmentation template, made small
cfg = {
    "SYSTEM": {"NUM_CPUS": -1, "NUM_WORKERS": 2},
    "PROBLEM": {"TYPE": "INSTANCE_SEG", "NDIM": "2D", "INSTANCE_SEG": {"DATA_CHANNELS": "BC"}},
    "DATA": {
        "PATCH_SIZE": "(64, 64, 1)",
        "TRAIN": {"PATH": os.path.join(root, "train", "x"), "GT_PATH": os.path.join(root, "train", "y"),
                  "IN_MEMORY": True},
        "VAL": {"SPLIT_TRAIN": 0.2},
        "TEST": {"PATH": os.path.join(root, "test", "x"), "GT_PATH": os.path.join(root, "test", "y"),
                 "IN_MEMORY": True, "LOAD_GT": True, "PADDING": "(16, 16)"},
    },
    "AUGMENTOR": {"ENABLE": True, "RANDOM_ROT": True, "VFLIP": True, "HFLIP": True},
    "MODEL": {"ARCHITECTURE": "unet", "FEATURE_MAPS": [8, 16, 32], "LOAD_CHECKPOINT": False},
    "TRAIN": {"ENABLE": True, "OPTIMIZER": "ADAMW", "LR": 1.0e-3, "BATCH_SIZE": 2, "EPOCHS": 1, "PATIENCE": -1},
    "LOSS": {"CLASS_REBALANCE": True},
    "TEST": {"ENABLE": True, "AUGMENTATION": False, "FULL_IMG": False},
}
with open(os.path.join(root, "job.yaml"), "w") as f:
    yaml.safe_dump(cfg, f)
