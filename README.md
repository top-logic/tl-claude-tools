# TopLogic Claude Code Tools

Claude Code extensions for working with [TopLogic](https://top-logic.com) — a model-based, no-code web application development platform.

This repository hosts the `toplogic` Claude Code marketplace. Its primary plugin, `tl-claude-tools`, bundles skills and references useful for both framework developers working on the TopLogic engine and application developers building on top of it.

## Installation

In Claude Code:

```
/plugin marketplace add top-logic/tl-claude-tools
/plugin install tl-claude-tools@toplogic
```

To track the rolling main branch (default), no further pinning is needed. For a stable pin:

```
/plugin marketplace add top-logic/tl-claude-tools#stable
```

Auto-update for third-party marketplaces is off by default — enable it via `/plugin` → Marketplaces if you want silent updates at startup.

## Contents

### Skills

- **`tl-script`** — Reference for TL Script (TopLogic's embedded expression language). Covers surface syntax, semantics, and a search strategy for locating registered script functions. Triggers automatically when reading, writing, or debugging TL Script expressions.
- **`tl-model`** — Reference for the dynamic-type model XML (`*.model.xml`): declaring classes / interfaces / enums, properties and references (including composite ownership and multiplicity), derived attributes via `storage-algorithm`, label / `id-column` conventions, and the wrapper-generator build wiring with its m2-cycle pitfalls.
- **`tl-layout`** — Reference for layout XML (`*.layout.xml`): the template-call pattern, catalog of common templates (table, tree, tab, tile, form, …) with their inner-component selector names, channel binding (`selection(...)`, `model(...)`, `CombineLinking`), `TreeModelByExpression` / `ListModelByExpression` model builders, and the most frequent pitfalls (escape rules, `orientation` vs `horizontal`, tile-context requirements).
- **`tl-app`** — Start, stop, or restart a TopLogic application during a Claude Code session. Ships a helper shell script that tracks the port, tails the log until the app reports ready, and reports the URL.
- **`run-scripted-test`** — Run a TopLogic scripted test (`.script.xml`) via the generic `test.TestAll` runner. Locates the test by name or path, resolves the containing module, and invokes Maven with the right system properties.

### MCP servers

- **`tl-mcp`** — Indexes the Java type graph (classes, members, annotations, references, call graph, source snippets) of the current Maven reactor and serves structured queries over MCP. Exposes `query_types`, `describe_type`, `list_members`, `references_to`, `callers_of`, `field_accessors`, `module_of`, `show_source`. When it is available, the agent should prefer these tools over filesystem `grep`/`find` for Java navigation — the MCP server ships an `instructions` block that tells the LLM exactly when and how. Launched via `mvn com.top-logic:tl-mcp-server:0.1.0:serve` in the project's working directory, so Claude Code sessions opened inside a Maven reactor get it automatically.

  **No `~/.m2/settings.xml` changes needed.** The plugin ships its own `mvn-settings.xml` (at the plugin root) that declares the TopLogic Nexus as a plugin repository, and the `mcpServers` entry passes it via `mvn -s ${CLAUDE_PLUGIN_ROOT}/mvn-settings.xml`. Maven downloads `com.top-logic:tl-mcp-server:0.1.0` from <https://dev.top-logic.com/nexus/repository/toplogic/> on first invocation and caches it in `~/.m2/repository`.

  Outside a Maven project the server fails fast; disable it via `/mcp` if you don't need it.

## Development

The plugin layout follows the [Claude Code plugin reference](https://code.claude.com/docs/en/plugins-reference.md):

```
.claude-plugin/
├── plugin.json         # plugin manifest
└── marketplace.json    # marketplace catalog
skills/
├── tl-script/
│   └── SKILL.md
├── tl-model/
│   └── SKILL.md
├── tl-layout/
│   └── SKILL.md
├── tl-app/
│   ├── SKILL.md
│   └── tl-app.sh
└── run-scripted-test/
    └── SKILL.md
```

To test locally:

```
claude --plugin-dir /path/to/tl-claude-tools
```

## License

Dual-licensed under AGPL-3.0-only or LicenseRef-BOS-TopLogic-1.0, matching the TopLogic engine.
