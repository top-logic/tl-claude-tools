# Albert integration: the full how-to

Fallback how-to of the skill `tl-albert`. Verified on TopLogic 8.0.0-alpha12. If the engine of the app ships the article `doc:ai/adding-albert` (see `engine.names` of `detect`), that article has priority.

Paths are relative to `src/main/webapp/WEB-INF/` of the app module. Exception: `pom.xml` and `deploy/` in the module directory.

Two user interfaces:

- **React**: views in `views/*.view.xml`, entry point `/view/`. The archetype `tl-archetype-app` makes this UI by default.
- **Classic**: layouts of the `LayoutServlet`. Albert is a tab through `tl-chat-classic`.

Steps marked **[React]** or **[Classic]** apply only to that UI.

## Prerequisites

- The model security (`<security-config>` of `SecurityConfigurationService`) is complete for each role.
- Each business role is held on `SecurityStructure#ROOT` through a `singleton-rule` with `group-with-name`. The tool policy (step 5) asks for the role there.
- Each role that has a grant in `<security-config>` is also held on the objects, through a role rule (`<rule type="reference">`). A role on the root gives no data access while `use-default-security-parent` of `BoundHelper` is `false` (the engine default).

The examples use `{{READ_ROLE}}` (read only) and `{{WRITE_ROLE}}` (read and write). Use the roles of the app.

## 1. Dependencies

Add the AI modules to `pom.xml`. Do not write a version.

```xml
<dependency>
	<groupId>com.top-logic</groupId>
	<artifactId>tl-ai-core</artifactId>
</dependency>
<dependency>
	<groupId>com.top-logic</groupId>
	<artifactId>tl-chat</artifactId>
</dependency>
<!-- Only [Classic]: the Albert tab. Never in a React-only app. -->
<dependency>
	<groupId>com.top-logic</groupId>
	<artifactId>tl-chat-classic</artifactId>
</dependency>
```

- Alternative: the starter `tl-starter-ai` (`<type>pom</type>`). It also brings `tl-ai-views`, `tl-ai-mcp-server`, `tl-ai-a2a` and `tl-layout-view-agent`. Use the starter only if the app needs these modules.
- `tl-ai-core` does not bring `tl-license-professional`. Keep it as a direct dependency if the app uses the Professional Edition.
- `tl-chat-classic` moves the start page and the login page to the `LayoutServlet`. Do not add it to a React-only app.

## 2. LLM provider and key

`tl-ai-core` ships four providers. A provider without a key is inactive. The app starts without a key, but Albert does not answer.

| Provider | Secret (key) | Models (optional) |
|---|---|---|
| `anthropic` | `tl_anthropic_api_key` | `tl_anthropic_models` |
| `openai` | `tl_openai_api_key` | `tl_openai_models` |
| `mistral` | `tl_mistral_api_key` | `tl_mistral_models` |
| `xai` | `tl_xai_api_key` | `tl_xai_models` |

- The secret store reads the environment variable or system property with the name of the secret. The value is the plain key, without the prefix `unencrypted:`.
- `tl_ai_default_model` sets the model of the agents. Without it, Albert uses the first model of the first provider with a key.
- Local development: the key can also be in the file `~/.top-logic/secrets/<secret name>`. This needs the line `devel-secrets.xml` after `devel-h2.xml` in `deploy/local/webapp/WEB-INF/conf/metaConf.txt`. The archetype writes this line.
- Own provider (other endpoint, gateway, Amazon Bedrock): `templates/LLMProviderService.config.xml`. Give the key as `<api-key name="..."/>`, not as an alias. Read `doc:ai/providers` for the provider classes and their attributes.
- A model that models.dev does not know needs an entry in `<model-catalog><model-overrides>` of `LLMProviderService`.
- If a shipped provider and an own provider have the same model, the start fails. Use `<providers config:override="true">` or name the provider (`default-provider`).

## 3A. [React] Albert page

If the engine ships a chat page (`engine.chatView: yes`), the app adds only the navigation entry with `<view-ref view="<chatView>"/>`. Else copy `templates/albert.view.xml` to `views/<app>/albert.view.xml`.

- Add the navigation entry of `templates/nav-item.app.view.xml` to the overlay `views/app.view.xml`. Merge it into the existing `<sidebar config:ref="sidebar">`. The `id` must be unique in the whole view.
- Default: show the page only to the Albert roles with `<access-control operation="AlbertChat"/>`. Do all three parts of `templates/albert-operation.config.xml`. Without the registration in `CommandGroupRegistry`, the app does not start.
- Albert on every page: `templates/chat-dock.app.view.xml`. Use the same `chat-id` as the page (`albert`). The dock then shows the same conversation and hides itself on the Albert page.

## 3B. [Classic] Albert tab

`tl-chat-classic` adds the tab `tl-chat/chat/tabLayout.layout.xml` at the start of the main tab bar (`mainTabbar.layout.xml`) through an overlay. Do not add the tab yourself.

- If the app has its own main tab bar with another name, add `<layout-reference resource="tl-chat/chat/tabLayout.layout.xml"/>` there.
- Give each Albert role the read right on the tab in `conf/security.xml` (`templates/classic-security.xml`). Without it, a non-admin account does not see the tab. The startup hook `access-configuration` imports `conf/security.xml` again when the file changes. Restart the app.

## 4. Each account reads its own chats

The chat list and the progress list are TL-Script. A TL-Script result contains only objects that the account may see. If neither the engine nor the app grants rights on chats, the chat list of each non-admin account stays empty.

- Engine ships `WEB-INF/conf/tl-ai-core.chatSecurity.config.xml` (Trac #30144, role `AiChatOwner`): do nothing.
- Else: merge `templates/chat-security.config.xml` into `conf/<app>.conf.config.xml`. Remove it again after an engine update that ships the file. `check` warns then.

## 5. Albert rights per role

`templates/AiToolAccessService.config.xml`, target `autoconf/com.top_logic.tl.ai.core.security.AiToolAccessService.config.xml`.

- `<tool-access>`: the tool groups for each role. Defaults: read roles get `model.read` and `data.read`. Write roles get also `data.write`. All roles get `<denies>` for `script`, `egress`, `model.structure`, `agent.authoring`.
- Give `data.write` only to roles that may change data in the UI. Albert asks the account before each change. The model security checks each change again.
- Give `script` only with care. `changeData` in a script changes any type.
- `<baseline/>`: no tools for all accounts.
- `<background-allowlist>`: required when a role is named. It limits runs without a person.
- `<agent-access default="none">` with a grant of `TL_SingleAgent` for each Albert role. Without `default="none"`, each account that can log in talks to Albert.
- A role in `<tool-access>` or `<agent-access>` must be held on `SecurityStructure#ROOT`.
- [Classic] The policy can also be edited as `root`: *Administration > Permissions > Agent security*. *Apply* writes the autoconf file with `config:override="true"`. Commit that file.
- [React] The React administration of 8.0.0-alpha12 has no editor for the policy. Write the file by hand. Do not open `/servlet/LayoutServlet` in a React-only app.

Engine articles: `ai/security/tool-access-recipes`, `ai/security/run-access`, `ai/security/administration`.

## 6. Domain knowledge

- `templates/AgentApplicationService.config.xml`, target `autoconf/com.top_logic.tl.ai.core.agent.AgentApplicationService.config.xml`. Contributor name `application-context`. Text in English and German: a glossary of the business terms, fixed business rules, units.
- Give each model element (module, type, attribute) a label and a tooltip in English and German, in `conf/resources/model.<module>.messages_<lang>.properties`. Albert reads them. An attribute without a label shows as `[model.<module>.<Type>.<attribute>]` in the tables of Albert.
- Keep `conf/resources/*.properties` normalized (sorted keys, UTF-8). Else the build reports `Resource check: Not normalized`.

## 7. Test

Use a new database and non-admin accounts. Step 6 of `SKILL.md` lists the checks.

- Log: 0 lines with `ERROR`. One line `Agent tool access: roles=<n> ... backgroundAllowlist=<n> agents=...`.
- `root` in the React administration (`/view/`): *Administration > System > Secrets* shows the secret of the provider as *Available*. *Administration > AI models* shows the model.
- `GET <app-url>/chat/capabilities` as a test account lists the agents, models and MCP servers that the account may use.

### Test accounts

Make the accounts as `root`. Do not give a test account administrator rights.

[React] In `/view/`:

1. *Administration > Access control > Accounts* (German UI: *Zugriffskontrolle > Konten*). Select *New*. Enter the user name and a password. Do not select *Administrator*. Select *Create*.
2. *Administration > Access control > Groups*. Select the group that the `singleton-rule` of the role names (`group-with-name`). Select *Edit*. Add the account to *Members*. Select *Save*.
3. Do step 2 for each role of the account. Make one more account without a group of an Albert role.

[Classic] Use the account and group administration of the classic UI in the same sequence.

Tell the user the names and passwords of the test accounts. Tell the user to delete them before production use.

## 8. Operation

- Set the secret of the provider in each target environment.
- Get the approval of the customer for the provider, the model and the data flow to the provider.
- Do not set `tl_reset_password` on each start together with the same `tl_initial_password`. The second start then fails ("Password has been used before").

## Migrate from the separate tl-ai artifacts

Only if `detect` shows `legacy.tl-ai`. The AI modules are engine modules since TopLogic 8.0.

1. Remove the property `tl.ai.version` and each `<version>${tl.ai.version}</version>` in `pom.xml`.
2. Remove `<llm-configuration>` in `<model-catalog>`. Since 8.0.0-alpha12 the app does not start with it.
3. Replace `api-key="%ALIAS%"` with `<api-key name="<secret name>"/>`. An alias value is an encrypted value. It needs the prefix `unencrypted:` in the variable, and the service editor writes the key into the file.
4. Move the old prompt text (the file of the former `LLMService` does not exist any more) to the contributor `application-context` of step 6.
5. Then do the normal steps.
