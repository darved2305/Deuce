# 02 · Product, users, principles and moat

The product name is undecided (`11-debates.md`, open decision 1). Docs say "the app".

## 1. The promise

**Import a match. See it on your own footage, with evidence-linked analysis drawn on the real
video. Review disputed moments. Confirm or correct. Keep one record across sessions, in the app
and through the developer platform. Anything the evidence cannot support is withheld, out loud.**

## 2. Two products, one record

| | (A) Player app | (B) Developer platform |
|---|---|---|
| Who | Club and college players, partners, coaches | Developers, academies, tournament organisers, AI-assistant builders |
| Job | "Show me what happened on my footage, what's worth fixing, and settle disputes" | "Give me structured, evidence-linked match data I can build on" |
| Surface | Native Android app (Kotlin + Compose + Media3), widget, notifications | REST `/v1` (OpenAPI 3.0.4), TypeScript SDK, read-only MCP, embeddable replay |
| Data | `match.v1` | the same `match.v1`, same IDs |

The app is the first client of the platform. There is no app-only data.

## 3. Users and the first story

1. **Player (primary).** Plays 2 to 4 times a week, films on a phone propped on a fence or bag.
   Wants to see their game, understand why points were lost, and settle "was that out?".
2. **Coach.** Many students, little time. Wants the few moments that matter, on real footage.
3. **Organiser / academy.** Wants scores, disputes resolved with evidence, and histories.

First story built: **the player, after a match, picking a video from the gallery.**

## 4. Principles (they break ties in every later decision)

1. **Real footage first.** Every visual sits on the user's real video or a real clip with
   provenance. The only synthetic visual is the top-down court, labelled when it shows
   something that did not happen (`SIMULATED`). See `12-real-clips.md`.
2. **Show, don't tell.** Pictures and clips first; text is a one-line caption from data.
3. **Every number opens its evidence** and carries its denominator.
4. **Abstain out loud.** "Too close to call", "shuttle not visible here", "not enough evidence
   yet" are designed states.
5. **The user never stares at a blank screen.** Local playback starts immediately; real stages
   and bytes are shown; results appear as they are ready.
6. **Fundamentals before flourishes** (`01`), then the daily-life and social features (Phase 4).
7. **One record, many surfaces.**
8. **Configuration, not training.**

## 5. "What's our moat?" (mentor question)

**What is not a moat:** an API/SDK/MCP (PB Vision already ships all three; Smashpoint has MCP),
multi-sport configuration (PlaySight, Stupa cover several sports), "AI coach" (anyone can wrap
an LLM), "India-first" (Playo, Hudle, KheloMore, Visist.AI, Nabham, Stupa exist).

**Our wedge today:** a phone-first match record in which every insight opens the frames that
prove it and uncertain calls are withheld. That is what a coach or a tournament referee needs
before trusting any number from a phone.

**Why it is designed to compound (our thesis, to be proven by usage):**
1. **Evidence-linked, consented history.** Each match adds real moments, outcomes and
   corrections to a player's library. That library is what makes "this worked for you" clips
   possible; it grows with use and a new entrant starts at zero.
2. **Corrections as a trust asset.** A visible correction history and a record of not
   overclaiming are slow to copy.
3. **Organiser distribution.** Academies and tournaments running events through the platform
   feed many players and repeated matches at once.

Pitch version: *"Today our wedge is trust: every insight shows the frames that prove it, and
we withhold what we can't support. Our thesis is that consented, corrected match histories
compound, so the product gets more personal with every match, and organisers bring those
matches in bulk."*

## 6. Mentor feedback map

| Mentor note | Answer | Where |
|---|---|---|
| Phone camera resolution | Preserve original frames and timestamps; capture coach; crop to the confirmed court before the model's resize; multi-frame trackers + a tracking layer; audio as event evidence; measured uncertainty and abstention; display-only smoothing separated from measurement | `04` §3 |
| Moat | Wedge + compounding thesis | §5 |
| Less text, more visuals | Visual primitives on real footage; one-line captions | `05` |
| AI judge/referee | Evidence assistant: model estimate + human confirmation | `07` |
| Fundamentals first | Checklist with held-out acceptance tests | `01` |
| Game simulation, opponent analysis | Built in Phase 4 on the same shot records | `06` |
| Analysis and recommendations first | Film room + real-clip comparisons with gates | `06`, `12` |

## 7. Name

All five earlier candidates collide (`11-debates.md`). Shortlist to screen: **CourtMemory**,
**RallyMirror**, **MatchEcho**. Team votes, then checks Play Store, domains and trademarks.
Until then the code uses the working name `elevate` (package names, deep-link scheme).
