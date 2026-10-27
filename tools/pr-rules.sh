#!/bin/sh
# pr-rules.sh BASE: checks a pull request against the rules in CONTRIBUTING, from the git diff BASE...HEAD.
# Fails on a new library or a new permission; only warns on a missing CHANGELOG entry, since not every
# change to the app is one a user sees. Writes a table to $GITHUB_STEP_SUMMARY when it is set.
base=${1:?usage: pr-rules.sh BASE}
summary=${GITHUB_STEP_SUMMARY:-/dev/null}
added() { git diff "$base"...HEAD -- "$@" | grep '^+[^+]'; }
fail=0

deps=$(added '*.gradle.kts' | grep -E '^\+\s*(implementation|api|compileOnly|runtimeOnly|ksp|kapt|annotationProcessor)\(|^\+\s*id\(')
perms=$(added '*AndroidManifest.xml' | grep -E 'uses-permission|<permission')
changed=$(git diff --name-only "$base"...HEAD -- app/src/main)
notes=$(git diff "$base"...HEAD -- CHANGELOG.md | grep -c '^+- ')

{
    echo "### Plume rules"
    echo "| Rule | Result |"
    echo "|---|---|"
} >> "$summary"

if [ -n "$deps" ]; then
    echo "::error::New library or plugin. Plume has no dependencies on purpose; discuss it in an issue first."
    printf '%s\n' "$deps"
    echo "| No new library | ❌ new library or plugin |" >> "$summary"
    fail=1
else
    echo "| No new library | ✅ |" >> "$summary"
fi

if [ -n "$perms" ]; then
    echo "::error::New permission in a manifest. Discuss it in an issue first."
    printf '%s\n' "$perms"
    echo "| No new permission | ❌ new permission |" >> "$summary"
    fail=1
else
    echo "| No new permission | ✅ |" >> "$summary"
fi

if [ -n "$changed" ] && [ "$notes" -eq 0 ]; then
    echo "::warning::The app changed but CHANGELOG.md has no new entry. Add one under ## Unreleased if users see the change."
    echo "| CHANGELOG entry | ⚠️ none, needed if users see the change |" >> "$summary"
else
    echo "| CHANGELOG entry | ✅ |" >> "$summary"
fi

exit $fail
