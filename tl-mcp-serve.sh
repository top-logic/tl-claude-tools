#!/usr/bin/env bash
#
# SPDX-FileCopyrightText: 2026 (c) Business Operation Systems GmbH <info@top-logic.com>
#
# SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-BOS-TopLogic-1.0
#
# Launcher for the tl-mcp MCP server, invoked from the plugin's .mcp.json.
#
# Before starting the server it pulls dependency source jars into the local
# Maven repository, so show_source can serve source for external dependencies
# and not just for reactor modules. The pre-fetch is best-effort: artifacts
# without published sources and offline runs must still start the server, so a
# failure is non-fatal. Its build output is redirected to stderr because on the
# default stdio transport Maven's stdout would otherwise corrupt the JSON-RPC
# stream the MCP client reads.
#
# Usage: tl-mcp-serve.sh <mvn-settings.xml> [extra mvn args...]
# The settings file (the plugin's mvn-settings.xml, declaring the TopLogic
# Nexus) is passed by .mcp.json via ${CLAUDE_PLUGIN_ROOT}; the server runs in
# the client's working directory, so no target pom needs to be named.
set -u

SETTINGS="$1"
shift

# Coordinate of the serve goal; override with TL_MCP_COORD when testing a build.
TL_MCP_COORD="${TL_MCP_COORD:-com.top-logic:tl-mcp-server:0.1.1}"

mvn -s "$SETTINGS" -q -fn dependency:sources "$@" 1>&2 || true

# exec so signals reach Maven directly.
exec mvn -s "$SETTINGS" -q "${TL_MCP_COORD}:serve" "$@"
