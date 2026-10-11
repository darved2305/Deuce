# Elevate business case

As of 2026-10-11. Every outside figure is sourced in [SOURCES.md](SOURCES.md) (value, year, URL, confidence). Our own costs come from real engine runs. Anything labelled **estimate** or **assumption** is ours, and says so.

Interactive version (with a live unit-economics model): ask Hetansh for the "Elevate Business Case" page link.

## One line

A coach in every player's pocket: one Android phone video becomes match analysis and training. Professional tracking costs tens of thousands of dollars per court. Elevate needs a phone the player already owns.

## Market

| Signal | Figure | Year | Confidence |
|---|---|---|---|
| Badminton players worldwide | 220M (Pledge Sports) to 339M weekly (BWF/Nielsen); BWF 2024 says 384M | 2018 / 2024 | medium (339M, 384M from search summaries) |
| Tennis players worldwide, for comparison | 106M | 2024 | high |
| India badminton player count | not found (no official figure) | - | - |
| Bengaluru monthly badminton players on Playo | ~1.25 lakh (12x in 5 years) | 2025 | medium |
| Hudle: courts / players | 2,000+ courts in 60+ cities / 1.5M players | 2025 | medium |
| India sports market | $130B by 2030, 14% CAGR | Deloitte x Google | high |
| India sports technology | $1B, 19% CAGR | Deloitte x Google | medium |
| India fitness apps | $436-521M, ~19% CAGR | 2024-25 | medium |
| Android share of phones in India | ~93% | mid-2026 | medium (search summary) |
| Padel / US pickleball (expansion sports) | 30M worldwide / 19.8M (+45.8%) | 2024 | high |

## Competition

| Product | Sport | Needs | Price | Signal |
|---|---|---|---|---|
| Hawk-Eye | Tennis and others | Multi-camera install | $60-100K per court | 2011/2015 reports |
| PlaySight | Tennis and others | Installed cameras | ~$10K per unit + monthly | 2014 |
| Stupa | Table tennis, badminton | Venue system | B2B | BWF-certified line review, 20 Mar 2026 |
| SwingVision | Tennis, pickleball | One iPhone | $179.99/yr US; Rs 19,900/yr India | $6M Series A (Oct 2023), 100+ US college teams |
| Machaxi | Badminton coaching centres | Coaching ops | B2B | $1.5M raised (Jun 2025), target 1,000+ centres |
| Smashspeed | Badminton smash speed | One iPhone | Free + IAP | Tiny (20 ratings) |
| **Elevate** | **Badminton, then racket sports** | **One Android phone** | **Rs 199/mo (plan)** | **Live end to end today** |

SwingVision proves people pay for one-phone sports analysis. It is tennis and pickleball, iPhone-first, and not priced down for India. Badminton has no consumer equivalent.

## Pricing (plan)

| Tier | Price | Includes |
|---|---|---|
| Free | Rs 0 | 2 analysed matches a month, coach report, court map, ReadLab |
| **Pro player** | **Rs 199/month or Rs 1,499/year** | Unlimited (fair use 20/month), Reflex, Decide, Instant Review, profile trends, challenges, share cards |
| Coach / academy | Rs 2,999/month, up to 25 players | All students' matches, assign drills and challenges, squad progress |
| Developer API | $0.25 per video minute | REST API, TypeScript SDK, AI assistant connector (MCP), developer keys |

Anchors:
- Cricbuzz Plus is Rs 199 and Strava Rs 349 in India.
- Typical badminton coaching is Rs 2,000-5,000/month (premium up to Rs 15,000), so the academy tier costs about one student's fee.
- Court rental in Bengaluru is about Rs 199-300 an hour.

## Our real costs

**Measured:** a real 8-minute match (484.5 s) ran as 81 pieces in 626.6 s on up to 10 Modal T4 GPUs, with 4 CPU cores per container.

| Item | Rate | Per 8-min match |
|---|---|---|
| GPU, upper bound (10 T4 x 626.6 s) | $0.000164/s | $1.03 |
| CPU, upper bound (4 cores x 10 x 626.6 s) | $0.0000131/core-s | $0.33 |
| **Total, upper bound** | | **$1.36 (about Rs 116)** |
| Video storage (deleted after 1 day) | R2 $0.015/GB-month, free tier 10 GB | ~$0 (inside the free tier by design) |
| Database and auth | Supabase Pro $25/month for 100K users | ~$0.00025 per user per month |

The upper bound assumes all 10 GPUs were busy for the whole run. The true GPU-seconds (from Modal usage) are owed: issue #155.

## Unit economics, Pro player (assumptions: 6 matches/month, Rs 199, 15% store fee, Rs 85 = $1)

| Cloud cost per match | Monthly cloud cost | Store fee | Margin per Pro player | Margin % |
|---|---|---|---|---|
| $1.36 (measured upper bound) | Rs 694 | Rs 30 | **-Rs 525 (loss)** | -264% |
| $0.25 (optimised cloud, target) | Rs 128 | Rs 30 | Rs 41 | 21% |
| $0.10 | Rs 51 | Rs 30 | Rs 118 | 59% |
| $0.02 (tracking on the phone, illustrative) | Rs 10 | Rs 30 | Rs 159 | 80% |

Break-even at 6 matches/month needs cloud cost under **$0.33 per match**.

**This is the core business lever.** It is why Android-first matters: the app already runs court mapping and the rules on the phone, and moving shuttle tracking on-device takes cloud cost toward zero.

Levers to reach the $0.25 target (owner: VrajVed, #155):
- keep containers warm (the measured run includes cold starts);
- skip tracking during dead time between rallies (sound and motion already detect play);
- batch more frames per GPU call;
- use fewer CPU cores per container.

## Three-year scenarios (estimates, not forecasts)

| Case | Reachable players | Paid conversion | Pro players | Academies | Yearly revenue |
|---|---|---|---|---|---|
| Cautious | 500K | 1% | 5,000 | 50 | Rs 0.93 cr |
| Base | 1.5M | 2% | 30,000 | 200 | Rs 5.2 cr |
| Strong | 3M | 3% | 90,000 | 600 | Rs 15.6 cr |

Method:
- Pro counted at Rs 1,499/year, academies at Rs 2,999 x 12.
- Reachable players are anchored on booking-app audiences (Hudle 1.5M; Playo 1.25 lakh/month in Bengaluru alone).
- Freemium converts about 2.1% of downloads (RevenueCat); a hard paywall with trial converts about 10.7%.

Check, base case: 30,000 x 1,499 = Rs 4.50 cr, plus 200 x 35,988 = Rs 0.72 cr, which gives Rs 5.2 cr.

## Go to market

- **Court-booking apps (Playo, Hudle):** "Analyse this session" right after a booking.
- **Academies:** the coach tier onboards every student at once, and parents see progress.
- **College and corporate leagues:** challenges and leaderboards spread inside a club.
- **Built-in sharing:** share cards and friend challenges (already built) bring each new player's friends.

## Risks and answers

| Risk | Answer |
|---|---|
| Cloud cost per match | The levers above, then on-device tracking on Android |
| Shaky handheld video | Engine stabilisation is built (#147, off until deployed); in-app recording guides framing (#153) |
| Low consumer spend in India (India/SEA revenue per install $0.06-0.09 vs $0.44 global Health and Fitness) | Academies and the API carry early revenue; consumer pricing anchored on Indian price points, not US |
| A larger rival adds badminton | Badminton rules engine, Android-first, Indian pricing, training games on your own footage, developer platform |

## Owed numbers

- True GPU-seconds per match from Modal usage (#155).
- Held-out tracking accuracy on a clip we never tuned on (#155).
- An India badminton player count, a current PlaySight price and a Foxtenn price: not found.
- Re-check in a browser before external use: BWF 339M/384M and the StatCounter 93% (search summaries only).
