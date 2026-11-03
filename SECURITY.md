# Security policy

## Reporting a vulnerability

Please do not open a public issue for a security problem.

Use GitHub's private vulnerability reporting: the **Security** tab of this repository, then
**Report a vulnerability**. If that is not available, contact the repository owner,
[@darved2305](https://github.com/darved2305), through the email on their GitHub profile.

Include what you found, how to reproduce it, and which component it touches (`android/`, `engine/`,
`supabase/`, `mcp/`, `sdk/`). We aim to acknowledge a report within a week. This is a hackathon
project maintained by four students, so there is no bug bounty and no guaranteed fix time.

## What is in scope

- The Edge Function API in `supabase/functions/api`, including auth, row-level security and the
  upload and download budget guards.
- Secret handling: nothing in this repository should contain a credential. If you find one, report
  it here, do not use it.
- The read-only MCP server and the generated SDK.

## Design notes that matter to reporters

- The Supabase service-role key must never reach the Android app (`AGENTS.md`, rule 8).
- Cloud storage is private; the app uses short-lived signed URLs. Uploads and downloads are capped
  in code (`docs/ops/cloudflare-budget.md`).
- Secrets live in local `.env` files (gitignored), the Supabase and Modal secret stores, or GitHub
  Actions secrets. `.env.example` lists names only.
- CI scans every pull request diff with gitleaks.
