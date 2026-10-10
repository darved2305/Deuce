<!-- Branch must be p{n}/{slice}. Fill every section; CI checks Proof and Decisions. -->

## Slice
<!-- one line: the behaviour this PR adds -->

## Decisions
<!-- D-… IDs this PR implements or adds, or: none -->

## Contracts
<!-- unchanged, or the new version + decision ID -->

## Proof
<!-- REAL evidence (D-009). Tests passing is not proof.
P1: Modal app/run ID + outputs + detections vs hand labels on a real clip.
P2: screen recording or screenshots on the S23 FE / A55 with real footage.
P3: real calls against the deployed API with real IDs (paste the command and response).
P4: real footage / real labels / eval output on real engine results.
If an upstream isn't ready, say which part ran on the hand-labelled real fixture. -->

## Checks run
- [ ] Lane checks (commands + result):
- [ ] Agent review (`/code-review` or `codex review --base origin/main`): blocking findings fixed
- [ ] Stayed inside my lane (`./scripts/check-lane.sh`)
