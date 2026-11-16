---
name: tl-albert
description: Add Albert, the AI chat agent of TopLogic (TL_SingleAgent, modules tl-ai-core, tl-chat, tl-chat-classic), to an existing TopLogic application on 8.0.0-alpha13 or later. The skill follows the engine article ai/adding-albert of the app's engine version. It adds a model-security gate, four questions, a checklist, and a test as non-admin accounts. Use for "add Albert to this app", "Bau Albert in diese Anwendung ein", an AI assistant or chat agent in a TopLogic app, tl-chat, tl-ai-core, "add AI to this application".
allowed-tools: Bash, Read, Edit, Write, Glob, Grep, AskUserQuestion, Agent
---

# Add Albert to a TopLogic application

Albert is the chat agent of TopLogic (`TL_SingleAgent`). Albert acts as the logged-in account. Albert reads and changes only the data that the account can read and change. Thus the model security of the app is the base of every step.

The engine article `ai/adding-albert` of the app's engine version is the how-to. This skill adds only the workflow around it. **If the article and this skill disagree, the article wins.** Tell the user about the difference.

Do the steps in sequence. Do not skip a gate. Write all changes on a new branch of the app repository. Paths are relative to `src/main/webapp/WEB-INF/`, except `pom.xml`.

## 1. Version

Read the engine version from `pom.xml` (`tl.version`, the `tl-bom` import, or the parent). If it is lower than `8.0.0-alpha13`, STOP. Tell the user to upgrade the engine first. Albert traps of this upgrade:

- The shell has `<section id="application">` and no `system-separator`. A `config:reference="system-separator"` in `views/app.view.xml` stops the start.
- The default model lists of the providers changed. A `tl_ai_default_model` that is not in the new list stops the start ("no provider exposes model").
- The engine ships the rights on own chats. Remove the app's own role rules and grants on the types of `tl.ai.execution`.

## 2. Article

1. Read `ai/adding-albert` with the `tl-mcp` tool `read_doc`. Read the articles that it links when a step needs details.
2. If `read_doc` does not find it (the app has no AI dependency yet), extract it from the jar of that version:
   `mvn -B -q dependency:get -Dtransitive=false -Dartifact=com.top-logic:tl-ai-core:<version>`, then
   `unzip -p ~/.m2/repository/com/top-logic/tl-ai-core/<version>/tl-ai-core-<version>.jar META-INF/tl-docs/ai/adding-albert.md`.
3. Do not use names from memory. Use the names of the article.

## 3. Gate: model security

STOP if one of these is true:

- No file has a `<security-config>` (`grep -rl '<security-config' src/main/webapp/WEB-INF`). Every type is then closed to non-admin accounts.
- No role has a `<singleton-rule>` with `target="SecurityStructure#ROOT"`. Albert finds the roles of an account only on the security root.
- A grant is wider than the role needs in the UI. Albert then shows or changes data that a user must not see.

Tell the user why. Offer to set up the model security as a separate task first (articles `ai/security/role-model-worked-example`, `access-configuration`).

## 4. Questions

Use one `AskUserQuestion` call. Write the questions and the options in the language of the user. Mark the recommended option.

1. **Roles that talk to Albert** (multi-select): the roles with a `singleton-rule` on the root.
2. **Roles that may let Albert change data** (multi-select): a subset of question 1. Recommend only roles with `Write` grants. Albert asks the account before each change.
3. **Provider, model and key**: a shipped provider (article step 2) or an own endpoint. Tell the user: the data that Albert reads goes to the provider. The data owner must approve this data flow before production use.
4. **Where Albert appears**: React: own page (recommended), a dock on every page, or both. Classic: the tab (skip the question).

Do not ask more. Use the defaults of the article for all other decisions. List them in the result.

## 5. Apply

Do the steps of the article. Rules:

- **Merge, do not duplicate.** A file configures a `service-class` only once. Add new entries to the existing `<config>`. Make a new file in `autoconf/` only for a service that no file configures.
- **No version** on the AI dependencies.
- **Never write a key** into a file of the repository. Use the named secret (`<api-key name="..."/>` for an own provider). The key goes into the environment or `~/.top-logic/secrets/<secret name>`. Ask the user to set it. Do not read it. Do not print it.
- **Workspace tools**: give `<grant group="workspace"/>` to each Albert role in `<tool-access>` until the engine fixes Trac #30369. Without it, the sections *Progress* and *Memory* of the Albert page stay empty. The article does not have this grant yet.
- **Domain knowledge**: write only facts from the model labels, the tooltips or the user. Keep it under 30 lines per language. Show the draft in the result.
- **Labels**: list each model element without a label or tooltip (en, de) in the result. Albert knows the model only from these texts. Add them only if the user agrees.

## 6. Checklist before the build

Answer each line in the result (`ok`, or what you changed).

1. Each `service-class` occurs at most once in each file. This prints nothing: `for f in $(grep -rl 'service-class=' src/main/webapp/WEB-INF); do grep -o 'service-class="[^"]*"' "$f" | sort | uniq -d | sed "s|^|$f: |"; done`.
2. React-only app: `pom.xml` has no `tl-chat-classic`.
3. `pom.xml` has no `tl.ai.version` and no `<version>` on the AI dependencies.
4. No `<llm-configuration>` and no `api-key="%` in `WEB-INF`.
5. Each role in the policy has a `singleton-rule` on `SecurityStructure#ROOT`.
6. The policy has `<agent-access default="none">` and a `<background-allowlist>`.
7. Each Albert role has `<grant group="workspace"/>`.
8. With the operation of article step 3: `commandGroup.AlbertChat` and `commandGroup.AlbertChat.tooltip` are in the `_en` and `_de` bundles.
9. The chosen model is in the model list of its provider (`tl_<provider>_models`, else the default list in `WEB-INF/conf/tl-ai-core.conf.xml` of the `tl-ai-core` web-fragment war).
10. A secret file needs the line `devel-secrets.xml` in `deploy/local/webapp/WEB-INF/conf/metaConf.txt`.
11. No key in the change. This prints nothing: `git add -A && git diff --cached | grep -nP 'sk-ant-[A-Za-z0-9_-]{20,}|sk-(proj-|svcacct-)?[A-Za-z0-9_-]{20,}|xai-[A-Za-z0-9]{20,}|AKIA[0-9A-Z]{16}|AIza[0-9A-Za-z_-]{35}|gh[pousr]_[A-Za-z0-9]{36}|-----BEGIN [A-Z ]*PRIVATE KEY-----|api-key\s*=\s*"(?!%|unencrypted:%|")[^"]{12,}"|(?i)(api[_-]?key|secret|token|password)\s*[=:]\s*["\x27]?[A-Za-z0-9_-]{32,}'`.

## 7. Build and boot

1. `mvn install -DskipTests=true -B 2>&1 | grep -E "^\[ERROR\]|BUILD (SUCCESS|FAILURE)"`. Fix every `[ERROR]`.
2. Start the app with the skill `tl-claude-tools:tl-app`. Read the log:
   - 0 lines with `ERROR`.
   - One line `Agent tool access: roles=<n> ... backgroundAllowlist=<n>`.
   - The `SecurityCoverageCheck` INFO lines for the app types.
   - Each `WARN` line of `AiToolAccessService`. Fix it, or tell in the result why it stays.

## 8. Test as non-admin accounts

An administrator bypasses the policy and the model security. A test as `root` proves nothing about the roles.

1. Make sure that the app process has the key. If not, ask the user to set it.
2. Use a new database. If `tmp/` has data of the user, ask first. Stop the app (`tl-app.sh stop`). Move `tmp/` to `<module>/tmp.bak-<date>`, but keep `tmp/app-port.txt` in `tmp/`. Start the app. Tell the user where the backup is.
3. As `root`, make one account for each decided role and one account without an Albert role. Do not make an administrator.
   - React (`/view/`): *Administration > Access control > Accounts* (German *Zugriffskontrolle > Konten*). Make the account. Then, in *Groups*, add it to the group that the `singleton-rule` of the role names.
   - Classic: *Berechtigungen > Benutzerverwaltung*. End each edit with *Speichern* or *Abbrechen*.
4. Do these checks for each account:
   - The Albert page (or dock, or tab) opens. A question about the data gets an answer.
   - Ask for a table. The table opens in the rail. *Progress* and *Memory* fill during the chat.
   - The chat list shows only the chats of this account.
   - Read role: tell Albert to change an object. Albert refuses. The object keeps its value.
   - Write role: tell Albert to change an object. Albert shows an approval card with *Allow once*, *Allow this chat* and *Reject* (German: *Einmal erlauben*, *In diesem Chat erlauben*, *Ablehnen*). After *Allow once*, the object has the new value.
   - Account without an Albert role: no Albert page (with the operation `AlbertChat`). The dock button stays visible (Trac #30369). A message from the dock gets "No agent is available to you".
   - Dock: open it on another page. Albert knows the selection of that page. On the Albert page the dock hides itself.
5. If a browser tool is available (Playwright MCP, or `playwright-core` with a local Chromium), give the checks to a subagent. Write a self-contained brief: URL, accounts, passwords, checks, expected results, one screenshot of the Albert page for each role, one chat at a time.
6. Else give the user the checks as a numbered list with the accounts. Wait for the results.
7. If a check fails, fix the cause. Then go back to step 6.

## 9. Deliver

1. Commit on a branch, for example `albert-integration`. Use the commit style of the repository. Never commit or print a key.
2. Give the user:
   - A table "role -> what Albert can do" (talk, read, change, tool groups).
   - The changed files and why, the default decisions, the answers of the checklist.
   - The test result for each account, with the screenshots.
   - Open items: the secret in each environment (its name), the approval of the data flow, the missing labels, the domain-knowledge draft for review.
   - The test accounts. Tell the user to remove them before production use.

## Pitfalls that the article does not have

| Symptom | Cause | Fix |
|---|---|---|
| *Progress* and *Memory* on the Albert page stay empty. | The role has no tool group `workspace`. | Add `<grant group="workspace"/>` to the role. |
| [Classic] "Momentan nicht bearbeitbar" on an account or group. | An edit did not end with *Speichern* or *Abbrechen*. The lock stays after a restart. | End each edit. A browser script must end it too. |
| `tl-app.sh stop` does not find the app. The app starts on another port. | Somebody moved `tmp/` while the app ran, or moved `tmp/app-port.txt` too. | Stop the app first. Keep `tmp/app-port.txt`. |
| [React] An account without an Albert role sees the dock button. | The dock has no access control (Trac #30369). | Expected. The chat refuses the message. |
| The start fails after an engine upgrade: "no provider exposes model". | `tl_ai_default_model` names a model that left the default list. | Set `tl_<provider>_models`, or choose a model of the new list with the user. |
