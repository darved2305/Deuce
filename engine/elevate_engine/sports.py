"""Sport configuration for the tracking layer (engine plan step 4: process noise per sport).

Values are in source pixels of a 1920x1080 frame. How each badminton value was chosen is
recorded next to it; they were set on the working clip h13g_singles 31-91 s only (D-261010-HW1:
Person 1 may tune there; the held-out 300-360 s segment is never used).
"""

from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class TrackingConfig:
    accel_px_s2: float  # white-acceleration process noise (std)
    meas_sigma_px: float  # TrackNet centre error (std)
    init_speed_sigma_px_s: float  # prior speed uncertainty for a new tracklet
    gate_chi2: float  # Mahalanobis gate, 2 dof
    conf_bonus: float  # cost reduction per unit of heatmap confidence
    max_missed: int  # frames a tracklet survives without a match (1: at most one filled)
    min_observed: int  # tracklets with fewer observed points are dropped as noise
    static_radius_px: float  # radius a static tracklet stays within
    static_fraction: float  # share of its points that must stay within it to count as static


BADMINTON = TrackingConfig(
    accel_px_s2=60_000.0,  # hits and drag change velocity by thousands of px/s within a frame
    meas_sigma_px=6.0,  # about 1.5 px at the 512x288 model input
    init_speed_sigma_px_s=3_000.0,  # covers a clear crossing the frame in about half a second
    gate_chi2=13.8,  # chi-square 2 dof at 99.9%
    conf_bonus=2.0,
    max_missed=1,  # the plan's rule: interpolate at most one slot
    min_observed=4,
    static_radius_px=12.0,  # wall marks and floor spots sit still to within a few px
    static_fraction=0.9,  # tolerate a stray point absorbed from a passing shuttle
)
