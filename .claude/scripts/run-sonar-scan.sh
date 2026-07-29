#!/bin/bash
# Optional pre-merge SonarQube scan. Run by the orchestrator as part of
# superpowers:finishing-a-development-branch's test-verification step --
# NOT wired as a hook. A full Sonar analysis is much slower than the
# interactive per-turn quality gate (network round-trip to a server,
# server-side background processing), so it runs once, right before a
# branch finishes, not on every turn.
#
# Advisory only: never fails the build or blocks anything itself. Submits
# the analysis, waits for the server to finish processing it, then prints
# the quality gate result and dashboard link for a human to weigh before
# choosing how to finish the branch.
#
# Opt-in per project: skips silently if no Sonar credentials are
# configured. To enable, create a gitignored .env in the project root:
#   SONAR_HOST_URL=http://localhost:9000
#   SONAR_TOKEN=<your-token>
#
# Currently supports Maven projects only (pom.xml). Other ecosystems print
# a clear "not yet supported" message and exit 0 rather than silently doing
# nothing -- extend here if you need npm/pytest/etc. support.

set -uo pipefail

if [ -f .env ]; then
  set -a
  # shellcheck disable=SC1091
  source .env
  set +a
fi

if [ -z "${SONAR_HOST_URL:-}" ] || [ -z "${SONAR_TOKEN:-}" ]; then
  echo "No SONAR_HOST_URL/SONAR_TOKEN configured for this project -- skipping SonarQube scan."
  echo "(To enable: add a gitignored .env with SONAR_HOST_URL and SONAR_TOKEN.)"
  exit 0
fi

if [ ! -f pom.xml ]; then
  echo "SonarQube scan requested but this project isn't Maven-based (no pom.xml)."
  echo "run-sonar-scan.sh currently only supports Maven projects -- skipping."
  exit 0
fi

MVN="mvn"
[ -x ./mvnw ] && MVN="./mvnw"
REPORT_TASK="target/sonar/report-task.txt"

# `verify` and `sonar:sonar` run together, in one reactor invocation, so the
# JaCoCo coverage report (bound to the `test` phase) is freshly generated
# in this same build before the scanner reads it. Running `sonar:sonar`
# alone -- a single, standalone goal -- does NOT execute the test phase,
# so coverage would show as "not computed" on the dashboard even though
# the scan itself succeeds. If this project needs JAVA_HOME/DOCKER_HOST
# overrides for its test suite, the caller must export them before
# invoking this script -- never baked in here.
echo "==> Running tests + SonarQube scan ($MVN verify sonar:sonar)..."
if ! $MVN -q verify sonar:sonar -Dsonar.host.url="$SONAR_HOST_URL" -Dsonar.token="$SONAR_TOKEN"; then
  echo "Build or scan submission failed -- see output above. Not blocking; review manually."
  exit 0
fi

if [ ! -f "$REPORT_TASK" ]; then
  echo "Scan ran but no report-task file found at $REPORT_TASK -- cannot poll quality gate status."
  exit 0
fi

TASK_ID=$(grep '^ceTaskId=' "$REPORT_TASK" | cut -d= -f2)
DASHBOARD_URL=$(grep '^dashboardUrl=' "$REPORT_TASK" | cut -d= -f2-)

if [ -z "$TASK_ID" ]; then
  echo "Could not determine analysis task ID -- check the dashboard manually at $SONAR_HOST_URL"
  exit 0
fi

echo "==> Waiting for SonarQube to process the analysis (task $TASK_ID)..."
STATUS="PENDING"
for _ in $(seq 1 30); do
  STATUS=$(curl -s -u "${SONAR_TOKEN}:" "${SONAR_HOST_URL}/api/ce/task?id=${TASK_ID}" \
    | python3 -c "import sys,json; print(json.load(sys.stdin)['task']['status'])" 2>/dev/null || echo "UNKNOWN")
  case "$STATUS" in
    SUCCESS|FAILED|CANCELED) break ;;
  esac
  sleep 2
done

if [ "$STATUS" != "SUCCESS" ]; then
  echo "SonarQube background processing did not finish successfully (status: $STATUS)."
  echo "Check manually: ${DASHBOARD_URL:-$SONAR_HOST_URL}"
  exit 0
fi

ANALYSIS_ID=$(curl -s -u "${SONAR_TOKEN}:" "${SONAR_HOST_URL}/api/ce/task?id=${TASK_ID}" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['task']['analysisId'])" 2>/dev/null)

QG_STATUS="UNKNOWN"
QG_CONDITIONS=""
if [ -n "$ANALYSIS_ID" ]; then
  QG_JSON=$(curl -s -u "${SONAR_TOKEN}:" "${SONAR_HOST_URL}/api/qualitygates/project_status?analysisId=${ANALYSIS_ID}")
  QG_STATUS=$(echo "$QG_JSON" | python3 -c "import sys,json; print(json.load(sys.stdin)['projectStatus']['status'])" 2>/dev/null || echo "UNKNOWN")
  QG_CONDITIONS=$(echo "$QG_JSON" | python3 -c "
import sys, json
d = json.load(sys.stdin)['projectStatus']
for c in d.get('conditions', []):
    if c.get('status') != 'OK':
        print(f\"  - {c.get('metricKey')}: {c.get('actualValue')} (threshold: {c.get('comparator')} {c.get('errorThreshold')})\")
" 2>/dev/null)
fi

echo ""
echo "=================================================="
echo " SonarQube Quality Gate: $QG_STATUS"
[ -n "$QG_CONDITIONS" ] && echo "$QG_CONDITIONS"
echo " Dashboard: ${DASHBOARD_URL:-$SONAR_HOST_URL}"
echo "=================================================="
echo ""
echo "(Advisory only -- this does not block finishing the branch.)"

exit 0
