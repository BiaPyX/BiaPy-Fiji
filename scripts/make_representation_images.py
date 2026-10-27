"""
Draws the images of the instance representation question of the Wizard from real training data: an image, its
instances and the channels each representation makes the network predict, computed by BiaPy itself
(labels_into_channels, with the channel options BiaPy's configuration check sets by default). Each
representation uses a different dataset, chosen for the kind of instances it suits. StarDist's radial
distances are drawn as its authors do (wheels, with biapy/utils/scripts/stardist_wheels.py).

Writes, for each representation of biapy.wizard.instance_seg.REPRESENTATIONS, into
src/main/resources/biapy/wizard/instance_representations/:
  <id>.png       small, shown in the question
  <id>_full.png  large, shown with "Expand image"

Needs BiaPy installed and the datasets (folder given with --datasets):
    python scripts/make_representation_images.py [--datasets /data2/dfranco/datasets]
"""
import argparse
import os
import sys

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
import numpy as np  # noqa: E402

from biapy.data.data_manipulation import read_img_as_ndarray  # noqa: E402
from biapy.wizard.instance_seg import REPRESENTATIONS, representation_to_biapy  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "src", "main", "resources", "biapy", "wizard", "instance_representations")

# Data of each representation: dataset name (caption), raw and label files relative to --datasets, crop
# (y, x, height, width; None for the whole image) and, for 3D, the slices to stack (the middle one is shown)
OMNIPOSE = "instance_seg_paper/Omnipose/train/"
LIZARD = "instance_seg_paper/Lizard/train/"
MITOEM = "harvard_datasets/MitoEM/human/train/"
DSB = "dsb2018/train/"
SNEMI = "snemi/data/train/"
DATA = {
    "F_C": ("MitoEM-H (mitochondria, electron microscopy)", MITOEM + "x/im0200.png", MITOEM + "y/seg0200.tif",
            (3584, 3584, 512, 512)),
    "F_P": ("Lizard (nuclei, H&E histology)", LIZARD + "raw/1354.tif", LIZARD + "label/1354.tif", None),
    "F_Db": ("Omnipose (bacteria, phase contrast)", OMNIPOSE + "raw/s_flex_a22_t12xy5c1_tile2_phase.tif",
             OMNIPOSE + "label/s_flex_a22_t12xy5c1_tile2_phase_masks.tif", (0, 0, 380, 380)),
    "F_S": ("Omnipose (filamentous Caulobacter, phase contrast)", OMNIPOSE + "raw/caulo_29.tif",
            OMNIPOSE + "label/caulo_29_masks.tif", None),
    "Db_R": ("2018 Data Science Bowl (nuclei, fluorescence)",
             DSB + "images/5488e8df5440ee5161fdfae3aeccd2ee396636430065c90e3f1f73870a975991.tif",
             DSB + "masks/5488e8df5440ee5161fdfae3aeccd2ee396636430065c90e3f1f73870a975991.tif", None),
    "F_G": ("Omnipose (bacteria, phase contrast)", OMNIPOSE + "raw/cex_xy3c1_tile_3.tif",
            OMNIPOSE + "label/cex_xy3c1_tile_3_masks.tif", (0, 0, 380, 380)),
    "F_HV": ("Lizard (nuclei, H&E histology)", LIZARD + "raw/3406.tif", LIZARD + "label/3406.tif", None),
    "A": ("SNEMI3D (neurons, electron microscopy)", SNEMI + "x/input-{:05d}.png", SNEMI + "y/labels-{:05d}.png",
          (224, 512, 256, 256), (49, 50, 51)),
}
TITLES = {"F": "Foreground", "C": "Contour", "Db": "Distance to border", "H": "Horizontal distance",
          "V": "Vertical distance"}


def read(path, crop):
    img = read_img_as_ndarray(path, is_3d=False)
    if crop is not None:
        y, x, h, w = crop
        img = img[y:y + h, x:x + w]
    return img


def normalize(raw):
    raw = raw.astype(np.float32)
    lo, hi = np.percentile(raw, (0.5, 99.5))
    raw = np.clip((raw - lo) / max(hi - lo, 1e-6), 0, 1)
    return raw[..., 0] if raw.shape[-1] == 1 else raw[..., :3]


def channel_options(rid, ndim):
    """Channels of the representation and their options as BiaPy sets them (its configuration check)."""
    from biapy.config.config import Config, update_dependencies
    from biapy.engine.check_configuration import check_configuration

    channels, extra = representation_to_biapy(rid, ndim)
    manager = Config("/tmp", "representation_images")
    manager._C.merge_from_list([
        "PROBLEM.TYPE", "INSTANCE_SEG", "PROBLEM.NDIM", "{}D".format(ndim), "TRAIN.ENABLE", True,
        "DATA.TRAIN.PATH", "/tmp", "DATA.TRAIN.GT_PATH", "/tmp", "PROBLEM.INSTANCE_SEG.DATA_CHANNELS", channels,
        "PROBLEM.INSTANCE_SEG.DATA_CHANNELS_EXTRA_OPTS", extra,
        "DATA.PATCH_SIZE", "(32, 256, 256, 1)" if ndim == 3 else "(256, 256, 1)",
    ])
    update_dependencies(manager)
    cfg = manager.get_cfg_defaults()
    check_configuration(cfg, "representation_images", check_data_paths=False)
    return list(cfg.PROBLEM.INSTANCE_SEG.DATA_CHANNELS), dict(cfg.PROBLEM.INSTANCE_SEG.DATA_CHANNELS_EXTRA_OPTS[0])


def biapy_channels(lab, rid):
    """Channels of the representation computed by BiaPy, {name: array}, from (H, W) or (Z, H, W) labels."""
    from biapy.data.pre_processing import channel_physical_offsets, labels_into_channels

    channels, opts = channel_options(rid, lab.ndim)
    out = labels_into_channels(lab[..., None].astype(np.int32), mode=channels, channel_extra_opts=opts)
    offsets = channel_physical_offsets(channels, opts)
    result = {}
    for i, ch in enumerate(channels):
        end = offsets[channels[i + 1]] if i + 1 < len(channels) else out.shape[-1]
        result[ch] = out[..., offsets[ch]:end]
    return result, opts


def instances_over(raw, lab, seed=3, alpha=0.55):
    """The image with each instance in a colour over it."""
    rng = np.random.default_rng(seed)
    colors = np.vstack([[0, 0, 0], rng.uniform(0.25, 1, (int(lab.max()), 3))])
    base = raw if raw.ndim == 3 else np.dstack([raw] * 3)
    fg = (lab > 0)[..., None]
    return np.where(fg, (1 - alpha) * base + alpha * colors[lab], base)


def flows_rgb(gv, gh, fg):
    """Flow direction as hue and magnitude as brightness, as Cellpose shows its flows."""
    from matplotlib.colors import hsv_to_rgb

    angle = (np.arctan2(gv, gh) + np.pi) / (2 * np.pi)
    mag = np.clip(np.sqrt(gv**2 + gh**2), 0, 1) * fg
    return hsv_to_rgb(np.stack([angle, np.ones_like(angle), mag], -1))


def stardist_wheels(ax, raw, lab, n_rays):
    """StarDist's wheels: the polygon of each instance with its rays (biapy/utils/scripts/stardist_wheels.py)."""
    import biapy

    sys.path.insert(0, os.path.join(os.path.dirname(biapy.__file__), "utils", "scripts"))
    import stardist_wheels as sw

    polygons, centers = [], []
    for i in np.unique(lab)[1:]:
        m = lab == i
        cy, cx = sw.pick_center(m)
        angles, dists = sw.radial_distances(m, (cy, cx), n_rays=n_rays)
        polygons.append(sw.polygon_from_rays((cy, cx), angles, dists))
        centers.append((cx, cy))
    base = raw if raw.ndim == 3 else np.dstack([raw] * 3)
    sw.draw_polygons(ax, base * 0.85, polygons, centers, lw_outline=0.9, lw_spoke=0.5, center_size=4)


def panels(rid, raw, lab):
    """[(title, image or drawing function, imshow options)] of a representation."""
    channels, opts = biapy_channels(lab, rid)
    if lab.ndim == 3:  # 3D: the middle slice is shown
        z = lab.shape[0] // 2
        raw, lab = raw[z], lab[z]
        channels = {k: v[z] for k, v in channels.items()}
    items = [("Image", raw, {"cmap": "gray"}), ("Instances", instances_over(raw, lab), {})]
    fg = (lab > 0).astype(np.float32)
    for ch, data in channels.items():
        if ch == "F":
            items.append(("Foreground", data[..., 0], {"cmap": "gray", "vmin": 0, "vmax": 1}))
        elif ch == "C":
            items.append(("Contour", data[..., 0], {"cmap": "gray", "vmin": 0, "vmax": 1}))
        elif ch == "P":
            skeleton = opts.get("P", {}).get("type") == "skeleton"
            points = data[..., 0] > 0
            if not skeleton:  # BiaPy's central points are a few pixels: thickened so they can be seen
                from scipy import ndimage

                points = ndimage.binary_dilation(points, iterations=2)
            items.append(("Skeleton" if skeleton else "Central point", points, {"cmap": "gray", "vmin": 0, "vmax": 1}))
        elif ch == "Db":
            # Instance pixels start above the background's black, so the instances show up to their border
            d = np.clip(data[..., 0], 0, None)
            items.append(("Distance to border", np.where(fg > 0, 0.2 + 0.8 * d / max(d.max(), 1e-6), 0),
                          {"cmap": "magma", "vmin": 0, "vmax": 1}))
        elif ch == "R":
            n_rays = data.shape[-1]
            items.append(("Radial distances ({} rays)".format(n_rays),
                          lambda ax, raw=raw, lab=lab, n=n_rays: stardist_wheels(ax, raw, lab, n), {}))
        elif ch in ("H", "V"):
            items.append((TITLES[ch], data[..., 0], {"cmap": "coolwarm", "vmin": -1, "vmax": 1}))
        elif ch == "A":
            a = opts["A"]
            names = (["Z"] * len(a.get("z_affinities", [])) if lab.ndim == 2 else []) + \
                ["Y"] * len(a.get("y_affinities", [])) + ["X"] * len(a.get("x_affinities", []))
            names = (["Z"] * (data.shape[-1] - len(names))) + names
            for k, axis in enumerate(names):
                items.append(("Affinity {}".format(axis), data[..., k], {"cmap": "gray", "vmin": 0, "vmax": 1}))
    if "Gv" in channels:  # Cellpose: the two gradients in one panel
        items = [it for it in items if it[0] not in ("Gv", "Gh")]
        items.append(("Flows (direction as colour)", flows_rgb(channels["Gv"][..., 0], channels["Gh"][..., 0], fg), {}))
    return items


def draw(items, path, panel_inches, dpi, title_size, caption=None):
    """Panels in a row, keeping the images' aspect ratio, with the titles above and the caption below."""
    first = next(img for _, img, _ in items if not callable(img))
    aspect = first.shape[0] / first.shape[1]
    gap, title_h = 0.04 * panel_inches, 1.9 * title_size / 72
    caption_h = 1.8 * title_size * 0.85 / 72 if caption else 0.03
    width = len(items) * panel_inches + (len(items) - 1) * gap + 0.04
    height = title_h + panel_inches * aspect + caption_h
    fig = plt.figure(figsize=(width, height), dpi=dpi)
    for k, (title, img, kw) in enumerate(items):
        ax = fig.add_axes([(0.02 + k * (panel_inches + gap)) / width, caption_h / height, panel_inches / width,
                           panel_inches * aspect / height])
        if callable(img):
            img(ax)
        else:
            ax.imshow(img, interpolation="nearest", **kw)
        ax.set_title(title, fontsize=title_size, pad=3)
        ax.axis("off")
    if caption:
        fig.text(0.5, 0.25 * caption_h / height, caption, ha="center", va="bottom", fontsize=title_size * 0.85,
                 color="#555555")
    fig.savefig(path)
    plt.close(fig)


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--datasets", default="/data2/dfranco/datasets", help="Folder with the datasets")
    parser.add_argument("--only", nargs="*", help="Representations to draw (all by default)")
    args = parser.parse_args()
    os.makedirs(OUT, exist_ok=True)
    for rid in args.only or REPRESENTATIONS:
        name, raw_path, lab_path, crop = DATA[rid][:4]
        if len(DATA[rid]) > 4:  # 3D: consecutive slices stacked
            zs = DATA[rid][4]
            raw = np.stack([read(os.path.join(args.datasets, raw_path.format(z)), crop) for z in zs])
            lab = np.stack([read(os.path.join(args.datasets, lab_path.format(z)), crop)[..., 0] for z in zs])
            raw = normalize(raw)
            file_name = os.path.basename(raw_path.format(zs[len(zs) // 2]))
        else:
            raw = normalize(read(os.path.join(args.datasets, raw_path), crop))
            lab = read(os.path.join(args.datasets, lab_path), crop)[..., 0]
            file_name = os.path.basename(raw_path)
        lab = lab.astype(np.int64)
        items = panels(rid, raw, lab)
        small = os.path.join(OUT, rid + ".png")
        draw(items, small, 1.6, 150, 7)
        caption = "Training data: {}, {}{}".format(name, file_name[:40], "" if crop is None else " (crop)")
        draw(items, os.path.join(OUT, rid + "_full.png"), 3.2, 125, 12, caption)
        print("Written", os.path.normpath(small), "and _full.png")


if __name__ == "__main__":
    main()
