# Track 16: seamless cloud pipeline

**Verdict:** Use the phone to play the selected original immediately, upload independently decodable video segments directly to Cloudflare R2, and send each completed segment to a warm Modal GPU worker. Save analysis as timestamped data, then overlay it on the original video in Media3. This lets results arrive before the full match finishes uploading.

A blanket promise of “analysis in seconds” is not supported. On 4G, even the first few seconds of a high-bitrate video can take several seconds to upload, before worker startup and inference. A full 20-minute match can take tens of minutes to upload and process. Make the app feel responsive with immediate local playback, real upload progress, partial results and a clear offline state.

## 1. India upload evidence and timing

The requested **2025–26 Jio/Airtel median split by 4G/5G in Mumbai is UNVERIFIED**. I could not verify a public source with that exact operator, technology and location breakdown. The sources that are available report averages:

- Opensignal’s February 2026 report says its Upload Speed Experience is an **average**. It reports Mumbai averages of 8.9 Mbps for Airtel and 11.0 Mbps for Jio across their mobile networks. [Opensignal India, February 2026](https://insights.opensignal.com/reports/2026/02/india/mobile-network-experience)
- TRAI’s June 2026 Mumbai drive test reports mixed 4G/5G route averages of 25.92 Mbps for Airtel and 40.92 Mbps for Jio. These are route-test results, not medians or guarantees for a user’s location. [TRAI’s Independent Drive Test report list](https://trai.gov.in/release-publication/qos-reports/idt-reports) and [Mumbai test annex](https://static.pib.gov.in/WriteReadData/specificdocs/documents/2026/jul/doc2026720926501.pdf)
- The best per-technology Mumbai measurements I found are older: TRAI’s November 2025 hotspot averages were Airtel 4G 7.11 Mbps and 5G 36.85 Mbps; Jio 4G 12.21 Mbps and 5G 34.13 Mbps. These were stationary hotspots, not medians. [TRAI’s January 2026 Mumbai findings](https://trai.gov.in/sites/default/files/2026-01/PR_No.06of2026.pdf)
- Ookla’s public India index reports country-level median speeds, but I could not verify the requested operator-by-technology Mumbai series there. [Ookla Speedtest Global Index, India](https://ooklaserver.org/global-index/india)

The following upload estimates use the older per-technology TRAI hotspot averages because they provide the needed 4G/5G split. They use decimal file sizes and the formula `bytes × 8 / Mbps`. They are idealized transfer times with no protocol overhead, retries, network variation or time spent preparing segments.

| Match assumption | Upload size | Airtel 4G, 7.11 Mbps | Jio 4G, 12.21 Mbps | Airtel 5G, 36.85 Mbps | Jio 5G, 34.13 Mbps |
|---|---:|---:|---:|---:|---:|
| 5 minutes, smaller file | 200 MB | 3:45 | 2:11 | 0:43 | 0:47 |
| 5 minutes, larger file | 1 GB | 18:45 | 10:55 | 3:37 | 3:54 |
| 20 minutes, smaller file | 800 MB | 15:00 | 8:44 | 2:54 | 3:08 |
| 20 minutes, larger file | 2 GB | 37:30 | 21:50 | 7:14 | 7:49 |

These are arithmetic estimates from observed averages, not median or p95 service promises. The app should calculate its ETA from the user’s actual upload throughput.

For a first partial result, an 8-second segment would be about 5.3 MB for a 200 MB, 5-minute video, or 13.3 MB for a 2 GB, 20-minute video, assuming uniform bitrate. At the above Airtel speeds, those segments alone take roughly 6 and 15 seconds on 4G, or 1 and 3 seconds on 5G. Add segment preparation, worker startup, model initialization and inference. **Seconds-to-first-result is plausible on a warm worker and good 5G; it is not a safe 4G guarantee.**

## 2. Upload less without damaging ball evidence

### Recommended default: remux segments, do not re-encode

Keep the original in the gallery. On Android, create short MP4 segment objects by copying already encoded samples where possible, then upload each segment directly. Align segment starts to sync samples and include the source presentation-time range in the manifest. Keep audio if it may support contact or rally cues.

Android’s `MediaExtractor` exposes sample timestamps and sync-sample flags, while `MediaMuxer` can write encoded samples into a new container. That makes a no-reencode segmenter a reasonable implementation path, but it still needs a phone-level prototype for actual gallery files, timestamps, audio and codecs. [MediaExtractor API](https://developer.android.com/reference/android/media/MediaExtractor) and [MediaMuxer API](https://developer.android.com/reference/android/media/MediaMuxer)

Use **4–8 seconds per segment** as a starting point. Overlap adjacent analysis windows by about 0.5–1 second to reduce missed events at boundaries. Preserve the original global presentation timestamps in the manifest so the server can join segments into one match timeline. These segment and overlap sizes are design estimates, not measured results.

### Re-encoding and two-pass proxy

Jetpack Media3 Transformer 1.11.1 uses `MediaCodec` for hardware video encoding and supports fragmented MP4 output. However, fragmented MP4 output is still one output file; it does not by itself create independently uploadable R2 objects. Transformer’s throughput can be limited by the device’s hardware encoder. **Encode speeds on the team’s Galaxy S23 FE Exynos 2200 and Galaxy A55 Exynos 1480 are UNVERIFIED.** [Media3 1.11.1 release notes](https://developer.android.com/jetpack/androidx/releases/media3) and [Transformer supported formats](https://developer.android.com/media/media3/transformer/supported-formats)

I would not make same-resolution HEVC re-encoding the required upload path. There is no verified “safe” HEVC bitrate for detecting a small ball or shuttle, nor a measured encode speed for those two phones. Do not assume that preserving the resolution and frame rate preserves enough visual detail for ball tracking.

A proxy-first pass can make player and rally analysis available sooner, followed by a full-quality ball pass. It costs phone time and adds upload bytes if the original is also sent. At an illustrative 1.5 Mbps proxy bitrate, a 5-minute proxy is about 56 MB and a 20-minute proxy about 225 MB. That size is only a calculation; its visual quality and encode time are UNVERIFIED. If used, treat proxy results as provisional and label the later full-quality ball track as an upgrade.

Avoid cropping on the phone by default. A bad crop can cut out players or the ball, and the informal clips may not have a court boundary. Crop on the server when the user has supplied a region of interest, keeping the original intact.

## 3. Ingestion and backend design

Cloudflare R2 is a good free-tier target for the upload objects. It supports direct presigned uploads. Its multipart upload supports files up to 5 TiB, up to 10,000 parts, and 5 MiB–5 GiB per part; only failed parts need retrying. **Multipart parts do not make a readable completed object available to the analysis worker until `CompleteMultipartUpload`.** R2 documents the upload as resumable and says incomplete multipart uploads are automatically aborted after seven days. [R2 upload documentation](https://developers.cloudflare.com/r2/objects/upload-objects/)

Use two upload paths:

1. **Progressive analysis:** upload each 4–8 second segment as its own R2 object with a presigned `PUT`. A completed segment can then be read and analyzed immediately.
2. **Optional full-source archive:** upload the entire original with resumable multipart. The worker can use this after completion, but it cannot unlock segment-by-segment processing.

For full-source multipart, 32–64 MiB parts are a reasonable starting estimate. Persist the R2 `uploadId`, part numbers, ETags and completed segment hashes on the phone. The app should be able to request a fresh presigned URL for the same unfinished part. Do not route video through a Cloudflare Worker: Workers Free has a 100 MB request-body limit and 10 ms CPU per HTTP request. Use Workers for match creation, upload authorization and small completion callbacks only. [Workers limits](https://developers.cloudflare.com/workers/platform/limits/)

Create the R2 bucket with an `apac` location hint. Cloudflare describes location hints as best-effort rather than a location guarantee. [R2 data location](https://developers.cloudflare.com/r2/reference/data-location/)

### Sequence

```mermaid
sequenceDiagram
    participant App as Android app
    participant API as Cloudflare Worker API
    participant R2 as Cloudflare R2
    participant GPU as Modal GPU worker
    participant DB as Supabase match state
    participant UI as Match timeline

    App->>App: Select gallery video; play local original
    App->>API: Create match and request segment upload URL
    API->>DB: Save match manifest and segment status
    API-->>App: matchId, signed URL, upload policy

    loop Each segment
        App->>R2: PUT completed segment object
        App->>API: Report segment sequence, PTS range, hash
        API->>DB: Mark segment ready; persist dispatch status
        API->>GPU: Dispatch segment job
        GPU->>R2: GET completed segment
        GPU->>GPU: Decode, track ball, detect and track players
        GPU->>R2: PUT compact timestamped result JSON
        GPU->>DB: Publish rally or track-ready event
        DB-->>UI: Realtime update; polling is fallback
        UI->>App: Draw overlay on local original video
    end

    App->>API: Finalize once all segments are uploaded
    GPU->>DB: Publish final match summary and completion
```

Expose one canonical match record for the app and developer API. An API surface could be:

- `POST /v1/matches`
- `POST /v1/matches/{id}/segments/{seq}/upload-url`
- `POST /v1/matches/{id}/segments/{seq}/complete`
- `GET /v1/matches/{id}/events?after={eventSeq}`
- `GET /v1/matches/{id}/analysis`

Each event should carry `matchId`, a monotonic event sequence, segment sequence, original PTS range, model version, confidence and a reference to the result JSON. Example event names: `segment.ready`, `rally.ready`, `ball_track.ready`, `analysis.complete`. Send small status and result-object references through Realtime. Store the actual tracks in R2.

## 4. Progressive updates and playback

| Option | Good fit | Recommendation |
|---|---|---|
| Supabase Realtime | App progress events and reconnecting after an interruption | Main app path. The Free plan allows 2 million messages/month and 200 peak connections; current limits show 100 messages/second. Store durable state in tables and send small progress events, not per-frame coordinates. [Supabase Realtime pricing](https://supabase.com/docs/guides/realtime/pricing) and [limits](https://supabase.com/docs/guides/realtime/limits) |
| Polling | Recovery when realtime cannot connect | Required fallback. Poll status every few seconds while the match screen is open; back off in the background. |
| Modal SSE | A direct developer API that streams an active job | Modal supports FastAPI `StreamingResponse` with `text/event-stream`. Make reconnects read from the durable event record so a dropped connection does not lose results. [Modal streaming endpoints](https://modal.com/docs/guide/streaming-endpoints) |
| Cloudflare Durable Objects | A future live session with a stateful match coordinator or bidirectional control | Not needed for post-game analysis. The Free plan supports SQLite-backed objects with 100,000 requests/day, 13,000 GB-seconds/day and 5 GB total storage. It can handle per-match coordination, but it does not replace GPU inference. [Durable Objects pricing](https://developers.cloudflare.com/durable-objects/platform/pricing/) |
| WebSockets | Live capture, interactive control or a two-way simulation | Defer until live mode. It adds connection recovery and session state that post-game results do not need. |

For local playback, use **Media3 ExoPlayer 1.11.1** on the selected gallery URI and draw the received positions and events over the player at their original PTS. That gives immediate playback without downloading a second video. AndroidX Media3 is Apache 2.0. [Media3 release notes](https://developer.android.com/jetpack/androidx/releases/media3) and [AndroidX Media3 source license](https://github.com/androidx/media/blob/release/libraries/datasource/src/main/java/androidx/media3/datasource/DataSource.java)

For coaches or another device, either issue a signed URL to a supported MP4 or package HLS/DASH renditions into R2 and play them with Media3. **FFmpeg 9.0.2** is the current stable release I verified. FFmpeg is LGPL 2.1+ by default; enabling GPL components changes the applicable license. [FFmpeg releases](https://www.ffmpeg.org/download.html) and [FFmpeg license](https://www.ffmpeg.org/legal.html)

Mux Free is an optional demo playback service: its current plan lists up to 10 stored videos and 100,000 delivery minutes/month, on-demand only, with no card required. It is not an unlimited match archive. Cloudflare Stream is **not free**: storage starts at $5 per 1,000 stored minutes/month, with $1 per 1,000 minutes delivered. [Mux pricing](https://www.mux.com/pricing) and [Cloudflare Stream pricing](https://developers.cloudflare.com/stream/pricing/)

## 5. Android background work and failure behavior

Use **WorkManager 2.11.2** for persisted retry state and work that can wait for suitable connectivity. For a gallery upload the user explicitly started, use Android’s **user-initiated data transfer job** on API 34+ so Android can show transfer progress and the work has the correct user-initiated semantics. Android 16 applies JobScheduler runtime quotas to jobs that started while the app was visible and then continue in the background, and to jobs running alongside a foreground service. [WorkManager release notes](https://developer.android.com/jetpack/androidx/releases/work), [user-initiated data transfer jobs](https://developer.android.com/develop/background-work/background-tasks/uidt), and [Android 16 behavior changes](https://developer.android.com/about/versions/16/behavior-changes-all)

For compatibility, a `dataSync` foreground service can be started in response to the user’s action, with a persistent notification. If targeting Android 15+, `dataSync` and `mediaProcessing` foreground services have separate six-hour-per-24-hour limits. Do not run full-match encoding on the phone in the normal path. [Foreground-service timeouts](https://developer.android.com/develop/background-work/services/fgs/timeout)

If the network drops:

- Keep the original in the gallery and preserve segment/upload state locally.
- Retry only failed segments or multipart parts with idempotent keys, hashes and ETags.
- Refresh an expired signed URL without restarting the segment.
- Show “Paused, will resume” with current bytes and an ETA after throughput returns.
- Allow “Wi-Fi only” and a user-controlled pause.
- When analysis finishes, post a local notification. Use remote push as an optional wake-up when configured; do not make match recovery depend on push delivery.

For the event demo, keep one or two already analyzed matches cached on the phone. If venue connectivity fails, play the cached result and label it as cached. A fresh clip should never appear analyzed if its upload or analysis did not finish.

## 6. GPU choices and cost

### Modal is the strongest API-shaped option

The Modal Starter plan currently includes $30/month in compute credits, 100 containers and 10 GPU concurrency, but only three workspace seats. GPU use requires a valid payment method on file. The team has four people, so one operator would need to own deployments on the free plan, or the team would need a paid plan. [Modal pricing](https://modal.com/pricing) and [GPU requirements](https://modal.com/docs/guide/gpu)

The cheapest listed GPU is **NVIDIA T4 at $0.000164/second**. L4 is $0.000222/second and A10 is $0.000306/second. CPU is $0.0000131 per physical core-second and memory is $0.00000222 per GiB-second. For a T4 plus 2 vCPU/one physical core and 4 GiB, running in narrow `ap-south` Mumbai incurs the 1.75× region multiplier. That is about **$1.17/hour while running**. Modal says containers boot in about one second, but imports and model loading add startup time. The default idle scaledown window is 60 seconds. Keeping a GPU worker warm avoids that cold start but remains billable; 24 hours at the above configuration would be about $28.12. [Modal region selection](https://modal.com/docs/guide/region-selection) and [cold-start guide](https://modal.com/docs/guide/cold-start)

Put the weights in the container image or a mounted volume and load them before accepting jobs. Keep the worker warm only for a short demo window. Pass an R2 object key or signed URL to the worker, not the whole video as a Modal function argument; Modal’s region docs say inputs and outputs larger than 2 MiB still pass through object storage in `us-east`.

### Alternatives

| Platform | Verified current offer | Fit |
|---|---|---|
| Lightning AI | Pricing page advertises up to 80 free GPU hours and no card; its T4 row lists 75 free hours and $0.55/GPU-hour. Its free active Studio restarts every four hours. The offer is inconsistent on the same page, so count the hours as **UNVERIFIED** until the account confirms them. [Lightning pricing](https://lightning.ai/pricing) | Worth testing for a hackathon worker if the API remains reachable through restarts and the region works. Not the dependable default until those are proven. |
| Google Cloud Run GPU | L4 is $0.0001867/GPU-second with no zonal redundancy, one GPU/instance and minimum 4 CPU/16 GiB. Instances start in about five seconds and can scale to zero. Mumbai L4 is invitation-only. The $300/90-day trial cannot attach GPUs until upgrading to a paid billing account; its remaining credit can then be used, with billing enabled beyond the credit. [GPU docs](https://docs.cloud.google.com/run/docs/configuring/services/gpu), [pricing](https://cloud.google.com/run/pricing) and [trial limits](https://docs.cloud.google.com/free/docs/free-cloud-features) | Good only if the Mumbai quota/invitation and billing setup are already available. At current instance-based prices, 4 CPU/16 GiB plus L4 is about $1.05/hour before any other costs. |
| Hugging Face ZeroGPU | Free account quota is five GPU minutes/day, shared queue; hosted Spaces are Gradio-only. Free accounts in good standing and older than 30 days can host up to two ZeroGPU Spaces. [ZeroGPU docs](https://huggingface.co/docs/hub/main/spaces-zerogpu) | A small model demo, not a match-analysis backend. |

### Compute estimate, not a benchmark

For a 60 fps input, 5, 10 and 20 minutes contain 18,000, 36,000 and 72,000 frames. Assume an end-to-end worker rate of 30, 60 or 120 processed frames/second. Those rates are **scenarios**, not measured Modal/T4 results.

| Input duration | Processing at 30 fps; Modal cost | At 60 fps; cost | At 120 fps; cost | $30 credits cover, respectively |
|---|---:|---:|---:|---:|
| 5 minutes | 10 min; $0.20 | 5 min; $0.10 | 2.5 min; $0.05 | 153 / 307 / 614 matches |
| 10 minutes | 20 min; $0.39 | 10 min; $0.20 | 5 min; $0.10 | 76 / 153 / 307 matches |
| 20 minutes | 40 min; $0.78 | 20 min; $0.39 | 10 min; $0.20 | 38 / 76 / 153 matches |

The estimate includes the Modal T4, 2 vCPU and 4 GiB in Mumbai. It excludes idle warm time, image builds, retries, storage, Realtime and any provider charges beyond credits. Actual end-to-end speed is **UNVERIFIED** until the team benchmarks video decode, WASB and player detection together.

For the model comparison, Ultralytics reports **YOLO11n** at 1.5 ms on a T4 with TensorRT 10 at 640 pixels. That is detector inference only, not video decode, tracking or full-resolution pipeline speed. Its `yolo11n.pt` file is 5.61 MB at the cited pinned Hugging Face revision and is AGPL-3.0. [YOLO11 speed and license](https://docs.ultralytics.com/models/yolo11) and [pinned weights file](https://huggingface.co/Ultralytics/YOLO11/blob/a01aaa06caeff788b052e193acb76b3f21571b3a/yolo11n.pt)

Your supplied WASB-SBDT measurement is about 50 fps on M4/MPS with 5.8 MB weights. Treat that as **team-measured on M4, not a cloud benchmark**. The repository code is MIT, and its model zoo links to external weight files. The per-checkpoint license for the exact weight binary is **UNVERIFIED**. The repository reports its original environment as Python 3.8/CUDA 11.3/Ubuntu 18.04, so pin and smoke-test the worker image rather than assuming the current stack matches it. [WASB-SBDT repository and model zoo](https://github.com/nttcom/WASB-SBDT/blob/main/MODEL_ZOO.md) and [MIT code license](https://github.com/nttcom/WASB-SBDT/blob/main/LICENSE.md)

**CPU fallback throughput is UNVERIFIED for the full pipeline.** Ultralytics reports YOLO11n CPU ONNX at 56.1 ms/frame on an unspecified CPU at 640 pixels, about 17.8 detector frames/second under that benchmark. It does not measure WASB-SBDT on CPU, 1080p decode or the target cloud CPU. Use CPU fallback as a queue or degraded analysis mode, clearly reporting that ball tracking is pending or unavailable. Do not promise a completion time. [YOLO11 benchmarks](https://docs.ultralytics.com/models/yolo11)

## 7. Free-tier limits and storage policy

| Service | Free allowance verified | Recommended use |
|---|---|---|
| Cloudflare R2 Standard | 10 GB-month storage, 1 million Class A operations, 10 million Class B operations, free egress. Beyond free storage, Standard is $0.015/GB-month. [R2 pricing](https://developers.cloudflare.com/r2/pricing/) | Segment uploads, compact tracks, selected clips. Direct upload from phone. |
| Cloudflare Workers Free | 100,000 requests/day, 10 ms CPU/request, 100 MB request body. [Workers limits](https://developers.cloudflare.com/workers/platform/limits/) | Sign URLs, create match records and accept small segment-complete callbacks. Never proxy video. |
| Supabase Free | 500 MB database, 1 GB file storage, 5 GB egress, 2 million Realtime messages/month, 200 peak connections. Free projects can be paused after a week of low activity. [Billing limits](https://supabase.com/docs/guides/platform/billing-on-supabase), [Realtime pricing](https://supabase.com/docs/guides/realtime/pricing) and [pausing](https://supabase.com/docs/guides/platform/free-project-pausing) | Match manifests, progress, player/session history and Realtime notifications. Keep video in R2. |
| Modal Starter | $30/month compute credit; 10 GPU concurrency; three seats; payment method required for GPU. [Pricing](https://modal.com/pricing) and [GPU docs](https://modal.com/docs/guide/gpu) | T4 workers, capped concurrency and a short warm demo window. |
| Mux Free, optional | Up to 10 stored videos and 100,000 delivery minutes/month, on-demand. [Mux pricing](https://www.mux.com/pricing) | Optional demo playback, not the analysis store or a long-term archive. |

R2’s free 10 GB stores at most about five 2 GB full-match videos for a month, before analysis assets and clips. At 1,000 retained 2 GB matches for a month, Standard storage beyond the 10 GB allowance is roughly **$29.85/month** (`1,990 GB × $0.015`), before other billable dimensions. Keep full originals in the user’s gallery; retain compact analysis and selected reference clips in the cloud. If the product requires full remote replay across many sessions, the free R2 allowance is not enough without a retention policy, credits or a paid budget.

## 8. Red-team: eight likely failures

| Failure | Mitigation |
|---|---|
| 1. A large video on weak 4G makes “seconds” impossible. | Start local playback immediately. Show measured upload speed and ETA, then render each ready segment. Offer Wi-Fi-only, pause and resume. |
| 2. The team uploads one multipart object and expects the worker to read part one. | Use separate R2 objects for analysis segments. Use multipart only for a resumable full-source archive. |
| 3. Phone re-encoding is slow, heats the device or erases small-ball detail. | Do not require it in v1. Remux if possible; benchmark exact devices and detection quality before enabling a proxy or HEVC path. |
| 4. VFR, HDR, rotation, audio or a long GOP breaks segment boundaries. | Preflight media metadata. Segment at sync frames, preserve source PTS, overlap windows, and fall back to whole-file MPU upload when remuxing fails. |
| 5. Android stops a background upload or media service. | Persist upload state, use UIDT for user-started transfer on API 34+, WorkManager for retryable deferred work, and handle foreground-service timeouts. |
| 6. A cold worker or model download delays the first result. | Bake pinned weights and dependencies into the image, warm one T4 only around the demo, and show “worker starting” as timeline progress. |
| 7. Segment boundaries split a rally or create duplicate player IDs. | Use global PTS, short overlap, segment IDs and a final stitching step. Label partial tracks until that step completes. |
| 8. Free-tier limits, payment setup or storage retention interrupt the demo. | Cap GPU containers, stop the worker after the demo, watch credit usage, limit raw retention, keep cached matches, and remember Modal’s three-seat/payment-method limits and Supabase’s inactivity pause. |

## Recommendation

Build the upload path around separate, keyframe-aligned segment objects in R2, with multipart upload only as an optional full-source archive. Use a Modal T4 worker in `ap-south`, one warm container for the demo window, and Supabase for durable match status and small Realtime events. Play the local original in Media3 and overlay results by source timestamp. Do not make phone-side lossy re-encoding a prerequisite until it passes both device-throughput and small-ball quality checks.

Present the promise as “analysis starts as segments arrive.” Use a cached match if venue connectivity fails. For whole-match completion, publish the measured ETA instead of promising “shortly.”

## Three biggest risks

1. **Upload time:** large originals can take many minutes over 4G and several minutes over 5G. Segmentation improves first results, but cannot remove the uplink bottleneck.
2. **Analysis quality:** a small-ball/shuttle detector that worked on static tennis may fail on handheld college footage. Compression and target-device capture settings have not been validated for ball detection.
3. **Free infrastructure constraints:** Modal requires a payment method for GPU and allows three free seats; R2 holds only 10 GB-month free; Supabase Free can pause after inactivity.

## Open questions only your team can answer

1. Should cloud history retain full match video, or retain analysis plus a few reference clips while the original stays in the player’s gallery?
2. What should “within seconds” mean for first partial results, and what whole-match completion time is acceptable on weak 4G?
3. Is one Modal operator account with a payment method acceptable for the four-person team, or must the primary GPU path avoid card-backed usage?
4. Which actual phone, codec, resolution, frame rate and carrier will be used for the fresh-clip demo, and which cached match will be the fallback?