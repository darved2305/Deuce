# Hand-off to Person 4: updated `fixtures/fixture_trail.json`

`fixture_trail.json` here = the current fixture plus 47 frames re-clicked by @darved2305 (2026-10-11) and checked visually: 196 of 200 frames labelled (153 shuttle points, 43 not visible, 4 unlabelled: frames 48, 136, 137, 174). Validated against `packages/contracts/schemas/elevate.trail_labels.v1.schema.json` (Python jsonschema).

Person 4: copy it over `fixtures/fixture_trail.json` (your lane) and delete this folder in the same PR. The app copies `fixtures/fixture_trail*.json` and `fixtures/fixture_events*.jsonl` into its assets at build time, so it picks the update up with no Android change.
