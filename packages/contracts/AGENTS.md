# packages/contracts/ · Person 4 (@HetanshWaghela) · the shared contracts
Zod 4 is the single source (`z.toJSONSchema()` generates JSON Schema). Generated outputs (JSON Schema
files for Python/Kotlin) are committed and CI fails if they are stale. Every change: a DECISIONS
entry, a version bump in package.json, and sign-off from everyone in the blast radius.
- Checks: `pnpm --filter @elevate/contracts run check` (typecheck + regenerate + validate every file in `fixtures/` against the schemas).
