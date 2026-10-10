# Cloudflare costs nothing (D-261011-HW1, D-261011-HW2)

Cloudflare has **no hard spending cap**. Its budget alerts only send an email. We never even get
close to the free tier: our budget is **20% of each free limit**, the API refuses work at that
line on its own, and we pause at 10%. We keep the bill at $0 ourselves, in three layers: our code refuses work long before the free limits, the bucket is
configured so nothing piles up or leaks, and one external backstop stops any charge that slips
through. Every agent and every person follows this file. Facts checked against
developers.cloudflare.com on 2026-10-11.

## The numbers

| | Cloudflare free tier (per month) | **Our hard budget (20%)** · pause at (10%) | Price beyond free tier |
|---|---|---|---|
| R2 storage (Standard only) | 10 GB-month | **2 GB stored at any moment** · 1 GB | $0.015 / GB-month |
| R2 Class A ops (writes: `PutObject`, `CreateMultipartUpload`, each `UploadPart`, `CompleteMultipartUpload`, `ListObjects`, `ListParts`) | 1,000,000 | **200,000** · 100,000 | $4.50 / million |
| R2 Class B ops (reads: `GetObject`, `HeadObject`; each ranged read counts) | 10,000,000 | **2,000,000** · 1,000,000 | $0.36 / million |
| R2 egress | free | free | free |
| Workers AI | 10,000 Neurons / day | **Workers Free plan only** | none on Free: requests fail with an error past the daily limit |

Infrequent Access storage is **not** in the free tier and has a 30-day minimum. We never use it.

## Layer 1 · Code refuses before the limit (Person 3 API, Person 1 engine)

The API is the only thing that hands out upload and download URLs, so it enforces the budget.

| # | Guard | Where | Response when hit |
|---|---|---|---|
| G1 | One video (all its segments together) at most **300 MiB** (`source.bytes` in `POST /v1/sessions`) | API | 413 `upload_too_large` |
| G2 | Before `CreateMultipartUpload`: bytes of originals created in the last day + all artifacts and clips + this upload must stay **≤ 2 GiB** | API | 429 `storage_budget_reached` + `retry_after_s` |
| G3 | At most **5 sessions per user per day** and **30 uploads per day in total** | API | 429 `daily_upload_limit` + `retry_after_s` |
| G4 | Upload-part or segment URLs per session at most **3 × the part or segment count** | API | 429 `too_many_part_requests` |
| G5 | A table `r2_usage_monthly` counts Class A ops we cause (every part URL issued, every artifact the engine registers). Refuse new uploads when the month reaches **200,000** | API | 429 `storage_budget_reached` |
| G6 | Download URLs expire in **≤ 15 min**, upload-part URLs in **≤ 10 min**; at most **200 download URLs per user per day** | API | 429 `daily_download_limit` |
| G7 | Never call `ListObjects` / `ListParts` in a request path or a loop (they are Class A). The database knows every key | API, engine | n/a |
| G8 | Per analysis the engine writes at most: one `window_result` per window, 2 track files, 1 `match_vision`, **40 clips** (mp4 + jpg each, ≤ 3 s each). It reads the original once | engine | stop writing, post `analysis.failed` reason `artifact_budget` |
| G9 | Kill switch: env `R2_WRITES_ENABLED=false` makes every presign route return 503 `uploads_paused` | API | 503 |
| G10 | Automatic pause: when storage reaches **1 GiB** or the month's Class A count reaches **100,000** (10% of free), the API behaves as if G9 were set and logs `r2_budget_paused` | API | 503 `uploads_paused` |
| G11 | The app retries each upload part **at most 3 times** with backoff and never loops on a failed URL (a presigned URL can be reused until it expires, and every use is a billed op) | app | show "Upload paused, try later" |

The 429 shape is the contract's (§1): `{error: {code, message}}` plus `retry_after_s`.

## Layer 2 · Bucket configuration (Cloudflare account owner, once)

Use the bucket name from `R2_BUCKET`.

```sh
# originals are deleted 1 day after upload (the analysis finishes in minutes; the app plays the phone's own copy)
npx wrangler r2 bucket lifecycle add "$R2_BUCKET" delete-originals orig/ --expire-days 1 --force
# results and clips are kept 14 days (enough for the demo), then deleted
npx wrangler r2 bucket lifecycle add "$R2_BUCKET" delete-analysis analysis/ --expire-days 14 --force
npx wrangler r2 bucket lifecycle add "$R2_BUCKET" delete-clips clips/ --expire-days 14 --force
# chunked-upload segments are deleted 1 day after upload, like originals
npx wrangler r2 bucket lifecycle add "$R2_BUCKET" delete-segments seg/ --expire-days 1 --force
# abandoned multipart uploads are dropped after 1 day (the default is 7)
npx wrangler r2 bucket lifecycle add "$R2_BUCKET" abort-multipart --abort-multipart-days 1 --force
# check
npx wrangler r2 bucket lifecycle list "$R2_BUCKET"

# no public access: anyone hitting a public URL would spend our Class B ops
npx wrangler r2 bucket dev-url disable "$R2_BUCKET" --force
npx wrangler r2 bucket domain list "$R2_BUCKET"   # must list nothing
```

- **Standard storage only.** Never pass `--ia-transition-days` or `--ia-transition-date`.
- **Two scoped R2 API tokens**, each "Object Read & Write" on this one bucket only: one for the API
  (Supabase secret), one for the engine (Modal secret). Never an account-wide admin token in the
  app, the API or the engine.
- **Stay on the Workers Free plan.** Never click "Upgrade to Workers Paid". On Free, Workers AI
  stops with an error at 10,000 Neurons/day instead of billing.

## Layer 3 · External backstop (Cloudflare account owner, once)

- If Cloudflare asks for a payment method (to verify: R2's pricing page does not say), use a
  **virtual card with the lowest limit your bank allows** (or a prepaid card with near-zero
  balance). It is the only true cap: a charge that gets past layers 1 and 2 is declined instead of
  paid. A declined charge can suspend the account; that is better than a surprise bill.
- If the dashboard offers it (Billing: budget alerts, account-wide dollar threshold), set an alert
  at **$1**. It only emails; it does not stop anything.

## Daily check (Cloudflare account owner, 2 minutes)

1. Dashboard → R2 → the bucket → Metrics: storage now, Class A and Class B month to date.
2. Dashboard → Billing: current usage must read **$0.00**.
3. `npx wrangler r2 bucket lifecycle list "$R2_BUCKET"` still shows the 5 rules.
4. Any metric at **10% of the free tier** (storage 1 GB, Class A 100,000, Class B 1,000,000): set
   `R2_WRITES_ENABLED=false` (G9) and tell the team in the group chat. G10 should already have
   paused uploads; if it did not, that is a bug to fix before uploads resume.

## Emergency stop (anything unexpected)

1. Set `R2_WRITES_ENABLED=false` (uploads stop, the app keeps showing what exists).
2. Still climbing: roll (revoke) both R2 API tokens in the dashboard. Every read and write stops at
   once.
3. Delete `orig/` objects from the dashboard if storage is the problem.

## Worst case, by design

- **Storage** cannot pass 2 GiB: G2 refuses the upload first, and lifecycle rules delete
  originals after a day. 2 GB of 10 GB free.
- **Writes** stop at 100,000 Class A ops a month (G10) and are refused outright at 200,000 (G5),
  each upload is at most 40 parts (300 MiB / 8 MiB) and each part at most 4 attempts (G11).
  200,000 of 1,000,000 free.
- **Reads** are bounded by 200 short-lived download URLs per user per day (G6). The daily check
  pauses at 1,000,000 of 10,000,000 free.
- **Workers AI** cannot bill on the Workers Free plan; it errors at its daily limit.
- What remains is a leaked token or a bug that bypasses the API: scoped tokens, the emergency
  stop and the card backstop cover it.

## Never

- Infrequent Access storage, public buckets, r2.dev URLs or custom domains on the bucket.
- `ListObjects` in loops, retries without a cap, or a download URL valid for longer than 15 minutes.
- Workers Paid, or any paid Cloudflare add-on.
- An admin or account-wide token outside the dashboard owner's own machine.
