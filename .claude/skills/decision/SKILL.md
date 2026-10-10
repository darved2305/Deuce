---
name: decision
description: Record a decision (plan, contract, scope, stack, ownership or rule change) in DECISIONS.md with its blast radius. Use before changing anything another person depends on.
argument-hint: "<short title>"
---
Append one entry at the END of `DECISIONS.md` using the template at the top of that file.

1. ID: `D-<YYMMDD>-<your initials><n>` (n = your count for the day). Never reuse or renumber an existing ID.
2. Fill every field. Blast radius is mandatory: contracts (and version bump), every person and folder affected with what they must do, data/infra (migrations, env names in `.env.example`, secrets, Modal/Supabase config), containment.
3. Cite a URL or a real run for every factual claim; mark anything unchecked `UNVERIFIED`.
4. If it supersedes an entry, change only that entry's status line to `superseded by <new ID>`. Never rewrite an accepted decision.
5. If it changes `docs/plan/contracts.md` or `packages/contracts/`, ship it as its own PR first and name every affected person as an approver.
6. Do not edit `AGENTS.md` from here; if a rule should become permanent, propose it in the entry and Person 4 adds it.
7. Mention the ID in the PR's `## Decisions` section.
