# Gaps, edge cases and notes for the TopLogic team (<APP>)

Status <DATE>. Discussion paper for the team: what the migration to model-based object security left
open, what was accepted on purpose, and what should be proposed to TopLogic.

- **Part A** is general: engine behaviour, best practices and proposals for TopLogic. Start from the
  table "Known engine gaps" in the skill (`engine-reference.md` §3) and keep only what applies, with
  the app's concrete example.
- **Part B** is app-specific: decisions and accepted gaps.

Each item: problem, impact, status, proposal, sources (D#, F#, test IDs).

Status values: **accepted** (decided so) · **open** (to decide or implement) · **engine** (proposal or
bug report to TopLogic) · **best practice** (rule for developers).

Guiding principle: *secure what is necessary, allow what is possible.* A superset of the objects a role
really needs is fine as long as there is no critical gap: no write gap, no access across tenant or
supplier boundaries.

---

# Part A: General (engine, best practices)

## A1. <title>

**Status:** engine / best practice

**Problem:** <…>

**Impact in <APP>:** <example>

**Proposal:** <…>

---

# Part B: <APP>-specific

## B1. <title>

**Status:** accepted (D#) / open

**Decision / situation:** <…>

**Reason:** <…>

**Solution if needed later:** <…>

## Bn. Deliberate supersets ("allow what is possible")

| What | Who | Decision |
|---|---|---|
