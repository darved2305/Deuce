# 08 · Developer platform (product B)

Decided in debate D2, checked by reviews R1–R2 (`11-debates.md`). The app uses the same API.

## 1. Promise

"Send a racket-sports video, get back a versioned, evidence-linked match record (rallies, events,
review estimates, user-confirmed score, players, real clips), each value with its provenance and
the frames that prove it. Same IDs in our app, your app and your AI assistant."

## 2. Surfaces and build order

| Phase | Surface | Detail | Demo proof |
|---|---|---|---|
| 1–2 | REST | Base `{SUPABASE_URL}/functions/v1/api`; OpenAPI 3.0.4 generated from zod at `/v1/openapi.json` (3.0.4 chosen for generator compatibility); Scalar docs; idempotency keys; cursor pagination | "Try it" on a seeded match returns real JSON |
| 3 | TypeScript SDK | OpenAPI Generator 7.26.0 `typescript-fetch`; generated, never hand-edited | A short script prints the summary and pages events |
| 3 | Read-only MCP | `@modelcontextprotocol/server` 2.3.1 (spec 2026-07-28); local stdio server reading the API (owner JWT kept local) or an exported `.rally` file; tools `get_match_summary`, `find_moments`, `get_rally`, `get_capabilities` | Tested against the actual Claude host; if that host check fails, shown through the MCP inspector |
| 4 | Developer keys | `X-API-Key` (hashed, read-only scope by default) | Key created and revoked in the docs page |
| 4 | Embeddable replay | `<match-player match="m_…" token="…">` web component (Person 4): real clip, overlays from the record, rally barcode; match-scoped embed token | Academy page embeds the same match |
| 4+ | Python SDK, remote MCP with OAuth, webhooks (HMAC + outbox) | | |

## 3. Endpoints (`/v1`, relative to the base)

```text
GET    /v1/health
GET    /v1/openapi.json
GET    /v1/capabilities                              sport × feature tier + status, model descriptors
POST   /v1/sessions                                  create; returns R2 multipart upload plan
POST   /v1/sessions/{id}/upload/parts                presigned part URLs
POST   /v1/sessions/{id}/upload/complete             idempotent completion (parts + ETags)
POST   /v1/sessions/{id}/upload/abort
POST   /v1/analyses                                  idempotent; dispatches the Modal job
GET    /v1/analyses/{id}                             state and stages
GET    /v1/analyses/{id}/events?after_seq=&limit=    durable cursor (after_seq exclusive)
GET    /v1/matches/{id}                              match.v1
GET    /v1/matches/{id}/rallies?cursor=
GET    /v1/matches/{id}/rallies/{rid}                shots, events, track artifact IDs, clips
GET    /v1/matches/{id}/moments?filters=             typed filters
GET    /v1/matches/{mid}/shots/{shot_id}/comparisons?scope=own|shared|reference&limit=3
POST   /v1/matches/{id}/reviews/{rvid}/confirm       Idempotency-Key; → confirmed point + point.confirmed
POST   /v1/matches/{id}/corrections                  append-only
POST   /v1/matches/{id}/share-grants   ·   DELETE /v1/matches/{id}/share-grants/{gid}
POST   /v1/matches/{id}/embed-tokens
DELETE /v1/matches/{id}                              cascade delete
GET    /v1/players/{id}/history?metric=&range=       comparable trend with tiers
GET    /v1/artifacts/{id}                            short-lived signed URL after ownership check
GET    /v1/export/{match_id}.rally                   RallyPack
POST   /v1/developer-keys   ·   DELETE /v1/developer-keys/{id}
POST   /v1/matches/{id}/simulation-runs              Phase 4
GET    /v1/matches/{id}/opponent-scout?opponent_id=  Phase 4
POST   /internal/analyses/{id}/events | /artifacts | /finalize     Modal only (X-Internal-Token)
```

Auth: app `Authorization: Bearer <Supabase JWT>` + `apikey`; developers `X-API-Key`; embeds
use match-scoped tokens; internal routes `X-Internal-Token`. Errors `{error: {code, message}}`.

## 4. Deep links

`elevate://m/{match_id}/r/{rally_id}?t_ms={t}` (Android intent filter; opens the rally and seeks)
with an https fallback to the embed. The scheme follows the final product name.

## 5. RallyPack (`.rally`)

Zip with `manifest.json` (format version, hashes, consent scope), `match.json` (`match.v1`) and
optional `clips/`. Players own and move their history; coaches and other apps import it; the local
MCP server answers from it without a network. A useful open format, not the moat.

## 6. Developer first experience (demo)

Docs page with a seeded match → curl → SDK script → (Phase 4) embed on the academy page → MCP
answer whose link opens the exact frame on the mirrored phone.

## 7. Limits stated up front

Quotas in headers and in `/v1/capabilities`; analysis returns 429 with `retry_after_s` when the
GPU budget is spent; free-tier limits listed in the docs.
