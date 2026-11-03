# Contributing

Thanks for looking. This repository was built by four people in four lanes during a hackathon, and the
rules below are what kept the claims in the README honest. They apply to outside contributors too.

## Before you start

1. Read [`AGENTS.md`](AGENTS.md). The ten non-negotiable rules are short and CI enforces most of them.
2. Open an issue first for anything bigger than a typo or a small fix, so we can say whether it fits.
3. Look at the component's own `AGENTS.md` (`android/`, `engine/`, `supabase/`, `mcp/`, `sdk/`) for
   its stack, pinned versions and gotchas.

## The rules that matter most

- **Nothing synthetic.** No invented numbers, clips, detections or demo data. Screenshots and results
  come from real runs.
- **Real proof.** Tests catch regressions; they are not proof that a feature works. Every pull request
  has a `## Proof` section with a link, a command and its output, or a run id.
- **Decisions are written down.** A change to the plan, a data shape (`docs/plan/contracts.md`,
  `packages/contracts`) or a rule goes in [`DECISIONS.md`](DECISIONS.md) in the same pull request.
- **No secrets, no large binaries.** Names live in `.env.example`; videos and model weights stay out of git.
- **Do not edit `LEDGER.md`.** CI writes it after each merge.
- **Pin versions.** Do not use a library or platform API you have not read the current docs for.

## Workflow

1. Fork, then branch from a fresh `main`. The team's own branches are named `p<n>/<slice>`.
2. Keep the change to one behaviour, about 300 lines or fewer.
3. Run the checks for the part you touched (see "Run it" in the [README](README.md#run-it)).
4. Open a pull request and fill in the template: Slice, Decisions, Contracts, Proof, Checks run.
5. CI runs `guard` (lane, proof, decisions, secrets) plus the jobs for the folders you changed.
   `ci-gate` must be green.

Maintainers own different folders (see [`.github/CODEOWNERS`](.github/CODEOWNERS)); a change outside
the owner's lane needs their approval and the `cross-lane` label.

## Security

Do not report vulnerabilities in public issues. See [`SECURITY.md`](SECURITY.md).
