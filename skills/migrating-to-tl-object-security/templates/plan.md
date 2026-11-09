# Plan: object security for <APP>

Started <DATE>. Engine facts: skill `engine-reference.md`. Legacy concept of the app: `legacy-analysis.md`.

**Goal:** a `SecurityConfigurationService` configuration with which every business role works in the
frontend **exactly as before**, and which is safe for REST API and agent access. As slim as possible:
`without-security`/`internal` for technical types, access parents for context-bound types (mostly the
composition default), explicit grants only on role carriers and master data.

**Starting point:** <existing config draft / empty>. An existing config is pruned, not reset (the
running frontend already depends on it).

## Phase 0: baseline and tooling

- [ ] 0.1 Engine facts read (skill files, `tl-docs` in `tl-core.jar`), TL version noted: <VERSION>.
- [ ] 0.2 Coverage baseline captured: `coverage-baseline.txt` (+ short classification).
- [ ] 0.3 Legacy analysis: `legacy-analysis.md`.
- [ ] 0.4 Test accounts (one non-admin per role), role wiring task with verification log, test data.
- [ ] 0.5 Model map: `model-map.md` (compositions, role carriers, master data, technical types).
- [ ] 0.6 REST security probe deployed in the local deploy aspect; first call works.
- [ ] 0.7 Phases and their order agreed with the developer (below).

## Phase order (adapt)

| Phase | Role(s) | Why this order | Status |
|---|---|---|---|
| 1 | <central writing role> | largest object scope, sets the patterns | |
| 2 | <its read-only variant> | subset of phase 1 | |
| 3 | <other internal roles> | | |
| 4 | <external roles> | most critical for API exposure | |
| 5 | <tenant admin> + master data maintenance | | |
| 6 | <system admin, USER> | root-only roles | |
| 7 | cleanup and safeguards | coverage clean, `test-security-coverage`, API leak tests | |

## Per-phase procedure (repeat)

1. Reachable views of the role (profiles), types used there and how (select → Discover; table,
   form, column, `get` → Read; edit → Write; new/delete → Create/Delete or Write on the parent).
2. Classify the types (technical / context-bound / role carrier / master data).
3. Where does the role land (rule paths)? Which grants take effect, which would be dead?
4. Scripts that must see more than the user → privileged functions.
5. Decisions D# one at a time (in `decisions.md`), then implement, re-run coverage.
6. Test points T<phase>-xx in `test-checklist.md`; test as the non-admin test user (browser) and via
   the REST probe (API view); record results.
7. Commit per step.

## Status log

- <DATE>: …
