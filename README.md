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

### MCP servers

- **`tl-mcp`** — Indexes the Java type graph (classes, members, annotations, references, call graph, source snippets) of the current Maven reactor and serves structured queries over MCP. Exposes `query_types`, `describe_type`, `list_members`, `references_to`, `callers_of`, `field_accessors`, `module_of`, `show_source`. When it is available, the agent should prefer these tools over filesystem `grep`/`find` for Java navigation — the MCP server ships an `instructions` block that tells the LLM exactly when and how. Launched via `mvn com.top-logic:tl-mcp-server:0.1.0-SNAPSHOT:serve` in the project's working directory, so Claude Code sessions opened inside a Maven reactor get it automatically.

  **One-time setup:** clone and install the [`tl-mcp-server`](http://tl.bos.local:3000/TopLogic/tl-mcp-server) repo so the plugin artifact lands in your local Maven repository:

  ```bash
  git clone http://tl.bos.local:3000/TopLogic/tl-mcp-server.git
  cd tl-mcp-server && mvn install
  ```

  After that, opening Claude Code inside any built Maven reactor boots the MCP server on session init. Outside a Maven project the server fails fast; disable it via `/mcp` if you don't need it.

## Development

The plugin layout follows the [Claude Code plugin reference](https://code.claude.com/docs/en/plugins-reference.md):

```
.claude-plugin/
├── plugin.json         # plugin manifest
└── marketplace.json    # marketplace catalog
skills/
└── tl-script/
    └── SKILL.md
```

To test locally:

```
claude --plugin-dir /path/to/tl-claude-tools
```

## License

Dual-licensed under AGPL-3.0-only or LicenseRef-BOS-TopLogic-1.0, matching the TopLogic engine.
