# Albert integration: pitfalls

Symptom, cause, fix. Most entries come from a verified integration on TopLogic 8.0.0-alpha12.

| Symptom | Cause | Fix |
|---|---|---|
| The app does not start: `The command group ... does not exist`. | An operation in `<access-control>` or in `<security-config>` has no entry in `CommandGroupRegistry`. | Register the operation (`templates/albert-operation.config.xml`, part 1). |
| The app does not start after a merge. A service has lost entries, or its settings are ignored. | A file configures a `service-class` twice. | Merge the entries into one `<config>` per service and file. `check` finds this. |
| The app does not start: `<llm-configuration>` in the log. | An old configuration of the separate tl-ai artifacts. | Remove `<llm-configuration>` (`integration.md`, migration). |
| The app does not start: a model is ambiguous, `AgentRuntimeService` fails. | A shipped provider and an own provider offer the same model. | `<providers config:override="true">`, or set `default-provider`. |
| The app does not start: `AiToolAccessService` reports a missing background allowlist. | `<tool-access>` names a role, but there is no `<background-allowlist>`. | Add `<background-allowlist>` (`templates/AiToolAccessService.config.xml`). |
| The app does not start: an unknown agent, model or MCP server in the policy. | A name in `<agent-access>`, `<model-access>` or `<mcp-access>` does not exist. | Correct the name. The agent of the chat is `TL_SingleAgent`. |
| A view does not load: duplicate `id`. | Since 8.0.0-alpha12 an `id` must be unique in the whole view. | Give each `<tab>`, `<section>` and `nav-item` its own `id`. Address engine elements with `config:ref`. |
| `Undefined variable` in the chat list or the progress list. | `<rows>` of a `<data-list>` (tl-chat) takes its inputs as positional arguments. `$chatSession` does not exist there. | Write `session -> ...` at the start of the script. |
| `Undefined variable` or a wrong result in another view script. | Since 8.0.0-alpha12, view scripts outside `<data-list>` take their inputs as variables `$<name>`. A lambda head is wrong there. | Remove the lambda head. Use `$<input name>`. |
| The chat works, but the chat list of a non-admin account shows "No chats yet". | Nobody grants Read on `tl.ai.execution:AgentSession`. The engine of the app does not ship the chat security. | Step 4: `templates/chat-security.config.xml`. |
| Albert answers "There is no agent available for you". | The account holds no role of `<agent-access>` on the security root, or `default="none"` and no grant. | Give the account a group of an Albert role. Check the `singleton-rule` of the role. |
| Albert has tools but finds no objects, or says that there is no data. | The role is held on the security root only. `<security-config>` asks for the role on the object. | Add a role rule that puts the role on the objects (`<rule type="reference">`, a `<path>` with `group-with-name`). |
| Albert finds no tools at all for a role. | The role is not in `<tool-access>`, or the account does not hold it on the security root. | Add the role to `<tool-access>`. Check the `singleton-rule`. |
| A role with `data.write` cannot change an object: `Permission denied ... Nothing was changed`. | The model security has no `Write` grant for this role on this type. | Correct. Both checks must pass. Add the `Write` grant only if the role may change the type in the UI. |
| The start fails: "no provider exposes model". | `tl_ai_default_model` (or `default-model`) names a model that no provider lists. | Set `tl_<provider>_models` so that it contains the model, e.g. `tl_openai_models=gpt-5.6-luna`. A model that models.dev does not know needs also `<model-overrides>`. `detect` shows `llm.WARN`. |
| `/chat/capabilities` answers 404. | The engine has no `/chat/*` REST routes any more (Trac #30126). | Check the agents of an account through the UI. `detect` shows `engine.chatRestApi`. |
| Albert does not answer. The chat names a secret. | No provider has a key. | Set the secret (environment variable or `~/.top-logic/secrets/<name>`). Check *Administration > System > Secrets*. |
| The secret file in `~/.top-logic/secrets` has no effect. | `deploy/local/webapp/WEB-INF/conf/metaConf.txt` has no line `devel-secrets.xml` after `devel-h2.xml`. | Add the line. |
| The key appears in a configuration file after a save in the service editor. | The key comes from an alias (`api-key="%ALIAS%"`). | Use `<api-key name="..."/>`. |
| [Classic] A non-admin account does not see the Albert tab. | `conf/security.xml` gives no read right on the tab. | `templates/classic-security.xml`. Restart the app. |
| [React] The start page or login page is a classic page. | `tl-chat-classic` in a React-only app. | Remove the dependency. |
| Tables of Albert show `[model.<module>.<Type>.<attribute>]`. | The model element has no label. | Add the label in English and German. |
| The build reports `Resource check: Not normalized`. | A `.properties` bundle is not sorted or not UTF-8. | Normalize the bundle (keys sorted, UTF-8). |
| The second start fails: `Password has been used before`. | `tl_reset_password` is set on each start together with the same `tl_initial_password`. | Set `tl_reset_password` once only. |
| A test as `root` passes, but a user cannot do it. | An administrator bypasses all checks. | Test with non-admin accounts only. |
| Albert says that the user sees a table, but no table opens on the Albert page. | The dock and the page share the chat-id, and both have `present="..."`. The conversation then has two places for a table. Albert puts the table into the hidden dock. | Remove `present` and the pane channel from the dock (`templates/chat-dock.app.view.xml`). |
| [React] An account without an Albert role types a message in the dock, and nothing happens. | `/chat/send` answers 403 "no agent available". The UI of 8.0.0-alpha12 does not show the reason. | Expected for this account. Give the account an Albert role if it must use Albert. |
| `/chat/capabilities` answers 409 `stale_window`. | The request has no `windowId` of an open page. In the classic UI also the conversation is missing. | React: add `?windowId=<windowName of the page>`. Classic: open the Albert tab, then add `?windowId=<window id of the LayoutServlet URL, before the "-">&conversation=classic` (see `SKILL.md`, step 6). |
| [Classic] "Momentan nicht bearbeitbar" on an account or a group. | An edit was not ended with *Speichern* or *Abbrechen*. The lock stays also after a restart. | End each edit with *Speichern* or *Abbrechen*. |
| `tl-app.sh stop` does not find the app. The app starts on another port. | `tmp/` (with `tmp/app-port.txt`) was moved while the app ran. | Stop the app first. Keep `tmp/app-port.txt` when you replace the database. |
