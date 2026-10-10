# services/insights/ · Person 3 (@nikhilpravinpise) · insights job (Python on Modal CPU)
Adds to the root AGENTS.md. Implement exactly `docs/lanes/person-3-spec.md` §Insights.
- Checks: `uv run ruff check . && uv run pytest -q`
- Runs on Modal CPU (no GPU), triggered after `finalize`; writes insights/moments/comparisons with the Supabase secret key, then appends `analysis.completed` via `append_event`.
- Deterministic only: SQL + the ranker in the spec. No LLM computes anything. Nothing synthetic.
