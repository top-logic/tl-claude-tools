# Test checklist: object security (<APP>)

Every test point has a fixed ID; decisions refer to these IDs; results are entered here with date and
tester. Test accounts and data: local test setup (see `plan.md` 0.4): <T_ACCOUNT per role>.
Always test as a **non-admin** (root and `admin=true` accounts bypass all checks).

Two kinds of tests per phase:
- **UI tests** in the browser as the test user: the role works as before (lists, forms, commands,
  reports, dialogs, legends/footers, exports).
- **API tests** via the REST security probe (`curl -u <user>:<pw> …/api/securityProbe/<path>`): what
  the role can read/write through the API (no leaks across tenants, areas, suppliers; no
  write access beyond the role).

Status: ⬜ open · ✅ passed · ❌ failed (with note) · ➖ obsolete

## Phase 0: test setup

| ID | Test point | Origin | Status | Result / note |
|---|---|---|---|---|
| T0-01 | Test accounts exist, are non-admin, have a password; setup task log shows the expected roles (`filterPermission`) | plan 0.4 | ⬜ | |
| T0-02 | REST probe answers for a test user (HTTP 200, `user` = the test user) | plan 0.6 | ⬜ | |

## Phase 1: <ROLE>

| ID | Test point | Origin | Status | Result / note |
|---|---|---|---|---|
| T1-01 | <view/command> works as <T_ACCOUNT> (<what to check>) | D1 | ⬜ | |
| T1-02 | **API leak check** as <T_ACCOUNT>: `all(<Type>)` returns only permitted objects; attributes of <Type> not readable/writable beyond the role | goal | ⬜ | |

## Phase n: …

## Legacy / frontend regressions

| ID | Test point | Origin | Status | Result / note |
|---|---|---|---|---|
| TL-01 | <legacy view> still behaves as before for <role> | | ⬜ | |

## Known issues seen during tests (not caused by the migration)

| Symptom | Also as root? | Status | Note |
|---|---|---|---|
