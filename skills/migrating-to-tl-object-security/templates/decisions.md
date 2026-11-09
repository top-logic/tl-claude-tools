# Decisions: object security for <APP>

> **CURRENT RESUME POINT (<DATE>, <short state>):**
> - Done: …
> - In progress: …
> - **NEXT:** …
> - Open stragglers / TODOs for later phases: …
>
> (When the state changes, move this block down as PREVIOUS RESUME POINT and write a new one.)

Every decision: number, date, who decided, finding (facts checked in the code, with file/line),
options considered, decision, implementation (files), tests (IDs in `test-checklist.md`).

---

**D1: <title> (<developer> <DATE>).**
- Finding: <what was observed, where; which roles reach which types; what the code really does>.
- Options:
  - (a) <option> – pro/contra;
  - (b) <option> – pro/contra.
- Decision: (a), because <rationale>. Principle: secure what is necessary, allow what is possible.
- Implementation:
  - `<config file>`: <rule/grant/access parent>;
  - helper role `<ROLE>` (role list + i18n, in no role profile).
- Accepted consequence / known limitation: <…> (→ `gaps-and-edge-cases.md` if relevant).
- Tests: T1-xx, T1-yy.

**D2: …**
