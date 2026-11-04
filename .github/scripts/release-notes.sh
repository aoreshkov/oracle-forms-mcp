#!/bin/sh
# Prints the CHANGELOG.md section of one version as a GitHub release body.
#
#   .github/scripts/release-notes.sh 0.18.0 [CHANGELOG.md]
#
# The release workflow runs it before anything is built, so a tag whose version has no
# changelog section fails there instead of publishing a release without its notes.
#
# GitHub renders every newline in a release body as a line break, so the hard-wrapped
# lines of the changelog are joined back into one line per paragraph or list item: an
# indented line that does not start a list item continues the line above it.
set -eu

version="${1:?usage: release-notes.sh <version> [changelog]}"
changelog="${2:-CHANGELOG.md}"

awk -v v="$version" '
  function emit(line) { if (seen && blank) print ""; print line; seen = 1; blank = 0 }
  function flush() { if (have) emit(buf); have = 0 }
  index($0, "## [" v "]") == 1 { on = 1; next }
  !on { next }
  /^## \[/ || /^\[[^]]+\]: / { exit }
  /^[ \t]*$/ { flush(); blank = 1; next }
  have && /^[ \t]+[^ \t]/ && !/^[ \t]*([-*+]|[0-9]+\.) / {
    sub(/^[ \t]+/, ""); buf = buf " " $0; next
  }
  { flush(); buf = $0; have = 1 }
  END {
    flush()
    if (!seen) {
      printf "%s has no \"## [%s]\" section, or it is empty\n", FILENAME, v > "/dev/stderr"
      exit 1
    }
  }
' "$changelog"
