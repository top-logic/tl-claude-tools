# Object security migration (<TICKET>)

Working documents for introducing TopLogic 8 model-based object security (`SecurityConfigurationService`)
in <APP>, while keeping every business role working in the frontend as before. Started <DATE>.

**Where to start:** `decisions.md` (CURRENT RESUME POINT at the top), then `plan.md`.

| File | Contents |
|---|---|
| `legacy-analysis.md` | The app's legacy security: roles, role rules, profiles, security objects, filters, groups (step 2) |
| `model-map.md` | Types, compositions, role carriers, master data, technical types (step 3) |
| `plan.md` | Phases per role, status of each phase, test setup (step 0) |
| `decisions.md` | All decisions D1, D2, … with rationale, plus the resume point |
| `test-checklist.md` | Test points with fixed IDs and results |
| `follow-up-topics.md` | Functional issues found along the way (F1, F2, …), outside the migration |
| `gaps-and-edge-cases.md` | Accepted gaps and engine proposals, for the team discussion |
| `coverage-baseline.txt` | `SecurityCoverageCheck` findings before the migration |

Local test setup (deploy aspect `local` only, never in production):
- test accounts: `deploy/local/webapp/WEB-INF/data/zz_security_test_users.objects.xml`;
- role wiring and verification log: `deploy/local/webapp/WEB-INF/autoconf/com.top_logic.util.sched.Scheduler.zz-security-test-setup.config.xml`;
- REST security probe: `deploy/local/webapp/WEB-INF/autoconf/com.top_logic.service.openapi.server.OpenApiServer.zz-security-probe.config.xml`.

Configuration files:
- object grants: `src/main/webapp/WEB-INF/autoconf/com.top_logic.model.security.SecurityConfigurationService.config.xml`;
- role rules, role parents: `src/main/webapp/WEB-INF/autoconf/com.top_logic.tool.boundsec.manager.AccessManager.config.xml`;
- role profiles: `src/main/webapp/WEB-INF/conf/security.xml`.
