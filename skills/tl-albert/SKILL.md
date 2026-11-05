---
name: tl-albert
description: Add Albert, the AI chat agent of TopLogic (TL_SingleAgent, modules tl-ai-core, tl-chat, tl-chat-classic), to an existing TopLogic application - dependencies, LLM provider and key, the Albert page or tab, Albert rights per role, domain knowledge, and a check as non-admin accounts. Use for "add Albert to this app", "Bau Albert in diese Anwendung ein", an AI assistant or chat agent in a TopLogic app, tl-chat, tl-ai-core, "add AI to this application", or an app that still uses the old separate tl-ai artifacts.
allowed-tools: Bash, Read, Edit, Write, Glob, Grep, AskUserQuestion, Agent
---

# Add Albert to a TopLogic application

Albert is the chat agent of TopLogic (agent name `TL_SingleAgent`). Albert acts as the logged-in account. Albert sees only the data that the account may see. Albert changes only the data that the account may change. Thus the model security of the app is the base of every step.

Do the steps in sequence. Do not skip a gate. Write all changes in the app repository on a new branch.

## Tools of this skill

- **Doctor** (read-only, JDK 17+, no other tool): `java "${CLAUDE_PLUGIN_ROOT}/skills/tl-albert/AlbertDoctor.java" detect|check <app-dir>`. `detect` prints a fact sheet and the steps that apply. `check` prints errors and warnings and exits with 1 on an error. The doctor never prints a secret value. The doctor never writes a file.
- **Templates**: `${CLAUDE_PLUGIN_ROOT}/skills/tl-albert/templates/`. Placeholders have the form `{{NAME}}`. Each template names its target file in its header comment.
- **References** in `references/`: `integration.md` (the full how-to, all steps), `pitfalls.md` (symptom, cause, fix), `customize.md` (change the chat after the integration).
- **Engine articles**: the `tl-mcp` tools `list_docs` and `read_doc` serve the articles of the engine version that the app builds against. These articles have priority over the references of this skill.

## Step 1: Detect

1. Run `detect` on the app directory.
2. If `engine.chatSecurity`, `engine.chatView` or `engine.howtoDoc` is `unknown`, build the app once (`mvn install -DskipTests=true -B 2>&1 | grep -E "^\[ERROR\]|BUILD (SUCCESS|FAILURE)"`). Then run `detect` again.
3. If `engine.howtoDoc` is `yes`, read that article with `read_doc` (the name is in the `engine.names` line). Use it as the main how-to. Use `references/integration.md` only for what the article does not say.
4. Read `references/integration.md` for each step that is `APPLY` or `ASK`.

The line `engine.names` holds the names of the shipped parts (agent, chat view, how-to article, owner role). Use these names. Do not use names from memory.

## Step 2: Gate - model security

Look at `gate.modelSecurity` and step `G`.

- **STOP**: Do not continue. Tell the user why:
  - Without a `<security-config>`, every type is closed to every non-admin account. Albert then finds nothing for a normal user.
  - With grants that are too wide, Albert shows and changes data that a user must not see.
  - Albert gets tool rights only through a role on `SecurityStructure#ROOT` (`singleton-rule`). A role on the root gives no data access. A grant in `<security-config>` needs a role rule that puts the role on the objects.
  Offer to set up the model security as a separate task first. Point to the engine articles `ai/security/role-model-worked-example` (read first) and `access-configuration`.
- **ASK**: Show the types without a Read grant. Ask whether these types are composition parts or have an access parent. The startup log of `SecurityCoverageCheck` (INFO) gives the final answer.
- **PASS**: Continue.

If `legacy.tl-ai` is not `none`, do also the migration of `references/integration.md`, section "Migrate from the separate tl-ai artifacts". Do it before step 4.

## Step 3: Ask - at most 4 questions

Use one `AskUserQuestion` call. Write the questions and the options in the language of the user. Fill the options from the fact sheet (`roles.onRoot`, `ui`, the secrets, `llm.*`). Mark the recommended option.

1. **Roles that talk to Albert** (multi-select): the roles of `roles.onRoot`. An account without one of these roles gets no Albert.
2. **Roles that may let Albert change data** (multi-select): a subset of question 1. Recommend only roles that have `Write` grants in `<security-config>`. Albert asks the account before each change. The model security checks each change again.
3. **Provider, model and key**: a shipped provider (`anthropic`, `openai`, `mistral`, `xai`, secret `tl_<provider>_api_key`), or an own endpoint or gateway (`templates/LLMProviderService.config.xml`). Ask for the model too. If the model is not in the default list of the provider (`references/integration.md`, step 2), set `tl_<provider>_models` to a list that contains it, together with `tl_ai_default_model`. Else the start fails: "no provider exposes model". Tell the user: the data that Albert reads goes to the provider. The customer must approve this data flow before production use.
4. **Where Albert appears**: React: own page (recommended), a dock on every page, or both. Classic: a tab (no choice, skip the question).

If `detect` shows `llm.WARN`, tell the user in question 3: the app does not start with this model until `tl_<provider>_models` contains it. Never change the model or the model variables for a test run without the agreement of the user.

Do not ask more. Use the defaults of `references/integration.md` for all other decisions, and list them in the result.

## Step 4: Apply

Do the steps of the `detect` output that are `APPLY`. For each step, use the engine article first, then `references/integration.md`, then the templates.

Rules for each change:

- **Merge, do not duplicate.** A file may configure a `service-class` only once. The section "Configured services" of the fact sheet shows which file configures which service. Add new entries to that `<config>`. Create a new file in `WEB-INF/autoconf/` only for a service that no file configures.
- **No version** on the AI dependencies. The BOM `tl-bom` or the parent sets `${tl.version}`.
- **Never write a key** into a file of the repository. Use a named secret (`<api-key name="..."/>`). For local development, the key goes into the environment variable or into `~/.top-logic/secrets/<secret name>`. Ask the user to set it. Do not read it, do not print it.
- **Step 4 of `detect` (own chats readable)**: apply `templates/chat-security.config.xml` only if `detect` says `APPLY`. Newer engines ship this policy.
- **Domain knowledge (step 6)**: draft the text of `templates/AgentApplicationService.config.xml` from the model labels and tooltips (English and German). Write only facts from the labels, the tooltips or the user. Keep it under 30 lines per language. Show the draft to the user in the result.
- **Labels**: list each model element of `labels.missing.*` and `tooltips.missing.*` in the result. Albert knows the model only through these texts. Add the missing texts only if the user agrees. Keep the bundles normalized.

## Step 5: Check

1. Run `check`. Fix every `ERROR`. Fix each `WARN` or explain in the result why it stays.
2. Build the app (command of step 1). Fix every `[ERROR]`.

## Step 6: Test as non-admin accounts

An administrator bypasses the tool policy and the model security. A test as `root` proves nothing about the roles.

1. Make sure that the key of the provider is set for the app process (environment variable or secret file). Ask the user if it is not set. Run `detect` in the same environment: `llm.WARN` must not appear.
2. Use a new database. If `tmp/` has data of the user, ask first. Then:
   1. Stop the app with `tl-app.sh stop` if it runs. Do not touch `tmp/` while the app runs.
   2. Move the database to `tmp.bak-<date>` next to the module (`<module>/tmp.bak-<date>`), never into a Claude scratchpad. Keep `tmp/app-port.txt` in `tmp/`, so that the app keeps its port.
   3. Start the app with the skill `tl-claude-tools:tl-app`.
   Tell the user where the backup is.
3. Read the log. Expect 0 lines with `ERROR` and one line `Agent tool access: roles=<n> ... backgroundAllowlist=<n>`. Read the `SecurityCoverageCheck` findings for the app types.
4. As `root`, make one test account for each decided role and one account without an Albert role. Use the procedure of `references/integration.md`, section "Test accounts".
5. For each test account, do these checks:
   - Agents of the account. If `detect` shows `engine.chatRestApi: yes`: `/chat/capabilities` lists `TL_SingleAgent` for an Albert role, and no agent for the account without an Albert role. The request needs the window of an open page: `GET <app-url>/chat/capabilities?windowId=<window name>`. The window name is the parameter `windowName` of the requests `/react-api/poll` of that page. Without it the answer is 409 `stale_window`. Classic UI: open the Albert tab first. Use the window id of the `LayoutServlet` URL (the part before the `-`) and add `&conversation=classic`. If `engine.chatRestApi` is `no`, the engine has no `/chat/*` routes (Trac #30126). Then check the agents through the UI only: the items below.
   - The Albert page (or dock, or tab) opens. A question about the data gets an answer. Ask for a table: the table opens in the rail of the page.
   - The chat list shows only the chats of this account.
   - Read-only role: tell Albert to change an object. Albert refuses. The object stays unchanged.
   - Write role: tell Albert to change an object. Albert shows an approval card (*Allow once*, *Allow in this chat*, *Reject*). After *Allow once*, the object has the new value.
   - Account without an Albert role: the Albert page is absent (with the `AlbertChat` operation). In the dock, a message is not sent: `/chat/send` answers 403 "no agent available". The UI of 8.0.0-alpha12 shows no message for this and keeps the text in the input field.
   - Dock: open it on a page other than the Albert page. On the Albert page the dock hides itself.
6. If a browser tool is available (a Playwright MCP server, or `playwright-core` with a local Chromium), do the checks in the browser. Give the browser work to a subagent with a self-contained brief (URL, accounts, passwords, checks, expected results). Take one screenshot of the Albert page for each role.
7. If no browser tool is available, give the user the checks of item 5 as a short numbered list with the accounts. Wait for the results.
8. If a check fails, read `references/pitfalls.md`. Fix the cause, then go back to step 5.

## Step 7: Deliver

1. Commit the changes on a branch in the app repository, for example `albert-integration`. Use the commit style of the repository. Run `check` before the commit: it scans `git diff` for keys.
2. Give the user this result:
   - A table "role -> what Albert can do" (talk to Albert, read, change data, the tool groups).
   - The files that you changed and why.
   - The decisions that you took with defaults.
   - The test results for each account.
   - Open items: set the key in each environment (name of the secret), the customer approval of the provider and the data flow, the missing labels, the domain-knowledge draft for review.
3. Never commit a key. Never print a key.
