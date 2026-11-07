#!/usr/bin/env bash
# Runs the plugin's eval suite against sample-forms and a local server build. See evals/README.md.
#
# `claude plugin eval` gives every run a fresh config and withholds the environment, so neither
# forms_dir nor OFMCP_* reaches the server. The server would answer every tool with "no forms
# directory" and a grader on the tool call alone would still pass. So this evaluates a temporary
# copy of the plugin whose .mcp.json names the directory and the build. The skill, the launcher and
# the server are the real ones, and nothing that ships changes.
set -euo pipefail

repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
server="$repo/server/build/install/server"
test -d "$server/lib" || { echo "No server build at $server. Run: ./gradlew :server:installDist" >&2; exit 1; }

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

plugin="$work/oracle-forms"
cp -r "$repo/plugins/oracle-forms" "$plugin"
mkdir "$plugin/evals"
find "$repo/evals" -mindepth 1 -maxdepth 1 -type d -exec cp -r {} "$plugin/evals/" \;

jq --arg forms "$repo/sample-forms" --arg home "$server" '
  .mcpServers["oracle-forms"].args[2] = $forms
  | .mcpServers["oracle-forms"].env.OFMCP_SERVER_HOME = $home
' "$repo/plugins/oracle-forms/.mcp.json" > "$plugin/.mcp.json"

out="$repo/build/evals/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$out"

# --ablation none: without the plugin there's no server, so a no-plugin arm says nothing about the skill.
# Options given to this script come last and override these.
cd "$plugin"
claude plugin eval . \
  --trust-plugin \
  --no-publish \
  --ablation none \
  --allow-real-servers \
  --allow-tools 'mcp__plugin_oracle-forms_oracle-forms__*' \
  --max-cost-usd 10 \
  --output-dir "$out" \
  --json "$out/result.json" \
  "$@"
