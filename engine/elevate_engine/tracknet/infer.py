"""TrackNet inference as in the official test.py (eval_mode "weight"), at commit 6eda442.

Frames and the background median are resized to 512x288 with PIL's default filter, stacked as
[median, f0..f7] (27 channels, /255), run in every complete 8-frame window (step 1), and the
overlapping heatmaps are combined per frame exactly like the official buffer. Each frame's heatmap
is thresholded at 0.5; every blob is kept as a candidate (engine plan: keep all candidates), and
the blob with the largest bounding box is the official single prediction.
"""

from __future__ import annotations

from collections import deque
from collections.abc import Iterator
from dataclasses import dataclass

import numpy as np
from PIL import Image
from scipy import ndimage

HEIGHT, WIDTH = 288, 512
EIGHT_CONNECTED = np.ones((3, 3), dtype=bool)


def resize_chw(rgb: np.ndarray) -> np.ndarray:
    """(H, W, 3) uint8 RGB -> (3, 288, 512) float64 in 0..255, as the official dataset does."""
    img = Image.fromarray(rgb.astype("uint8")).resize((WIDTH, HEIGHT))
    return np.moveaxis(np.asarray(img), -1, 0).astype(np.float64)


def ensemble_weight(seq_len: int) -> np.ndarray:
    """Official 'weight' mode: triangular positional weights, normalised."""
    w = np.ones(seq_len)
    for i in range((seq_len + 1) // 2):
        w[i] = w[seq_len - i - 1] = i + 1
    return w / w.sum()


def ensemble_stream(windows: Iterator[np.ndarray], n_win: int) -> Iterator[np.ndarray]:
    """Per-frame heatmaps from step-1 windows of shape (L, H, W), yielded in frame order.

    Same as the official buffer: frame s takes position s - w from each window w covering it;
    the first L - 1 frames average what exists, the middle ones use the triangular weights,
    and the last L - 1 frames average the windows that still cover them. Holds only the last
    L windows in memory.
    """
    recent: deque[np.ndarray] = deque()
    weight = None
    seq_len = 0
    for s, win in enumerate(windows):
        if weight is None:
            seq_len = win.shape[0]
            weight = ensemble_weight(seq_len).astype(win.dtype)
        recent.append(win)
        if len(recent) > seq_len:
            recent.popleft()
        first = s - len(recent) + 1  # window index of recent[0]
        terms = [recent[w - first][s - w] for w in range(max(0, s - seq_len + 1), s + 1)]
        if s < seq_len - 1:
            yield sum(terms) / (s + 1)
        else:
            yield sum(
                t * weight[s - w] for t, w in zip(terms, range(s - seq_len + 1, s + 1), strict=True)
            )
    last = n_win - 1
    first = last - len(recent) + 1
    for f in range(1, seq_len):
        s = last + f
        # Official divides by seq_len - f even when a short rally has fewer covering windows.
        covering = range(max(0, s - seq_len + 1), last + 1)
        yield sum(recent[w - first][s - w] for w in covering) / (seq_len - f)


def ensemble(window_preds: np.ndarray) -> np.ndarray:
    """(W, L, H, W') window heatmaps -> (W + L - 1, H, W') per-frame heatmaps."""
    return np.stack(list(ensemble_stream(iter(window_preds), len(window_preds))))


@dataclass(frozen=True)
class Blob:
    cx: int  # bounding-box centre, model input pixels (512x288)
    cy: int
    area: int  # bounding-box width * height, the official ranking key
    peak: float  # max heatmap value inside the box


def blobs(heatmap: np.ndarray, threshold: float = 0.5) -> list[Blob]:
    """Every connected region above threshold, largest bounding box first."""
    labels, _ = ndimage.label(heatmap > threshold, structure=EIGHT_CONNECTED)
    found = []
    for box in ndimage.find_objects(labels):
        ys, xs = box
        x, y, w, h = xs.start, ys.start, xs.stop - xs.start, ys.stop - ys.start
        found.append(Blob(int(x + w / 2), int(y + h / 2), w * h, float(heatmap[box].max())))
    # cv2.findContours lists blobs bottom-first and the official code keeps the first maximum,
    # so on a tie the lowest blob wins; reversing our top-first order reproduces that.
    return sorted(reversed(found), key=lambda b: -b.area)


TYPES = ("TP", "TN", "FP1", "FP2", "FN")


def gt_center(x_px: float, y_px: float, w_scale: float, h_scale: float) -> tuple[int, int] | None:
    """Official ground truth: label -> radius-2.5 disc at input scale -> its box centre."""
    cx, cy = int(x_px / w_scale), int(y_px / h_scale)
    if cx == 0 and cy == 0:
        return None
    vv, uu = np.mgrid[0:HEIGHT, 0:WIDTH]
    disc = (vv - cy) ** 2 + (uu - cx) ** 2 <= 2.5**2
    if not disc.any():
        return None
    ys, xs = np.nonzero(disc)
    x0, y0 = xs.min(), ys.min()
    return int(x0 + (xs.max() - x0 + 1) / 2), int(y0 + (ys.max() - y0 + 1) / 2)


def outcome(pred: Blob | None, gt: tuple[int, int] | None, tolerance: float = 4.0) -> str:
    if pred is None:
        return "TN" if gt is None else "FN"
    if gt is None:
        return "FP2"
    dist = ((pred.cx - gt[0]) ** 2 + (pred.cy - gt[1]) ** 2) ** 0.5
    return "FP1" if dist > tolerance else "TP"


def metrics(counts: dict[str, int]) -> dict[str, float]:
    """Official get_metric."""
    tp, tn, fp1, fp2, fn = (counts[t] for t in TYPES)
    total = tp + tn + fp1 + fp2 + fn
    precision = tp / (tp + fp1 + fp2) if tp + fp1 + fp2 else 0.0
    recall = tp / (tp + fn) if tp + fn else 0.0
    return {
        "accuracy": (tp + tn) / total if total else 0.0,
        "precision": precision,
        "recall": recall,
        "f1": 2 * precision * recall / (precision + recall) if precision + recall else 0.0,
    }
