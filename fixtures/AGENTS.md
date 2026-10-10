# fixtures/ · Person 4 (@HetanshWaghela) · REAL fixtures only
Every fixture comes from a real clip listed in `clips.md`, hand-labelled (`state: "user"`,
`engine_version: "hand-labelled"`) or real engine output. Never invented values (D-008).
Video files are not in git: fixtures reference the clip by R2 key / URL with its sha256 and source.
`fixtures/eval/` holds held-out labels and `RESULTS.md`. CI validates every JSON against the schemas.
