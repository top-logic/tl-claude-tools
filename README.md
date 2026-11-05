# TopLogic Claude Code Tools

Claude Code extensions for working with [TopLogic](https://top-logic.com) — a model-based, no-code web application development platform.

This repository hosts the `toplogic` Claude Code marketplace. Its primary plugin, `tl-claude-tools`, bundles skills and references useful for both framework developers working on the TopLogic engine and application developers building on top of it.

## Installation

In Claude Code:

```
/plugin marketplace add top-logic/tl-claude-tools
/plugin install tl-claude-tools@toplogic
```

If adding the marketplace fails because GitHub is cloned via SSH (no SSH key or `known_hosts` entry), add it by its HTTPS URL instead:

```
/plugin marketplace add https://github.com/top-logic/tl-claude-tools.git
```

**Requirements:** a JDK 17 or newer and Maven, both on `PATH` — for `tl-mcp`, `tl-app` and `run-scripted-test`. Works on Linux, macOS and Windows; on Windows the skill scripts run in the Git Bash that Claude Code uses (`winget install Microsoft.OpenJDK.17`, Maven from <https://maven.apache.org/download.cgi>).

To track the rolling main branch (default), no further pinning is needed. For a stable pin:

```
/plugin marketplace add top-logic/tl-claude-tools#stable
```

Auto-update for third-party marketplaces is off by default — enable it via `/plugin` → Marketplaces if you want silent updates at startup.

## Contents

### Skills

- **`tl-script`** — Reference for TL Script (TopLogic's embedded expression language). Covers surface syntax, semantics, and a search strategy for locating registered script functions. Triggers automatically when reading, writing, or debugging TL Script expressions.
- **`tl-model`** — Reference for the dynamic-type model XML (`*.model.xml`): declaring classes / interfaces / enums, properties and references (including composite ownership and multiplicity), derived attributes via `storage-algorithm`, label / `id-column` conventions, and the wrapper-generator build wiring with its m2-cycle pitfalls.
- **`tl-data`** — Reference for instance data (objects, not schema): the `WEB-INF/data/*.objects.xml` format (primitives, nested composites, `<model-ref>` for enums/model parts, cross-references), the `InitialDataSetupService` load-once seeding mechanism, and the instance export/import round-trip. For shipping seed/master data with an app.
- **`tl-layout`** — Reference for layout XML (`*.layout.xml`): the template-call pattern, catalog of common templates (table, tree, tab, tile, form, …) with their inner-component selector names, channel binding (`selection(...)`, `model(...)`, `CombineLinking`), `TreeModelByExpression` / `ListModelByExpression` model builders, and the most frequent pitfalls (escape rules, `orientation` vs `horizontal`, tile-context requirements).
- **`tl-app`** — Start, stop, or restart a TopLogic application during a Claude Code session. Ships a helper shell script that tracks the port, tails the log until the app reports ready, and reports the URL.
- **`tl-albert`** - Adds Albert, the chat agent of TopLogic (`TL_SingleAgent`, modules `tl-ai-core` / `tl-chat` / `tl-chat-classic`), to an existing application: a model-security gate, at most four questions (roles, write roles, provider and key, where Albert appears), the configuration, and a check as non-admin accounts. Ships `AlbertDoctor.java` (read-only, JDK 17+, `detect` prints a fact sheet and the steps that apply, `check` finds integration errors and keys in the configuration or `git diff`), templates, and a fallback how-to. Uses the engine's own parts and article where the engine version ships them.
- **`run-scripted-test`** — Run a TopLogic scripted test (`.script.xml`) via the generic `test.TestAll` runner. Locates the test by name or path, resolves the containing module, and invokes Maven with the right system properties.

### MCP servers

- **`tl-mcp`** — Indexes the Java type graph (classes, members, annotations, references, call graph, source snippets) of the current Maven reactor and serves structured queries over MCP. Exposes `query_types`, `describe_type`, `list_members`, `references_to`, `callers_of`, `field_accessors`, `module_of`, `show_source`, and — for a web application reactor — the application's web resource tree (stacked `src/main/webapp` dirs and `-web-fragment.war` overlays) via `list_resources`, `find_resources`, `read_resource`, `resource_overlays`. When it is available, the agent should prefer these tools over filesystem `grep`/`find` for Java navigation — the MCP server ships an `instructions` block that tells the LLM exactly when and how. Launched via the `TlMcpServe.java` launcher (at the plugin root, run by `java` as a single-file program, so the same entry works on Linux, macOS and Windows) in the project's working directory, so Claude Code sessions opened inside a Maven reactor get it automatically.

  **No `~/.m2/settings.xml` changes needed.** The plugin ships its own `mvn-settings.xml` (at the plugin root) that declares the TopLogic Nexus as a plugin repository, and the launcher passes it via `mvn -s`. If you have a `~/.m2/settings.xml`, the launcher passes a copy of it with the TopLogic Nexus profile added instead, so your own repositories, mirrors, credentials and local repository keep working (e.g. for customer artifacts that are not on the TopLogic Nexus or Maven Central). Maven downloads the `com.top-logic:tl-mcp-server` artifact — pinned to a single version in `TlMcpServe.java` — from <https://dev.top-logic.com/nexus/repository/toplogic/> on first invocation and caches it in `~/.m2/repository`.

  **Source for external dependencies.** Alongside the server, the launcher runs `mvn dependency:sources` in the background (best-effort, output on stderr) so `show_source` can serve source for external dependencies and not just reactor modules. It runs concurrently rather than in front of the server because the reactor-wide pass alone takes most of the MCP client's 30 s connect window. Artifacts without published sources are skipped, and the server still starts offline.

  **Serving another project.** By default the server indexes the project of the session directory. Set `TL_MCP_PROJECT` to a directory (searched like the session directory) or a `pom.xml` to serve another one — e.g. when the session runs on a module that cannot be built on its own and the customer application it is developed against is the reactor to index. If that path has no pom, the server fails to start instead of serving the wrong project. The `tl-docs-index` hook follows the same variable.

  Outside a Maven project the server fails fast; disable it via `/mcp` if you don't need it.

  **Developer articles.** The `list_docs` / `read_doc` tools serve the Markdown articles the modules of the workspace ship below `META-INF/tl-docs/` in their jars (folders are chapters; `doc:<name>#<section>` links between articles can be passed to `read_doc` as they stand), so an application workspace finds the engine's developer documentation for exactly the engine version it builds against.

### Hooks

- **`tl-docs-index`** (`SessionStart`) — announces the developer articles of the workspace: prints the index (name and "when to read" description of each article) that `tl-mcp` writes to `target/tl-docs-index.md` at startup, which Claude Code adds to the session context. Before the server has run once in the workspace, it prints a single line pointing to `list_docs`; outside a TopLogic Maven project it prints nothing. It locates the project like the `tl-mcp` launcher (session directory, a child directory with the pom, or a parent). New articles need no plugin change.

## Quickstart: Albert mit Claude einbauen

**Deutsch.** So baut ihr Albert in eine bestehende TopLogic-Anwendung ein:

1. Installiert das Plugin (siehe [Installation](#installation)).
2. Startet Claude Code im Repository der Anwendung.
3. Schreibt: `Bau Albert in diese Anwendung ein.`
4. Beantwortet die Fragen: Welche Rollen sprechen mit Albert? Welche Rollen dürfen Albert Daten ändern lassen? Welcher Anbieter und welcher Schlüssel? Wo erscheint Albert?
5. Prüft die Ergebnistabelle "Rolle -> was Albert kann" und die offenen Punkte.
6. Prüft die Änderungen auf dem neuen Branch, bevor ihr sie übernehmt.

Voraussetzung: Die modellbasierte Sicherheit (`<security-config>`) ist für jede Rolle vollständig. Sonst hält Claude an und bietet diese Arbeit zuerst an. Der Schlüssel des Anbieters kommt nie in das Repository.

**English.** To add Albert to an existing TopLogic application:

1. Install the plugin (see [Installation](#installation)).
2. Start Claude Code in the repository of the application.
3. Write: `Add Albert to this application.`
4. Answer the questions: Which roles talk to Albert? Which roles may let Albert change data? Which provider and key? Where does Albert appear?
5. Check the result table "role -> what Albert can do" and the open items.
6. Review the changes on the new branch before you merge them.

Prerequisite: the model-based security (`<security-config>`) is complete for each role. Otherwise Claude stops and offers this work first. The key of the provider never goes into the repository.

## Development

The plugin layout follows the [Claude Code plugin reference](https://code.claude.com/docs/en/plugins-reference.md):

```
.claude-plugin/
├── plugin.json         # plugin manifest
└── marketplace.json    # marketplace catalog
.mcp.json               # MCP server registration (tl-mcp)
mvn-settings.xml        # Maven settings declaring the TopLogic Nexus
hooks/
├── hooks.json          # hook registration (SessionStart)
└── tl-docs-index.sh    # announces the developer articles of the workspace
TlMcpServe.java         # tl-mcp launcher: serve, pre-fetching dependency sources in the background
skills/
├── tl-script/
│   └── SKILL.md
├── tl-model/
│   └── SKILL.md
├── tl-data/
│   └── SKILL.md
├── tl-layout/
│   └── SKILL.md
├── tl-app/
│   ├── SKILL.md
│   └── tl-app.sh
├── tl-albert/
│   ├── SKILL.md
│   ├── AlbertDoctor.java   # read-only diagnosis: detect / check
│   ├── references/         # integration how-to, pitfalls, chat customization
│   └── templates/          # chat view, nav item, dock, tool access, prompt, provider, chat security
└── run-scripted-test/
    └── SKILL.md
```

To test locally:

```
claude --plugin-dir /path/to/tl-claude-tools
```

## License

Dual-licensed under AGPL-3.0-only or LicenseRef-BOS-TopLogic-1.0, matching the TopLogic engine.
