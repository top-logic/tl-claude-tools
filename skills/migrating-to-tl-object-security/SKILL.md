---
name: migrating-to-tl-object-security
description: Use when a TopLogic application with legacy security (AccessManager role rules, security.xml role profiles, layout securityObject) must get a working SecurityConfigurationService / model-based object security configuration - e.g. after upgrading to TopLogic 8, when non-admin users suddenly see empty lists or get permission errors, or before exposing a REST API or AI agent in the user's permission context. Guides the developer step by step - legacy analysis, master data concept, per-role configuration with decisions, test setup, browser and API tests.
---

# Migrating a TopLogic app to object-based security

Legacy TopLogic security protects **views and command groups**: profile × role on the view's
security object. Model-based object security (`SecurityConfigurationService`) protects **objects
and attributes** wherever they are accessed: TL Script, the API, agents, React views.

**Core principle:** an object grant `operation=X roles=R` on type T only takes effect if the user
**holds R on that very object** (via the legacy role rules or role parents), or if T delegates to an
access parent that does. Grants are derived from **role paths**, never copied from profiles.

**Your job:** guide the developer through the migration step by step. Analyse the existing
configuration yourself (read the files, read engine code with the tl-mcp tools), propose concepts
and configurations, and ask the developer whenever a decision is business knowledge (internal vs.
external roles, what a role really needs, what is sensitive). Keep the goal in view: **every role
works as before in the UI, gets what it needs and nothing it doesn't**, also via API and agents.

## Files of this skill

| File | Read when |
|---|---|
| `legacy-concept.md` | Step 2, before analysing the app (roles, profiles, security objects, groups, call chain) |
| `engine-reference.md` | **Before planning** (required), and whenever a check behaves unexpectedly. Version-specific (TL 8.0.0-alpha11) |
| `patterns.md` | Steps 5–6: master data, helper roles, privileged functions, referenced objects |
| `pitfalls.md` | When something works as root but not as the test user; before reviews |
| `templates/` | Step 0: copy into the project's docs folder (and the memory template into the project memory) |
| `assets/` | Step 4: test accounts, setup task with role verification, REST security probe |

## Facts that break naive plans

- **Always on, deny by default.** No activation switch. Every TL Script run by classic layouts
  (model builders, options, defaults, executability) is already secured: non-admin lists are filtered,
  `get` returns empty values. **Root and `admin=true` accounts bypass everything**, so testing as root
  proves nothing.
- **The legacy role rules are the role source of the new layer.** Profiles (view access) and object
  grants (object access) are independent dimensions; both stay. You can't "remove legacy later".
- **Discover** (existence + label, select options) is derived from all grants on the type. Read is
  needed for table selects, form/column values, `get`.
- **Access parent ≠ role parent.** An access parent delegates the whole decision; **composition parts
  without a role source delegate to their container by default**. A role parent only passes roles on;
  the child type's own grants still apply. Giving a part its own role rule disables the default.
- **Create is checked on the container** (`new(type, context: X)`, the composition parent for copies
  and forms); top-level objects without context on the **security root**.
- Constraints, derived attributes and labels run **unsecured**; options, defaults, executability and
  commands secured. For "must see more than the user" use `<privileged-script>`.
- **The classic UI is no measure** of object access (classic tables show references without checks).
  Test the API view with the REST probe.

## Working agreement (settle in step 0, record in the project memory)

- Conversation language; decisions **one at a time** as D1, D2, … with the facts checked in the code
  and **one recommendation**; the developer decides. A batch of open points overwhelms.
- Test points get **fixed IDs** (T1-01, …); every check deferred to testing gets an ID, decisions
  reference them.
- Commit after each step (ticket prefix); **push only when asked**.
- The app: who starts/restarts it (often the developer, in the IDE); never start a second instance on
  the same H2 DB; autoconf and layout changes need a restart, say so explicitly.
- Principle (agree on it): **secure what is necessary, allow what is possible**: a superset of what a
  role needs is fine as long as there is no write gap and no access across tenant/supplier boundaries.
  Accepted supersets are documented.
- Functional bugs found on the way go to `follow-up-topics.md`, not into the migration.
- Keep the project memory (from `templates/project-memory-summary.md`) and the resume point up to date
  after every step, so the next session continues seamlessly.

## The guided workflow

Each step: what you do, what you ask, what is delivered, how it is checked. Don't skip the checks.

### Step 0: kick-off
- Ask: ticket and branch, TL version, which roles exist and which matter, is an API/agent planned
  (read only or write?), who tests (developer in the browser, you via the probe).
- Create the docs folder (e.g. `docs/object-security/`) from `templates/` (`README.md`, `plan.md`,
  `decisions.md`, `test-checklist.md`, `follow-up-topics.md`, `gaps-and-edge-cases.md`,
  `legacy-analysis.md`, `model-map.md`) and the project memory from the memory template.
- Deliverable: docs skeleton, working agreement. Commit.

### Step 1: baseline
- Start the app once (or ask the developer to) and capture the `SecurityCoverageCheck` findings from
  the startup log (grep `SecurityCoverageCheck - `) into `coverage-baseline.txt`; classify them
  briefly (dead grants, types without role source, types without grants). Message patterns:
  `engine-reference.md` §Tooling.
- **Existing config: prune, don't reset** (the frontend already depends on it). Keep marks and access
  parents, remove grants coverage reports as dead, rebuild role by role.
- Check the AccessManager app config for `config:override="true"`: it discards the framework's role
  rules (own account/contact readable, contact and invitation role parents). List what the app needs
  back.

### Step 2: legacy analysis
- Read `legacy-concept.md`. Fill `legacy-analysis.md`: per role the assignment and every inheritance
  path (up and down, **including which roles reach the security root**), groups and singleton
  rules, profiles per view, security objects/masters of the views, `filterPermission` filters,
  project helper functions used as security objects.
- Ask: which roles are **internal, which external** (don't infer from names: "EXT_" may mean "via
  another path"); which accounts are admins (unusable for tests); which views are shared by roles.
- Deliverable: `legacy-analysis.md`. Checkpoint: walk the developer through the role map.

### Step 3: model map
- Fill `model-map.md` from the `*.model.xml` files, the AccessManager config and the existing
  object config: compositions, role carriers, context-bound types, master data (global / per
  tenant), technical and transient types (`.ui` modules, dialog types).
- Look for surprises: parts not composed where you expect (need an explicit access parent), master
  data composed into a tenant, transient form types in business modules, reflective scripts on
  `tl.model` (grep `tl.model:` in layouts/functions).

### Step 4: test setup (before any configuration change)
- One **non-admin test account per role** (`assets/test-users.objects.xml` → local deploy aspect),
  an idempotent setup task that wires the roles and test data and **logs a role verification**
  (`assets/test-setup.config.xml`), and the **REST security probe** (`assets/security-probe.config.xml`).
  Never into `src/main/webapp/WEB-INF/data` (imported in production); check that the production build
  uses another deploy aspect.
- Ask the developer to set the test passwords in the admin UI (they can't be set by data or script)
  and to tell you the password for probe calls.
- Test data: one object per role carrier, plus objects in a **second tenant/area that must stay
  invisible** (that is what the separation tests need).
- Checks: T0-01 (accounts, roles in the setup log), T0-02 (probe answers as a test user).

### Step 5: cross-cutting concepts (decide before the role phases)
Present each as a decision with a recommendation (`patterns.md`):
- **Master data concept:** global vs. tenant master data; who reads (recommended: everyone
  authorized within a tenant reads all its master data, via a tenant-member helper role), who
  maintains (tenant admin; global data only a system admin); system admin inside every tenant.
- **Persons:** internal roles read contacts, externals only persons referenced from their objects.
- **Technical types:** `without-security` / `internal` (marks propagate to subtypes!).
- **Model metadata:** access parent `tl.model:TLModelPart` → root, if any script reads model
  attributes (dynamic grid columns, merge dialogs).
- **Operations that must see more than the user:** inventory (duplicate checks, e-mail matching,
  imports, object initializers) → privileged functions; role lists from **all** legacy profiles that
  have the command on the view.
- **Root as role parent:** check first which write roles reach the root.

### Step 6: role phases (repeat per role, in the order agreed in `plan.md`)
1. Reachable views (profiles) and per type its use: select → Discover; table/form/column/`get` →
   Read; edit → Write; new/delete → Create/Delete or Write on the access parent; custom command group.
2. Classify each type; check where the role really lands (rule paths, setup log); which grants would
   be dead.
3. Decisions D# one at a time → implement (hand-edit or in-app SecurityDefinitionEditor) → re-run
   coverage: no new dead grants or "no role source" findings.
4. Write the test points T<phase>-xx (UI + API) into the checklist **before** testing.
5. **Test round:** the developer tests the UI points as the test user and reports; you run the API
   points with the probe (`curl -s -u <user>:<pw> …`) and record results (date, tester, numbers).
   Compare with a broader user (e.g. a planner or the system admin) to see what is hidden.
6. A failure → diagnose (below) → decision → fix → retest. Commit per step; update resume point and
   memory.

Typical order: central writing role → its read-only variant → other internal roles → **external roles
(most critical for the API)** → tenant admin and master data maintenance → system admin / root-only
roles → cleanup.

### Step 7: cleanup and safeguards
- Coverage clean (only intended findings, documented); `test-security-coverage="true"` in the test config.
- API leak round for every role with the probe: `all(T)` per role carrier and master data type,
  attribute access, writes (`canWrite`), the known `changeLog()` leak.
- Legacy regressions: views with `securityObject="model"` on composition-part rows, grids, dialogs.

### Step 8: wrap-up
- `gaps-and-edge-cases.md`: Part A (engine gaps that apply, from `engine-reference.md` §3) and
  Part B (accepted gaps and supersets of this app) for the team discussion.
- Engine bug reports as ticket texts (symptom, reproduction, cause in code, proposal).
- Follow-up topics as tickets. Optional: role/object security diagrams. PR with a summary of the
  decisions and the open points.

## Diagnosing "works as root, not as the test user"

1. **Object security or legacy?** Run the probe as the test user: `canRead/canWrite` on the object,
   `canReadAttribute/canWriteAttribute` on the attribute.
   - Probe says denied → object security, go on with 2.
   - Probe says allowed → **legacy** (view/command check): profile of the view, the view's
     `securityObject` (`model` = command target, i.e. the selected row!), security master, row
     checker of grids. See `legacy-concept.md` L4–L6.
2. Does the role arrive on the object? Setup-task log (`filterPermission(objects, role, account)`).
   - No → role rule path, inheritance stops at the tenant, externals bypass the tenant, own role rule
     disabled the composition default, structured elements ignore role parents.
   - Yes → grant missing for that role/operation, part grant overrides, access parent resolves to
     null/several objects, `tl.model` not readable.
3. Is the data there at all? An empty field can just be missing data (check as root).
4. Side effects: a throwing `sendMail` rolls back the whole command; deleted objects in channels
   (engine issue); detached composition parts in transactions.
5. Also as root? Then it is not security; make sure the comparison really ran as root.

## Keeping the knowledge

Record every new engine fact in the project docs with its evidence. When it is general, propose to
the developer to add it to this skill (and keep version notes): the next migration starts from there.
