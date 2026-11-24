---
name: tl-upgrade
description: Move a TopLogic application to a newer engine release (e.g. 8.0.0-alpha12 to alpha13) - find the target release, fetch the published migration guide of every release step, run its Detect searches against the application, apply what applies, write the application's own migrations for what the engine leaves behind (recovered tables in WEB-INF/autoconf/inapp-schema-N), replay the upgrade on a database of the old release, build, test, start and walk the views, and record the move. Use for "update TL", "Update von TopLogic", "move to alpha13", "upgrade the engine version", "bump tl.version".
allowed-tools: Bash, Read, Edit, Write, Glob, Grep, AskUserQuestion, Agent
---

# Upgrade a TopLogic application to a newer engine release

Every engine release publishes a **migration guide** beside its release notes. It is written for an agent: each step has a **Detect** search, and a step whose search finds nothing does not apply. The guide is the curated source - do not reconstruct the steps from Trac queries or from the engine's git history.

## 1. Versions

1. **Current version**: the `<parent>` of `pom.xml` (`tl-parent-app` / `tl-parent-core`), the `tl.version` property, or the `tl-bom` import.
2. **Target version**: the version the user names, else the newest release:
   ```bash
   curl -s https://dev.top-logic.com/nexus/repository/toplogic/com/top-logic/tl-parent-app/maven-metadata.xml \
     | grep -oE '<version>[0-9]+\.[0-9]+\.[0-9]+(-alpha[0-9]+)?</version>' | tail -3
   ```
   Versions with a build suffix (`8.0.0-alpha13.829`) are nightly previews; they have no guide. Move to a preview only when the user asks for one (an engine fix that is in no release yet).
3. **Release steps**: from the current to the target release, every release in between is one step (alpha11 -> alpha12 -> alpha13). Each step has its own guide, and the guides are applied in order.

## 2. Guides

For each step, fetch the guide (plain `curl`, no login):

```
https://dev.top-logic.com/tl/content/releases/TL_<new>/migration-<old>-to-<new>.md
```

e.g. `https://dev.top-logic.com/tl/content/releases/TL_8.0.0-alpha13/migration-8.0.0-alpha12-to-8.0.0-alpha13.md`. Save it to the scratchpad and **read it completely** before changing anything. The release notes of the version are at `https://dev.top-logic.com/tl/content/releases/TL_<new>/`, a ticket of it at `…/TL_<new>/<ticket>/`. If a guide is missing (HTTP 404), STOP and tell the user; do not upgrade without it.

## 3. Detect and apply

1. Set the version (section "Version bump" of the guide).
2. Run **every** Detect line of the guide in one batch, restricted to the application's sources: `src`, `pom.xml` and deployment folders - never `target/`, a workspace `.m2/` or `node_modules/`.
3. For each hit decide: the step applies, or the hit is a false positive (a word that only looks like the pattern). Apply the steps that apply, exactly as the guide says. Comments describe the result, not the migration ("the pages sit in the application section", not "moved from the separator").
4. A step that needs a decision (keep or switch an AI model, grant a right or run without security, keep data the migration would delete): ask the user, do not guess.
5. A step that **deletes data or changes access rights** on start is reported to the user before the application is started against a production database.

## 4. What the engine leaves behind

The guides cover what an application must change. Two things show up only at the first start:

- **Recovered tables.** When a release moves a model module out of a module the application includes (e.g. the cookie pools of `tl-ai-core` into the optional `tl-ai-browser` in alpha13), `DynamicModelService` drops the no longer declared model module, but `MigrationService` keeps its table alive: the log says `Recovering configurations for lost tables: <Table>`, and new untracked files `WEB-INF/autoconf/inapp-schema-N.config.xml` / `.meta.xml` appear. Do not commit them. Either add the module that now holds the type (if the application uses it), or write a migration of the application's own that removes table, type and module - the engine article of the moved module describes it (for the cookie pools `doc:ai/browser#removing-the-module-again`):
  ```xml
  <pre-processors>
  	<processor class="com.top_logic.knowledge.service.migration.DeleteTableProcessor">
  		<tables><table object_name="CookiePool"/></tables>
  	</processor>
  	<delete-class name="tl.ai.cookiepool:CookiePool"/>
  	<delete-module name="tl.ai.cookiepool" fail-on-existing-types="true"/>
  </pre-processors>
  ```
  Dropping only the table is not enough: on a database of the old release the type is still in the model when the migration runs, and the start logs `UnknownTypeException ... Storage table ... does not exist`.
- **Dependencies of a new migration.** An application migration (`WEB-INF/kbase/migration/<app-module>/<Name>.migration.xml`) depends on its predecessor in the application's chain and on the **newest version of every migration module** the database knows - read them from the start log of the upgraded application (`Updated version of '<module>' to '<version>'`, and `Current data version: …` for the modules that did not change). Keep every module the previous application migration listed, even one the application no longer pulls in visibly (e.g. `tl-bpe`): a missing one stops the start with `Lost module dependency … Missing module dependencies: [...]`.

## 5. Verify

1. `mvn clean install` (clean: incremental compilation hides a class the new release removed), then the tests (`-DskipTests=false`). `BUILD SUCCESS` and the `[ERROR]` lines are the verdict.
2. Start with the `tl-app` skill and read the log: no `ERROR`, no configuration errors, no `Lost module dependency`, no `Recovering configurations for lost tables` after the application's own migration exists, and no new untracked file under `WEB-INF/autoconf/`.
3. **Replay the production path.** The developer database already went through the upgrade while you worked on it, so it no longer shows what a database of the old release does. Create one: a git worktree at the last commit of the old release, build it and start it once with `tl-app` (its own `tmp/` and port), stop it, check out the upgraded commit in the same worktree, build and start again. The log must show the engine migrations and the application's own ones without an error; start a second time to see that nothing is recovered. Remove the worktree afterwards. The worktree shares the workspace `.m2` with the main tree: build one tree at a time, and `mvn install` the main tree again afterwards.
4. Walk every view of the application in the browser as an ordinary account and as an administrator (delegate the walk to a sub-agent with a self-contained brief: URL, accounts, views, what each shows, no destructive commands). Check the console for errors.

## 6. Record and report

- Record the move where the project keeps its design record (version, guide URL, the steps that applied, the application's own migrations and how they were replayed), and update version mentions in the README and in project skills.
- Commit the version bump with the guide's changes; the application's own migration may be a commit of its own.
- Report to the user: the target version, each applied step with its ticket number, the steps that found nothing (one line), the decisions taken or still open, what the first start on production will do (engine migrations that delete or rewrite data), and how it was verified.
