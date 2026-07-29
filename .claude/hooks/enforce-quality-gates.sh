#!/bin/bash
# Stop hook: run this project's quality gates (test + basic lint) and block
# ending the turn if they fail. Auto-detects project type the same way
# speckit-pipelines' detect_test_cmd does, extended with each ecosystem's
# standard lint/static-analysis step where one exists as a single obvious
# command (no per-project config required).
#
# Skips fast (a git status check) unless a file the detected ecosystem cares
# about has changed since the gates last passed. Stop fires after every turn
# in an interactive session (unlike a one-shot pipeline, where Stop fires
# once total) -- rerunning a full test+lint pass on every message, including
# ones that never touched code, would make the session unusable.
#
# Override: set QUALITY_GATE_CMD and QUALITY_GATE_WATCH (space-separated git
# pathspecs) in .claude/settings.json's env, or export them before starting
# the session, to bypass auto-detection entirely.

FAILURES=""
PASSED=true
run_gate() {
  local NAME=$1; shift
  OUT=$(bash -c "$*" 2>&1) || { PASSED=false; FAILURES="${FAILURES}\n\n❌ ${NAME}:\n${OUT}"; }
}

detect() {
  if [ -n "${QUALITY_GATE_CMD:-}" ]; then
    CMD="$QUALITY_GATE_CMD"
    WATCH="${QUALITY_GATE_WATCH:-.}"
  elif [ -f pom.xml ]; then
    CMD="$([ -x ./mvnw ] && echo ./mvnw || echo mvn) verify"
    WATCH="*.java pom.xml checkstyle.xml"
  elif [ -f package.json ]; then
    if grep -q '"lint"' package.json 2>/dev/null; then
      CMD="npm run lint && npm test"
    else
      CMD="npm test"
    fi
    WATCH="*.js *.jsx *.ts *.tsx package.json"
  elif [ -f pytest.ini ] || [ -f pyproject.toml ]; then
    if [ -f pyproject.toml ] && grep -q '\[tool.ruff\]' pyproject.toml 2>/dev/null || [ -f ruff.toml ] || [ -f .ruff.toml ]; then
      CMD="ruff check . && pytest -q"
    else
      CMD="pytest -q"
    fi
    WATCH="*.py pyproject.toml pytest.ini"
  elif [ -f Cargo.toml ]; then
    CMD="cargo clippy --all-targets -- -D warnings && cargo test"
    WATCH="*.rs Cargo.toml"
  elif [ -f go.mod ]; then
    CMD="go vet ./... && go test ./..."
    WATCH="*.go go.mod"
  elif [ -f build.gradle ] || [ -f build.gradle.kts ]; then
    CMD="$([ -x ./gradlew ] && echo ./gradlew || echo gradle) test"
    WATCH="*.java *.kt build.gradle build.gradle.kts"
  else
    CMD=""
    WATCH=""
  fi
}

detect
[ -z "$CMD" ] && { echo '{"decision": "allow"}'; exit 0; }

# Skip on a clean base-branch checkout (main/master/develop, nothing
# uncommitted at all -- not just within $WATCH). This is the window before
# a ticket's worktree/branch exists yet, or right after one was merged and
# cleaned up: gating a completely clean base branch against its own
# possibly-already-broken state (pre-existing repo debt this turn didn't
# create) would block turns for reasons unrelated to anything being worked
# on. A dirty base branch (someone deliberately working in place, having
# declined a worktree) is NOT skipped -- their real edits still get gated.
CURRENT_BRANCH=$(git branch --show-current 2>/dev/null)
if [ -n "$CURRENT_BRANCH" ]; then
  case "$CURRENT_BRANCH" in
    main|master|develop)
      if [ -z "$(git status --porcelain 2>/dev/null)" ]; then
        echo '{"decision": "allow"}'
        exit 0
      fi
      ;;
  esac
fi

MARKER=.claude/.quality-gates-last-verified
LAST_SHA=$(cat "$MARKER" 2>/dev/null || echo "")
CURRENT_SHA=$(git rev-parse HEAD 2>/dev/null)
# shellcheck disable=SC2086
DIRTY=$(git status --porcelain -- $WATCH 2>/dev/null)

if [ "$LAST_SHA" = "$CURRENT_SHA" ] && [ -z "$DIRTY" ]; then
  echo '{"decision": "allow"}'
  exit 0
fi

run_gate "$CMD" "$CMD"

if [ "$PASSED" = true ]; then
  mkdir -p .claude
  echo "$CURRENT_SHA" > "$MARKER"
  echo '{"decision": "allow"}'
  exit 0
fi

MSG="Quality gates failed. The failing output:\n${FAILURES}\n
Respond with the MINIMAL fix for exactly this reported failure, then end your turn so the gates re-run.
- Do NOT refactor, restructure, or 'improve' code beyond what the failure output requires
- Do NOT invoke any skill (no /simplify, no code-review) to make gates pass
- Do NOT dispatch subagents to handle this
- Do NOT skip, disable, or weaken failing tests or gate configuration"
ESCAPED=$(echo -e "$MSG" | python3 -c "import sys,json; print(json.dumps(sys.stdin.read()))")
echo "{\"decision\": \"block\", \"reason\": $ESCAPED}"
