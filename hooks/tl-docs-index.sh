#!/usr/bin/env bash
#
# SPDX-FileCopyrightText: 2026 (c) Business Operation Systems GmbH <info@top-logic.com>
#
# SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-BOS-TopLogic-1.0
#
# SessionStart hook announcing the TopLogic developer articles of the workspace.
#
# The modules of a TopLogic workspace ship developer articles as
# META-INF/tl-docs/*.md in their jars; the tl-mcp server serves them through
# list_docs / read_doc and writes their index (name and "when to read"
# description of each article) to target/tl-docs-index.md when it starts. This
# hook prints that index, which Claude Code adds to the session context. Before
# the server has run once in the workspace, there is no index yet, and the hook
# prints a single line pointing to list_docs instead. Outside a TopLogic Maven
# project it prints nothing.
set -u

DIR="${CLAUDE_PROJECT_DIR:-$PWD}"
INDEX="$DIR/target/tl-docs-index.md"

if [ -f "$INDEX" ]; then
	cat "$INDEX"
elif [ -f "$DIR/pom.xml" ] && grep -q "top-logic" "$DIR/pom.xml"; then
	echo "TopLogic developer articles: the tl-mcp tool list_docs lists the articles shipped by the modules of this workspace; read one with read_doc(name[, section])."
fi
exit 0
