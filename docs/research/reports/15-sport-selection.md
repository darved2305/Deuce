# Verdict

**Make badminton the primary sport, table tennis the secondary sport, and use a click-to-track catch or bottle-hit clip for the beyond-racket demo.**

Badminton has the best mix of Indian relevance, likely college access, and public shuttle and stroke data. Table tennis offers a strong referee-style story, but its best public event data uses fixed 120 fps cameras. Neither sport has verified phone-camera performance from the public benchmarks reviewed here.

A useful new baseline is **RacketVision**, which covers badminton, table tennis, and tennis. Its published results are from professional broadcasts, and its checkpoint license is **UNVERIFIED**. Use it for experiments, not as evidence that phone footage will work. For the generalization demo, the most dependable phone-first plan is a user tap or box followed by lightweight OpenCV tracking. A laptop can run a learned point tracker such as TAPIR if needed.

All options below are free to download or use locally, subject to the stated licenses and data restrictions. I did not rely on a paid inference API. Some datasets require a request, use non-commercial terms, or do not establish rights to the underlying match footage.

## Ranked sport shortlist

“Public artifacts” counts the representative projects and datasets named here. These are discovery counts, not an exhaustive count of papers or repositories.

| Rank | Sport | Public evidence found | Phone-view evidence and demo practicality | India relevance |
|---|---|---|---|---|
| **1** | **Badminton** | **At least 9** representative sources, including RacketVision, TrackNetV3, TrackNetV4, ShuttleSet, BFMD, CoachAI, BadmintonVision and a smartphone/digital-camera video dataset. | Best first demo if DJSCE provides a court. An 884-video dataset includes indoor smartphone and digital-camera footage, but it is not a complete shuttle-tracking ground-truth set. **DJSCE access is UNVERIFIED.** ([dataset](https://data.mendeley.com/datasets/3sp4xntp34/1), [TrackNetV3](https://github.com/qaz812345/TrackNetV3), [ShuttleSet](https://github.com/wywyWang/CoachAI-Projects/blob/main/ShuttleSet/README.md)) | Ipsos reported 18% interest in following badminton at the 2024 Olympics among urban Indian respondents. This is viewing interest, not participation. ([Ipsos](https://www.ipsos.com/en-in/polarized-views-among-indians-those-interested-not-interested-paris-olympic-games-2024-ipsos-global)) |
| **2** | **Table tennis** | **At least 8** representative sources, including RacketVision, TTNet, OpenTTGames, its extended annotations, TTStroke-21 and P2ANet. | Strong college backup if a table is available. The best event dataset uses fixed 120 fps industrial-camera footage; comparable phone-view event labels were not verified. ([OpenTTGames](https://lab.osai.ai/), [TTNet](https://arxiv.org/html/2004.09927), [P2ANet](https://arxiv.org/abs/2207.12730)) | Ipsos reported 10% Olympic viewing interest. TTFI reports 35 affiliated state units and 25 institutions, evidence of an organised network rather than a participation total. ([Ipsos](https://www.ipsos.com/en-in/polarized-views-among-indians-those-interested-not-interested-paris-olympic-games-2024-ipsos-global), [TTFI](https://www.ttfi.org/pages/emailofstateassociations)) |
| **3** | **Tennis** | **At least 8** representative sources, including RacketVision, TrackNet, TrackNetV4, TennisCourtDetector, TenniSet, THETIS and phone-captured pose data. | Good model ecosystem, but courts are less certain for a same-day DJSCE demo. A 2,000-image smartphone pose dataset is useful for player actions, not ball tracking. ([pose dataset](https://data.mendeley.com/datasets/nv3rpsxhhk/1), [study](https://pmc.ncbi.nlm.nih.gov/articles/PMC11282921/), [TennisCourtDetector](https://github.com/yastrebksv/TennisCourtDetector)) | Ipsos reported 14% Olympic viewing interest, between badminton and table tennis in that survey. ([Ipsos](https://www.ipsos.com/en-in/polarized-views-among-indians-those-interested-not-interested-paris-olympic-games-2024-ipsos-global)) |
| **4** | **Pickleball** | **At least 4** representative sources, including BallNet, two TrackNet-style projects and a Roboflow dataset. | A public tracker exists but is trained on one match and explicitly warns about overfitting. Reliable phone-view data was not verified. ([BallNet](https://huggingface.co/CondadosAI/sportcv-ballnet), [TrackNet pickleball](https://github.com/shivam-d11/Ball-Tracking-PickleBall), [Roboflow dataset](https://universe.roboflow.com/liberin-technologies/pickleball-vision)) | The Indian Pickleball Association advertises 50,000+ players and 27 states. Treat these as federation claims; another association reports different figures. ([IPA](https://www.ipaofficial.com/), [AIPA](https://www.pickleball.in/)) |
| **5** | **Padel** | **At least 3** sources, including PadelTracker100, PadelVic and a CV pipeline. | PadelVic documents footage captured with Galaxy S22+ and iPhone 13 Pro Max cameras, but its data is research-oriented and license details were pending. PadelTracker100 is fixed-camera professional footage; its license field is blank. ([PadelVic](https://github.com/UPC-ViRVIG/PadelVic), [PadelTracker100](https://zenodo.org/records/17020011), [CV pipeline](https://github.com/FilDro/padel-cv-pipeline)) | Padel India publishes its own participation and coaching figures, but I found no independent current participation estimate. ([Padel Association of India](https://padelassociationofindia.com/)) |
| **6** | **Squash** | **At least 3** sources, including SquashAI datasets, CVBASE’06 and squashfeetdata. | Public work supports player positions and court areas more than ball tracking. Public phone-view data and a suitable pretrained ball tracker were not verified. ([SquashAI datasets](https://github.com/squashai/datasets), [CVBASE’06](https://vision.fe.uni-lj.si/cvbase06/downloads.html), [squashfeetdata](https://github.com/sudochris/squashfeetdata)) | Treat as a niche pitch option. A reliable current participation figure was not verified. |
| **Demo only** | **Catch or bottle-hit** | No sport-specific dataset is needed for the generalization claim. Use a manually selected point or object. | Film a short clip at college. Show a trail, confidence, and when tracking is lost or reacquired. This demonstrates tracking beyond racket sports without claiming cricket analytics. | A generalization demo, not a claim that cricket is supported. |

The public-source counts are not literature census results. There is no authoritative count of all papers and repositories per sport, so the exhaustive count is **UNVERIFIED**.

## Exact models, datasets and limits

### Badminton

- **TrackNetV3** provides `TrackNet_best.pt` and `InpaintNet_best.pt`. Its repository states that code and checkpoints are MIT-licensed. Checkpoint byte sizes were not verified. The repository reports 25.11 FPS and 98.56 F1 on its test setup; that does not establish phone speed or accuracy. ([repository and license](https://github.com/qaz812345/TrackNetV3))
- **RacketVision BallTrack** provides `balltrack_best.pth`, about **46.6 MB**. The RacketVision repository code is MIT, but the checkpoint license is **UNVERIFIED**. The paper reports badminton ball mAP 83.1 on its broadcast test split. ([RacketVision paper](https://ojs.aaai.org/index.php/AAAI/article/download/37362/41324), [repository](https://github.com/OrcustD/RacketVision), [checkpoint files](https://huggingface.co/linfeng302/RacketVision-Models/tree/main/checkpoints))
- **ShuttleSet** contains 104 sets, 3,685 rallies and 36,492 strokes, with 18 shot types and court-position information. The repository code is MIT; rights to source match footage and redistributed annotations are **UNVERIFIED**. ([ShuttleSet](https://github.com/wywyWang/CoachAI-Projects/blob/main/ShuttleSet/README.md))
- **BFMD** contains 19 full BWF matches with shuttle trajectories, player poses and shot labels. Its annotations are available for academic research; video rights remain with BWF TV. It is not a product-safe source for demo footage. ([BFMD](https://ning-d.github.io/BFMD-Dataset/))
- **CoachAI Challenge Track 1** labels hit frame, hitter, landing, player positions and shot type. Access and redistribution terms are **UNVERIFIED**. ([task page](https://sites.google.com/view/coachai-challenge-2023/tasks/track1))
- **Phone-view evidence:** a Mendeley dataset contains 884 indoor badminton recordings, including smartphone and digital-camera clips, under CC BY 4.0. Its described annotations do not establish complete shuttle trajectory labels. ([dataset](https://data.mendeley.com/datasets/3sp4xntp34/1))

**Assessment:** Badminton has the strongest combination of tracker, rally and shot-sequence assets. Shuttle visibility, speed and line calls remain the weak points on normal phone video.

### Table tennis

- **TTNet** detects ball events and segments people, table and scoreboard. The paper reports 97.0% event spotting, 2 px ball-location RMSE and under 6 ms on an RTX 2080 Ti, using 120 fps footage. This is a GPU benchmark, not an Android result. Checkpoint size and formal license are **UNVERIFIED**. ([paper](https://arxiv.org/html/2004.09927), [repository](https://github.com/bomlinh/TTNet-Realtime-for-Table-Tennis-Pytorch))
- **OpenTTGames** has five 10–25 minute training videos, seven test clips and 4,271 manually annotated events. Its 1920×1080 video uses a fixed 120 fps industrial camera. Labels include ball coordinates, bounce/net events, and masks for people, table and scoreboard. Its license is CC BY-NC-SA 4.0, so it is free for the hackathon but not an unrestricted commercial SDK. ([dataset](https://lab.osai.ai/))
- **RacketVision BallTrack** also covers table tennis. The paper reports mAP 71.1 on its broadcast test split; the same **46.6 MB** checkpoint’s license is **UNVERIFIED**. ([paper](https://ojs.aaai.org/index.php/AAAI/article/download/37362/41324), [files](https://huggingface.co/linfeng302/RacketVision-Models/tree/main/checkpoints))
- Extended OpenTTGames annotations add stroke, foot/lean and rally-ending labels, but retain CC BY-NC-SA terms. ([annotations](https://github.com/moamal01/table_tennis_data))

**Assessment:** Table tennis is a strong secondary choice for an assisted “AI referee” moment. With an ordinary phone, bounce and net calls are not established by these results.

### Tennis

- **RacketVision BallTrack** reports mAP 81.9 on its broadcast test split. Checkpoint license is **UNVERIFIED**. ([paper](https://ojs.aaai.org/index.php/AAAI/article/download/37362/41324), [files](https://huggingface.co/linfeng302/RacketVision-Models/tree/main/checkpoints))
- **TennisCourtDetector** provides pretrained weights for detecting 14 court keypoints. The dataset has 8,841 1280×720 images from YouTube tournament highlights. Repository code, weights and footage reuse rights are **UNVERIFIED**. ([repository](https://github.com/yastrebksv/TennisCourtDetector))
- A **2,000-image smartphone pose dataset** includes four actions and 18 COCO keypoints, totaling about 508 MB under CC BY 4.0. It supports player-action work, not tennis-ball tracking. ([dataset](https://data.mendeley.com/datasets/nv3rpsxhhk/1), [study](https://pmc.ncbi.nlm.nih.gov/articles/PMC11282921/))
- **TrackNet** and **TrackNetV4** are additional tennis-oriented tracker projects. I did not verify one consistent, commercially clear checkpoint license and checkpoint size across their releases. ([TrackNet](https://github.com/yastrebksv/TrackNet), [TrackNetV4](https://github.com/TrackNetV4/TrackNetV4))

**Assessment:** Keep tennis as an API adapter and a useful model-validation sport. Do not rely on access to a court or claim phone-grade line calls.

### Padel, pickleball and squash

- **PadelTracker100** has about 100,000 labeled frames from two World Padel Tour finals, at 1920×1080 and 30 fps. The Zenodo license field is blank, so reuse rights are **UNVERIFIED**. **PadelVic** includes phone-camera captures but describes research use and says license details were pending. ([PadelTracker100](https://zenodo.org/records/17020011), [PadelVic](https://github.com/UPC-ViRVIG/PadelVic))
- **BallNet** provides `ballnet.pt`, `ballnet.onnx` and `ballnet.fp16.onnx` under Apache 2.0. Its model card says it was trained on 396 annotated frames from one match, reports 47% overall detection rate and warns it overfits that camera, court and lighting. Use only for a controlled same-camera experiment. ([model card](https://huggingface.co/CondadosAI/sportcv-ballnet))
- A separate pickleball TrackNet project publishes code under Apache 2.0 and links weights, but the weights’ license is **UNVERIFIED**. A 6,202-image Roboflow dataset is CC BY 4.0; downloadable offline model weights were not verified. ([TrackNet pickleball](https://github.com/shivam-d11/Ball-Tracking-PickleBall), [dataset](https://universe.roboflow.com/liberin-technologies/pickleball-vision))
- **SquashAI** provides player boxes, T-area positions and court annotations, but I did not verify a public pretrained ball tracker. Repository license does not establish rights to the source match media. ([SquashAI datasets](https://github.com/squashai/datasets))

## Cross-sport pose and court setup

- **Google ML Kit Pose Detection** is an Android option for player landmarks. The current documentation lists `com.google.mlkit:pose-detection:18.0.0-beta5`, 33 landmarks, and base/accurate model app-size impacts of about 10.1/13.3 MB. It does not detect balls, rackets or courts. ([Android documentation](https://developers.google.com/ml-kit/vision/pose-detection/android))
- **RacketVision RacketPose** uses RTMDet-M and RTMPose-M for racket detection and five racket keypoints. Its checkpoint file `best_PCK_epoch_90.pth` is about **106.5 MB**; its license is **UNVERIFIED**. ([repository](https://github.com/OrcustD/RacketVision), [checkpoints](https://huggingface.co/linfeng302/RacketVision-Models/tree/main/checkpoints))
- For court geometry, the most reliable no-training hackathon setup is a user tap on visible corners. **TennisCourtDetector** is a downloadable 14-keypoint detector, but its code, weights and footage rights are **UNVERIFIED**. OpenTTGames offers table masks as data, not a verified ready-to-use phone table detector. ([TennisCourtDetector](https://github.com/yastrebksv/TennisCourtDetector), [OpenTTGames](https://lab.osai.ai/))

## Generalization demo: what runs where

| Method | Phone or laptop | Model, size and license | Recommended role |
|---|---|---|---|
| **Manual tap/box + OpenCV tracking** | Android and laptop are feasible; exact FPS and ball accuracy on the team’s phone are **UNVERIFIED**. | OpenCV **5.0.0** is the current release found. No learned weights. ([release](https://opencv.org/release/opencv-5-0-0/), [CSRT API](https://docs.opencv.org/5.0/extra_modules/classcv_1_1TrackerCSRT.html)) | **Best demo core.** Start from a tap or box, track with optical flow or a local tracker, use color/motion candidates for reacquisition, and show when tracking is lost. |
| **ML Kit Object Detection and Tracking** | Android, on-device. | `com.google.mlkit:object-detection:17.0.2`; built-in categories are coarse and do not include a sports-ball label. ([docs](https://developers.google.com/ml-kit/vision/object-detection/android)) | Optional phone experiment. Do not depend on it to identify a ball automatically. |
| **Apple Vision `VNTrackObjectRequest`** | iPhone only. | Tracks a previously supplied bounding box; no built-in sports-ball detector. ([API](https://developer.apple.com/documentation/vision/vntrackobjectrequest)) | Useful fallback for the team’s iPhone, not Android-first. |
| **TAPIR** | Laptop GPU. No official mobile runtime verified. | `causal_tapir_checkpoint.npy`, about **127 MB**, Apache 2.0; Google reports about 17 FPS at 480×480 on a Quadro RTX 4000. ([repository](https://github.com/google-deepmind/tapnet), [checkpoint](https://storage.googleapis.com/dm-tapnet/causal_tapir_checkpoint.npy)) | Best learned laptop fallback: click the ball and track the selected point. |
| **SAM 2.1** | Laptop/server. No official Android runtime verified. | `sam2.1_hiera_tiny.pt`, **38.9M parameters**; checkpoint byte size not verified. Apache 2.0. The reported 91.2 FPS is on an A100, not a phone. ([repository](https://github.com/facebookresearch/sam2)) | Optional click/box mask propagation for a visual effect. A tiny blurred ball may not segment reliably. |
| **CoTracker3** | Laptop GPU preferred. No official phone runtime verified. | Checkpoints about **102 MB**; CC BY-NC 4.0. ([repository and license](https://github.com/facebookresearch/co-tracker), [files](https://huggingface.co/facebook/cotracker3/tree/main)) | Technically useful, but its non-commercial terms make it a poor SDK dependency. |
| **Grounding DINO / OWL-ViT** | Laptop; no phone-ready performance verified. | `grounding-dino-tiny` about **689 MB**, Apache 2.0; `google/owlvit-base-patch32` about **613 MB**, Apache 2.0. ([Grounding DINO](https://github.com/IDEA-Research/GroundingDINO), [checkpoint](https://huggingface.co/IDEA-Research/grounding-dino-tiny/blob/main/model.safetensors), [OWL-ViT](https://huggingface.co/google/owlvit-base-patch32/blob/main/model.safetensors)) | Text prompt such as “ball” can propose an initial box. These are detectors, not trajectory trackers. |
| **YOLO-World V2.1** | TFLite/ONNX deployment is documented, but actual Android conversion and latency are **UNVERIFIED**. | V2.1 weight sizes range roughly 305–546 MB. The repository README says GPL-3.0, while its package metadata says Apache 2.0; licensing is **CONFLICTED**. ([repository](https://github.com/AILab-CVC/YOLO-World), [package metadata](https://github.com/AILab-CVC/YOLO-World/blob/master/pyproject.toml)) | Do not make it a hackathon or SDK dependency. |
| **Sport-trained TrackNet on catch footage** | Laptop/PyTorch is the supported path found; Android runtime not verified. | Racket-sport weights are sport-specific. RacketVision checkpoint license is **UNVERIFIED**. ([TrackNetV3](https://github.com/qaz812345/TrackNetV3), [RacketVision weights](https://huggingface.co/linfeng302/RacketVision-Models/tree/main/checkpoints)) | You can try it zero-shot, but catch/bottle performance is **UNVERIFIED**. It is weaker evidence of generalization than a user-selected object tracker. |

### Suggested banger clip

Film a bright, contrasting ball against a simple background with a static phone. Import the clip, tap or box the object, and show its path, confidence and any lost/reacquired frames. A bottle-hit clip can show the same pipeline on a different object. Use OpenCV locally on Android; keep TAPIR as a laptop fallback.

Show **pixels per second** or normalized screen speed unless the user supplies a known scale and camera geometry. Converting a single-camera clip into reliable 3D speed or line calls is **UNVERIFIED** for the proposed footage. These are engineering estimates, not benchmark results.

## Recommendation

Build badminton first and table tennis second. Use **TrackNetV3 or RacketVision** to experiment on badminton footage, and use **OpenTTGames/TTNet** as the table-tennis event reference. For the Android app, start with gallery import, manual court-corner setup, ML Kit pose, and tap-to-track for the generalization clip.

Treat full offline post-game analysis on the phone as **UNVERIFIED**. RacketVision’s published environment is Python/PyTorch/CUDA-oriented, and an Android deployment path was not verified. A practical demo split is on-device video selection, UI and lightweight tracking, with sport-specific inference on a laptop over a local connection if needed. That keeps the demo independent of venue internet; it does not prove full offline phone inference. ([RacketVision environment](https://github.com/OrcustD/RacketVision))

## Three biggest risks

1. **Broadcast-to-phone gap:** Most sport-specific tracker scores come from broadcast or fixed high-frame-rate footage, not normal phone clips.
2. **Small, fast objects:** Shuttle and ball blur, occlusion and low pixel size can break tracking, speed and line-call claims.
3. **Access and rights:** DJSCE court/table access is **UNVERIFIED**, and several public datasets or checkpoints have restricted or unclear reuse terms.

## Open questions only the team can answer

1. Which court or table can the team access at DJSCE, and can the team record and retain footage there?
2. Which Android phone and camera modes will be available, including frame rate and a stable camera position?
3. Is a manual tap/court setup and laptop-assisted inference acceptable in the demo, or must every analysis step run offline on the phone?