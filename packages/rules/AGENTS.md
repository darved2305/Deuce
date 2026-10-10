# packages/rules/ · Person 4 (@HetanshWaghela) · `@elevate/rules`
Pure score engines (`docs/plan/contracts.md` §10). Golden vectors in `vectors/*.json` are the single
truth shared by this TypeScript package and Person 2's Kotlin port; both test suites read the same
files. Add vectors before changing behaviour. Checks: `pnpm --filter @elevate/rules test`.
