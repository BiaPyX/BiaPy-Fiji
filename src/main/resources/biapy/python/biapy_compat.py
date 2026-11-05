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

    cfg, report = config_builder.build_config(answers, gpus)

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


def list_compatible_bmz_models(nchannels="all", callback=None, **kwargs):
    """biapy.models.bmz_search.list_compatible_bmz_models (same arguments and result), with the fixes it needs."""
    from biapy.models.bmz_search import list_compatible_bmz_models as search

    if not needed(NEXT_RELEASE):
        return search(nchannels=nchannels, callback=callback, **kwargs)

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

    return [m for m in search(callback=checked, **kwargs) if fits(m)]


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
