"""
Makes src/main/resources/biapy/images/workflow_info/watershed.png, the figure of the "Workflow info" window that
shows how BiaPy's watershed builds the instances, from the files of a BiaPy instance segmentation test run with
PROBLEM.INSTANCE_SEG.WATERSHED.DATA_CHECK_MW: True ("foreground + contour" channels).

The figure in the plugin was made with a 2D U-Net trained on DSB2018 for the Wizard's quick training (20 epochs) and
the image 5f9d29d6... of its test set, on the crop with the most touching nuclei:

    python scripts/make_watershed_figure.py <folder with test/x> <results/<job>_1 folder> 5f9d29d6 watershed.png 160

Arguments: data folder (with test/x), results folder of the run, prefix of the image's file name, output PNG and the
size of the crop.
"""
import sys, glob, os, numpy as np, tifffile
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.colors import ListedColormap

W, R, prefix, out, crop = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4], int(sys.argv[5])
name = [os.path.basename(f) for f in glob.glob(W + "/test/x/*.tif") if os.path.basename(f).startswith(prefix)][0]
x = tifffile.imread(W + "/test/x/" + name).squeeze()
if x.ndim == 3: x = x[..., 0] if x.shape[-1] < 5 else x[0]
pred = tifffile.imread(R + "/per_image/" + name).squeeze()
if pred.shape[0] == 2: pred = np.moveaxis(pred, 0, -1)  # F, C last
seeds = tifffile.imread(R + "/watershed/" + name + "/seed_map.tif").squeeze()
surf = tifffile.imread(R + "/watershed/" + name + "/topografic_surface.tif").squeeze()
mask = tifffile.imread(R + "/watershed/" + name + "/growth_mask.tif").squeeze()
inst = tifffile.imread(R + "/per_image_instances/" + name).squeeze()
print(name, x.shape, pred.shape, seeds.shape, surf.shape, mask.shape, inst.shape)
# The crop with the most touching objects
best, by, bx = -1, 0, 0
for y0 in range(0, inst.shape[0] - crop + 1, 16):
    for x0 in range(0, inst.shape[1] - crop + 1, 16):
        c = inst[y0:y0 + crop, x0:x0 + crop]
        t = int(((c[1:] != c[:-1]) & (c[1:] > 0) & (c[:-1] > 0)).sum() + ((c[:, 1:] != c[:, :-1]) & (c[:, 1:] > 0) & (c[:, :-1] > 0)).sum())
        if t > best: best, by, bx = t, y0, x0
sl = (slice(by, by + crop), slice(bx, bx + crop))
rng = np.random.default_rng(3)
colors = np.vstack([[0, 0, 0], 0.35 + 0.65 * rng.random((max(int(inst.max()), int(seeds.max())) + 1, 3))])
labels = ListedColormap(colors)
def lab(a):
    return labels(np.clip(a, 0, len(colors) - 1) / (len(colors) - 1))
panels = [
    ("Input image", x[sl], "gray", None),
    ("Foreground", pred[..., 0][sl], "gray", (0, 1)),
    ("Contour", pred[..., 1][sl], "gray", (0, 1)),
    ("1. Seeds", seeds[sl], "labels", None),
    ("2. Topographic surface", surf[sl], "magma", None),
    ("3. Growth mask", mask[sl], "gray", (0, 1)),
    (None, None, None, None),
    ("Result: objects", inst[sl], "labels", None),
    (None, None, None, None),
]
fig, axes = plt.subplots(3, 3, figsize=(4.6, 4.95), dpi=100)
for ax, (title, img, cmap, rng_) in zip(axes.ravel(), panels):
    ax.axis("off")
    if img is None:
        continue
    if cmap == "labels":
        ax.imshow(lab(img), interpolation="nearest")
    else:
        kw = {"vmin": rng_[0], "vmax": rng_[1]} if rng_ else {}
        ax.imshow(img, cmap=cmap, interpolation="nearest", **kw)
    ax.set_title(title, fontsize=9)
plt.tight_layout(h_pad=0.6, w_pad=0.4)
plt.savefig(out, facecolor="white")
print("crop at", by, bx, "touching", best)
