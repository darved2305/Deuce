# Changelog

## 1.0.0

- Initial package version for contracts v1, as recorded by D-012.
- Tests keep the spec and the schemas in step: every fenced JSON example in `contracts.md` must
  validate, and every enum written in its prose (provenance, tiers, statuses, analysis states,
  event types, model IDs, review estimates, ID prefixes) must equal the schema enum.

### Open questions for the contracts amendment (D-012 sign-off)

- §0 says every derived value carries a sibling `state`, but the §7 `metrics` example has none.
  The schema follows the example (no `state` on metrics) until the amendment decides.
- §1 does not say where `retry_after_s` sits on a 429; it is an optional top-level field.
- §2 does not define the upload-parts response envelope; it is an array of URLs.
- §3 does not define the Modal submit response; Person 1 returns
  `{analysis_id, modal_call_id}` (PR #5). Add it as `modal.submit.response.v1`.
- §7 `H_cov`, `attribution`, correction `old`/`new` have no non-null type yet.
- §9 `zone_grid` and court-template `lines[]` have no defined shape yet.
- Named components: schemas are inlined in OpenAPI output; add `.meta({ id })` names when the SDK
  needs readable type names (additive).
