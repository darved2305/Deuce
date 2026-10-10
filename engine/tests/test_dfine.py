import sys
from types import SimpleNamespace

import pytest

from elevate_engine.dfine import run as dfine


def test_person_label_follows_the_official_postprocessor():
    # Deployed postprocessors return before the COCO remap (postprocessor.py @ 956d1709314c).
    deployed = SimpleNamespace(deploy_mode=True, remap_mscoco_category=True)
    eval_mode = SimpleNamespace(deploy_mode=False, remap_mscoco_category=True)
    plain = SimpleNamespace(deploy_mode=False, remap_mscoco_category=False)
    assert dfine.person_label(deployed) == 0
    assert dfine.person_label(eval_mode) == 1
    assert dfine.person_label(plain) == 0


def test_persons_keeps_only_people_above_threshold_best_first():
    # Shapes and values as returned for the first frame of h13g_singles at 40.0 s on a T4.
    labels = [0, 0, 32, 38, 0]
    boxes = [[869, 527, 1064, 885], [1134, 515, 1180, 671], [1595, 744, 1610, 759],
             [839, 635, 892, 660], [10, 10, 20, 20]]  # fmt: skip
    scores = [0.866, 0.922, 0.636, 0.495, 0.2]
    assert dfine.persons(labels, boxes, scores, person=0) == [
        [1134.0, 515.0, 1180.0, 671.0, 0.922],
        [869.0, 527.0, 1064.0, 885.0, 0.866],
    ]


def test_sample_times_at_12_fps_from_50_fps():
    t_ms = list(range(0, 1000, 20))  # 50 fps
    picked = dfine.sample_times(t_ms, 12.0)
    assert len(picked) == 12
    assert all(b - a in (4, 5) for a, b in zip(picked, picked[1:], strict=False))


def test_calflops_stub_fails_loudly_if_profiling_is_ever_called():
    saved = sys.modules.pop("calflops", None)
    try:
        dfine._stub_calflops()
        from calflops import calculate_flops

        with pytest.raises(RuntimeError, match="stubbed"):
            calculate_flops()
    finally:
        sys.modules.pop("calflops", None)
        if saved is not None:
            sys.modules["calflops"] = saved


def test_sampler_rejects_non_positive_fps():
    for bad in (0, -5):
        with pytest.raises(ValueError):
            dfine.sampler(bad)
