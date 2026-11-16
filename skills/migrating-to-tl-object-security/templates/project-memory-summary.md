---
name: object-security-migration-<app>
description: <APP> object security migration: where the docs live, process rules, decisions, test setup, current state and next steps
metadata:
  type: project
---

<!--
Template for Claude's project memory (copy to the project's memory directory and keep it updated
after every step). It is the entry point for the next session; the details live in the versioned
docs. Keep it short: pointers, rules, the decision list in one line each, the current state.
-->

**Topic:** moving <APP> (TopLogic <VERSION>) from legacy view/command security to model-based object
security (`SecurityConfigurationService`). Goal: every role works as before in the UI, and a REST API
or AI agent in the user's context can't see or write more than the user may. Branch `<branch>`.

**Docs (versioned, edit only these):** `<docs folder>/`. Start with `README.md`, then the CURRENT
RESUME POINT in `decisions.md`.

**Config files:** object grants `…SecurityConfigurationService.config.xml`; role rules and role parents
`…AccessManager.config.xml`; helper roles registered in `<app conf>` + i18n.

**Test setup (local deploy aspect only):** accounts <T_…> (password set in the admin UI: <…>), setup
task `<name>` (wires roles, logs `filterPermission` checks to the app log), REST probe
`/api/securityProbe/<paths>` (`curl -s -u <user>:<pw> http://localhost:<port>/<ctx>/api/securityProbe/<path>`;
new paths need a restart).

**Process rules (<developer>):** <language>. Present decisions one at a time as D#, with a
recommendation. Test points get fixed IDs. Commit after each step with prefix "<TICKET>: ". Push only
when asked. Never start the app while the developer uses it / never two instances on one H2 DB.

**Decisions (short):** D1 <…>; D2 <…>; …

**State (<DATE>):** <phase>, <what is tested>. **Next:** <…>. **Later:** <…>.

**How to apply:** before any security work read the RESUME POINT and the skill; verify the config
before relying on details here (it changes with every decision).
