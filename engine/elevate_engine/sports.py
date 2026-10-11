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


@dataclass(frozen=True)
class StabiliseConfig:
    """Handheld-camera stabilisation (elevate_engine.stabilise). Pixel values are in the 512x288
    model-input space unless the name says source. Off by default: nothing changes until a run
    or deploy turns `enabled` on; even then a window is only touched past `min_motion_px`."""

    enabled: bool = False
    min_motion_px: float = 0.5  # gate: window camera_motion_px (source px) above this stabilises
    # camera_motion_px is the median shift between consecutive frames, so it scales with 1/fps:
    # steady h13g (50 fps) measures 0.03, the handheld 20251109_101719 clip (60 fps) 0.5-0.8.
    reanchor_every: int = 30  # frames between direct ORB re-anchors to the reference
    max_corners: int = 300  # KLT features per frame pair
    lk_win: int = 21
    lk_levels: int = 3
    fb_tol_px: float = 0.5  # forward/backward LK consistency
    ransac_px: float = 2.0  # RANSAC reprojection threshold for KLT steps
    min_inliers: int = 20  # KLT step accepted with at least this many inliers
    orb_features: int = 1500
    orb_min_inliers: int = 30
    orb_ransac_px: float = 3.0
    reanchor_max_jump_px: float = 60.0  # ORB anchor vs chained path, at the frame corners
    refine_max_px: float = 25.0  # largest residual correction a per-frame refinement may make
    max_step_px: float = 40.0  # a one-frame corner displacement above this is implausible
    max_failed_fraction: float = 0.3  # more unrecovered frames than this: left unstabilised
    person_pad_px: int = 6  # mask inflation around each D-FINE person box
    person_window_ms: int = 250  # a person box masks frames within this time of its sample
    residual_samples: int = 60  # frame pairs used to log the residual motion (0: all)
    background_samples: int = 60  # frames used for the per-window valid-pixel median


STABILISE = StabiliseConfig()
