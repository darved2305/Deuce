# 09 · The demo, beat by beat, on real footage

Decided in debate D6 and review R1 (`11-debates.md`). Every beat uses real footage and real engine
output. No QR codes. The S23 FE mirrors to the projector via USB-C → HDMI (DeX; adapter tested
beforehand). Upload uses the phones' own 5G (A55 as hotspot backup). Never show a fake
percentage; never present a cached result as just computed (label it "analysed earlier").

## Roles

Person 1 explains the engine and answers CV questions (model manifest ready). Person 2 drives the
S23 FE. Person 3 drives the laptop (developer platform). Person 4 films the live clip and hosts.

## Beats

| Beat | What judges see | Fallback |
|---|---|---|
| **Hook** | "Pros have Hawk-Eye. Clubs have a phone. We rebuilt as much as we honestly can from that phone, and it shows its evidence." Capability strip on screen. | — |
| **Filmed here, just now** (free play) | Person 4 films a short clip on a stand: catch, bottle-hit, bounces beside a taped line. Person 2 picks it from the gallery: the video plays at once; rally candidates appear from sound; one tap on the ball; real upload bytes and server stages; the pixel trail draws on the real clip as windows complete. Said plainly: a free-play clip with one tap of configuration, not a match. | Slow 5G → A55 hotspot; still pending → show the real queued state, then open a same-setup clip analysed earlier (labelled). Tracker loses the ball → show the abstention and move on |
| **The referee that admits doubt** | Review the corridor bounces: MODEL ESTIMATE IN, OUT and one TOO CLOSE TO CALL, beside the A55 slow-motion reference frames showing the truth. Confirm a point; the score updates. | Genuine cached results of the same setup |
| **Your game, on your footage** | The named, rights-cleared badminton clip (attribution on screen), analysed for real: hero insight on a real frame, rally replay with the shuttle trail, then Compare: this rally beside a real similar moment from the team reference library, labelled SIMILAR MOMENT with descriptive counts. | Cached on the phone. If no racket clip is rights-cleared, this beat is skipped rather than faked |
| **Judge plays** (only if simulation passed the Phase 4 check) | Hand the phone over: Read freeze → tap the landing zone → real reveal; Decide → target → legality and spacing. | Person 2 plays it |
| **Same record, any app, any AI** | Laptop: curl and the TS SDK print the same match; (if built) the academy page embeds the replay; Claude via the local MCP server answers a question and its link opens the exact frame on the mirrored phone. | MCP inspector if the Claude host check failed; otherwise the working API response only |
| **How much we rebuilt** | Capability matrix with tiers and the statuses from `RESULTS.md`; the moat thesis in two sentences; phone in airplane mode still browsing history. | Slide |

## Questions we are ready for

- "Precomputed?" → stage history, upload log, analysis ID, model hashes on the manifest screen.
- "Ball hidden?" → a rally with a gap and an UNOBSERVED review.
- "No training?" → model manifest; four corner taps and one ball tap are configuration.
- "Accuracy?" → `RESULTS.md`: clip names, conditions, denominators, statuses.
- "Moat?" → `02` §5.

## Rehearsal checklist

Adapter tested on the projector; both phones charged; capture modes audited; warm Modal
container started just before; full runs rehearsed with every fallback; every fallback clip
genuinely analysed and labelled.
