#!/usr/bin/env bash
#
# SPDX-FileCopyrightText: 2026 (c) Business Operation Systems GmbH <info@top-logic.com>
#
# SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-BOS-TopLogic-1.0
#
# Launcher for the tl-mcp MCP server, invoked from the plugin's .mcp.json.
#
# Before running Maven, the wrapper merges the plugin's toplogic-nexus
# profile into the user's own ~/.m2/settings.xml (creating the file if
# absent, and only if a profile with that id isn't already present), rather
# than passing the plugin's mvn-settings.xml straight to 'mvn -s'. '-s'
# replaces the settings file used for that run instead of merging with the
# user's own, so passing the plugin's file directly would silently drop the
# user's own mirrors, server credentials, and any localRepository override.
# If the merge itself fails (an existing settings.xml with no recognizable
# </profiles>/</activeProfiles>/</settings> closing tags, or a permission
# error), the wrapper warns on stderr and falls back to the plugin's own
# mvn-settings.xml for that run, so the server still starts.
# merge_toplogic_profile() is a text/line based edit, not a real XML parser
# - by design, to avoid a new runtime dependency beyond what this repo
# already assumes (bash, grep, sed, awk). It relies on the closing tags each
# appearing recognizably on their own line, which holds for any settings.xml
# Maven or a human would reasonably produce, and on the plugin's own
# mvn-settings.xml having exactly one <profile> block (true today).
#
# Alongside the server it pulls dependency source jars into the local Maven
# repository, so show_source can serve source for external dependencies and
# not just for reactor modules. The pre-fetch runs in the background,
# concurrently with the server: the MCP client allows the server a fixed
# connect window (30 s by default) to answer the initialize request, and a
# reactor-wide dependency:sources pass alone takes most of that. The two Maven
# runs do not interfere, since the pre-fetch only writes -sources artifacts,
# which the server never resolves. The pre-fetch is best-effort: artifacts
# without published sources and offline runs must still start the server, so a
# failure is ignored. Its build output is redirected to stderr and its stdin
# detached, because on the default stdio transport Maven's stdout would
# otherwise corrupt the JSON-RPC stream the MCP client reads.
#
# Usage: tl-mcp-serve.sh <source-mvn-settings.xml> [extra mvn args...]
# The source file (the plugin's mvn-settings.xml, declaring the toplogic-nexus
# profile) is passed by .mcp.json via ${CLAUDE_PLUGIN_ROOT}; the server runs in
# the client's working directory, so no target pom needs to be named.
set -u

# Merges the toplogic-nexus <profile> from $1 (the plugin's source
# mvn-settings.xml) into $2 (the user's ~/.m2/settings.xml), creating $2 if
# absent, and activating the profile via <activeProfiles> unless a profile
# with that id is already present (left untouched in that case, in case the
# user customized it). Exits 0 on success or no-op, 1 on any failure - the
# caller falls back to $1 unchanged.
merge_toplogic_profile() {
	local source="$1" user="$2" profile active merged
	mkdir -p "$(dirname "$user")" || return 1

	if [ ! -s "$user" ]; then
		cp "$source" "$user"
		return $?
	fi
	grep -qF '<id>toplogic-nexus</id>' "$user" && return 0

	profile="$(sed -n '/<profile>/,/<\/profile>/p' "$source")"
	[ -n "$profile" ] || return 1
	active='  <activeProfile>toplogic-nexus</activeProfile>'
	merged="$(mktemp "${user}.XXXXXX")" || return 1

	awk -v profile="$profile" -v active="$active" '
		{ lines[NR] = $0 }
		/<\/profiles>/       && !hp { hp = NR }
		/<\/activeProfiles>/ && !ha { ha = NR }
		END {
			for (i = 1; i <= NR; i++) {
				if (!hp && lines[i] ~ /<\/settings>/) print "  <profiles>\n" profile "\n  </profiles>"
				if (!ha && lines[i] ~ /<\/settings>/) print "  <activeProfiles>\n" active "\n  </activeProfiles>"
				if (i == hp) print profile
				if (i == ha) print active
				print lines[i]
			}
		}' "$user" >"$merged"

	if [ -s "$merged" ]; then
		mv "$merged" "$user"
	else
		rm -f "$merged"
		return 1
	fi
}

SOURCE_SETTINGS="$1"
shift

# Coordinate of the serve goal; override with TL_MCP_COORD when testing a build.
TL_MCP_COORD="${TL_MCP_COORD:-com.top-logic:tl-mcp-server:0.1.1}"

USER_SETTINGS="$HOME/.m2/settings.xml"
EFFECTIVE_SETTINGS="$SOURCE_SETTINGS"

if merge_toplogic_profile "$SOURCE_SETTINGS" "$USER_SETTINGS"; then
	EFFECTIVE_SETTINGS="$USER_SETTINGS"
else
	echo "tl-mcp-serve.sh: could not merge the toplogic-nexus profile into $USER_SETTINGS; falling back to the plugin's own mvn-settings.xml for this run (your own Maven mirrors/credentials/localRepository will not apply)." >&2
fi

mvn -s "$EFFECTIVE_SETTINGS" -q -fn dependency:sources "$@" </dev/null 1>&2 &

# exec so signals reach Maven directly.
exec mvn -s "$EFFECTIVE_SETTINGS" -q "${TL_MCP_COORD}:serve" "$@"
