import numpy as np
import pytest

from elevate_engine.tracknet.infer import (
    Blob,
    blobs,
    ensemble,
    ensemble_weight,
    gt_center,
    metrics,
    outcome,
)


def official_buffer(window_preds: np.ndarray) -> np.ndarray:
    """The official test_rally 'weight' buffer, transcribed as one batch (test.py 6eda442)."""
    n, seq_len = window_preds.shape[:2]
    weight = ensemble_weight(seq_len)
    batch_i, frame_i = np.arange(seq_len), np.arange(seq_len - 1, -1, -1)
    pad = np.zeros((seq_len - 1, *window_preds.shape[1:]))
    buf = np.concatenate([pad, window_preds])
    out = []
    for b in range(n):
        if b < seq_len - 1:
            out.append(buf[batch_i + b, frame_i].sum(0) / (b + 1))
        else:
            out.append((buf[batch_i + b, frame_i] * weight[:, None, None]).sum(0))
        if b == n - 1:
            buf = np.concatenate([buf, pad])
            for f in range(1, seq_len):
                out.append(buf[batch_i + b + f, frame_i].sum(0) / (seq_len - f))
    return np.stack(out)


def test_weights_are_triangular():
    np.testing.assert_allclose(ensemble_weight(8) * 20, [1, 2, 3, 4, 4, 3, 2, 1])


@pytest.mark.parametrize("n_windows", [1, 3, 7, 8, 20])
def test_ensemble_matches_official_buffer(n_windows):
    rng = np.random.default_rng(n_windows)
    preds = rng.random((n_windows, 8, 6, 5))
    np.testing.assert_allclose(ensemble(preds), official_buffer(preds), rtol=1e-12)


def test_blobs_keep_every_region_largest_box_first():
    heat = np.zeros((288, 512))
    heat[100:103, 200:203] = 0.9  # 3x3 box
    heat[10:15, 20:26] = 0.7  # 6x5 box, larger
    heat[50, 50] = 0.4  # below threshold
    found = blobs(heat)
    assert [(b.cx, b.cy, b.area) for b in found] == [(23, 12, 30), (201, 101, 9)]
    assert found[0].peak == pytest.approx(0.7)


def test_diagonal_pixels_are_one_blob():
    heat = np.zeros((288, 512))
    heat[5, 5] = heat[6, 6] = 1.0
    assert len(blobs(heat)) == 1


def test_gt_center_is_label_at_input_scale():
    # 1280x720 source -> scale 2.5; label (640, 360) -> (256, 144) at input scale.
    assert gt_center(640, 360, 2.5, 2.5) == (256, 144)
    assert gt_center(0, 0, 2.5, 2.5) is None


@pytest.mark.parametrize(
    "pred, gt, expected",
    [
        (None, None, "TN"),
        (None, (10, 10), "FN"),
        (Blob(10, 10, 9, 0.9), None, "FP2"),
        (Blob(13, 10, 9, 0.9), (10, 10), "TP"),
        (Blob(15, 10, 9, 0.9), (10, 10), "FP1"),
    ],
)
def test_outcome_rules(pred, gt, expected):
    assert outcome(pred, gt) == expected


def test_metrics_reproduce_official_run_from_its_counts():
    # Counts from the official TrackNetV3 run (PR #9): 97.5175% accuracy, 98.5608% F1.
    m = metrics({"TP": 9211, "TN": 1356, "FP1": 160, "FP2": 47, "FN": 62})
    assert m["accuracy"] == pytest.approx(0.9751753414544112)
    assert m["f1"] == pytest.approx(0.9856080466534697)


def test_ported_network_has_checkpoint_shape():
    torch = pytest.importorskip("torch")  # torch lives in the gpu group, not in CI
    from elevate_engine.tracknet.model import TrackNet

    state = TrackNet(in_dim=27, out_dim=8).state_dict()
    # Same element count as TrackNet_best.pt's 'model' (logged in PR #2).
    assert sum(v.numel() for v in state.values() if torch.is_tensor(v)) == 11_348_697


def test_tie_picks_the_lowest_blob_like_official_findcontours():
    heat = np.zeros((288, 512))
    heat[20:22, 30:32] = 0.9  # 2x2, higher in the frame
    heat[200:202, 300:302] = 0.9  # 2x2, lower in the frame: official picks this one
    assert (blobs(heat)[0].cx, blobs(heat)[0].cy) == (301, 201)
