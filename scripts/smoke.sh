#!/usr/bin/env bash
# End-to-end smoke test against a RUNNING instance of the service.
#
#   mvn spring-boot:run &            # in another terminal
#   scripts/smoke.sh [base_url]      # default http://localhost:8080
#
# Writes a human-readable transcript to docs/evidence/smoke-transcript.txt (commit it as validation evidence).
# Exit code is non-zero if any check fails. Uses only bash + curl + sed.
set -u

BASE="${1:-http://localhost:8080}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/docs/evidence/smoke-transcript.txt"
mkdir -p "$(dirname "$OUT")"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

pass=0
fail=0
STATUS=""
BODY=""

log() { printf '%s\n' "$*" | tee -a "$OUT"; }

# call METHOD PATH [extra curl args...]  -> sets STATUS, BODY; headers kept in $TMP/headers
call() {
  local method="$1" path="$2"
  shift 2
  STATUS="$(curl -s -m 10 -X "$method" -o "$TMP/body" -D "$TMP/headers" -w '%{http_code}' "$@" "$BASE$path")" || STATUS="000"
  # normalize "key" : value  ->  "key":value so field extraction does not depend on the server's JSON spacing
  BODY="$(sed -e 's/" *: */":/g' "$TMP/body" 2>/dev/null)"
}

# header NAME -> value of the first matching response header (case-insensitive)
header() {
  grep -i "^$1:" "$TMP/headers" 2>/dev/null | head -1 | sed -e 's/^[^:]*: *//' -e 's/\r$//'
}

json_field() { printf '%s' "$BODY" | sed -n "s/.*\"$1\":\"\([^\"]*\)\".*/\1/p" | head -1; }

expect_eq() { # description actual expected
  if [ "$2" = "$3" ]; then
    pass=$((pass + 1)); log "PASS  $1 (got: $2)"
  else
    fail=$((fail + 1)); log "FAIL  $1 (expected: $3, got: $2)"
  fi
}

expect_contains() { # description haystack needle
  case "$2" in
    *"$3"*) pass=$((pass + 1)); log "PASS  $1 (contains: $3)" ;;
    *) fail=$((fail + 1)); log "FAIL  $1 (missing: $3)" ;;
  esac
}

: > "$OUT"
log "# Smoke test transcript"
log "# target : $BASE"
log "# run at : $(date -u +%Y-%m-%dT%H:%M:%SZ)"
log ""

JSON='Content-Type: application/json'
SUFFIX="$(date +%s)"
URL="https://93.184.216.34/docs/getting-started"   # public IP literal: no DNS needed by the SSRF guard

log "## 1. Service is up and the contract is served"
call GET /actuator/health
expect_eq "health status" "$STATUS" "200"
expect_contains "health body" "$BODY" "UP"
call GET /openapi.yaml
expect_eq "openapi.yaml served" "$STATUS" "200"

log ""
log "## 2. Shorten"
call POST /api/v1/links -H "$JSON" -d "{\"url\":\"$URL\"}"
expect_eq "create returns 201" "$STATUS" "201"
CODE="$(json_field code)"
[ -n "$CODE" ] && expect_eq "code is 7 chars" "${#CODE}" "7" || expect_eq "code present" "missing" "present"
expect_contains "Location header" "$(header Location)" "/api/v1/links/$CODE"

log ""
log "## 3. Redirect (302, no-store) and click recording"
call GET "/$CODE"
expect_eq "redirect status" "$STATUS" "302"
expect_eq "redirect target" "$(header Location)" "$URL"
expect_contains "no-store" "$(header Cache-Control)" "no-store"
call GET "/api/v1/links/$CODE/analytics"
expect_eq "analytics status" "$STATUS" "200"
expect_contains "one click counted" "$BODY" "\"totalClicks\":1"

log ""
log "## 4. Idempotent create"
KEY="smoke-$SUFFIX"
call POST /api/v1/links -H "$JSON" -H "Idempotency-Key: $KEY" -d "{\"url\":\"$URL/idem\"}"
expect_eq "first call creates" "$STATUS" "201"
IDEM_CODE="$(json_field code)"
call POST /api/v1/links -H "$JSON" -H "Idempotency-Key: $KEY" -d "{\"url\":\"$URL/idem\"}"
expect_eq "retry replays" "$STATUS" "200"
expect_eq "replay header" "$(header Idempotent-Replayed)" "true"
expect_eq "original code was captured" "$([ -n "$IDEM_CODE" ] && echo yes || echo no)" "yes"
expect_eq "same code on replay" "$(json_field code)" "$IDEM_CODE"
call POST /api/v1/links -H "$JSON" -H "Idempotency-Key: $KEY" -d "{\"url\":\"$URL/different\"}"
expect_eq "key reuse with different request" "$STATUS" "422"

log ""
log "## 5. Custom alias and conflicts"
ALIAS="smoke-$SUFFIX-a"
call POST /api/v1/links -H "$JSON" -d "{\"url\":\"$URL/alias\",\"alias\":\"$ALIAS\",\"expiresInSeconds\":3600}"
expect_eq "alias create" "$STATUS" "201"
expect_eq "alias is the code" "$(json_field code)" "$ALIAS"
call POST /api/v1/links -H "$JSON" -d "{\"url\":\"$URL/alias2\",\"alias\":\"$ALIAS\"}"
expect_eq "duplicate alias" "$STATUS" "409"
call POST /api/v1/links -H "$JSON" -d "{\"url\":\"$URL/alias3\",\"alias\":\"api\"}"
expect_eq "reserved alias" "$STATUS" "409"

log ""
log "## 6. Guardrails"
call POST /api/v1/links -H "$JSON" -d '{"url":"http://169.254.169.254/latest/meta-data/"}'
expect_eq "SSRF target (cloud metadata IP) rejected" "$STATUS" "400"
call POST /api/v1/links -H "$JSON" -d '{"url":"http://127.0.0.1:8080/"}'
expect_eq "loopback rejected" "$STATUS" "400"
call POST /api/v1/links -H "$JSON" -d '{"url":"ftp://example.com/file"}'
expect_eq "non-http scheme rejected" "$STATUS" "400"
call POST /api/v1/links -H "$JSON" -d '{"url":"https://user:pw@93.184.216.34/"}'
expect_eq "credentials in URL rejected" "$STATUS" "400"
call POST /api/v1/links -H "$JSON" -d '{}'
expect_eq "missing url rejected" "$STATUS" "400"
expect_contains "problem+json content type" "$(header Content-Type)" "application/problem+json"
call GET /definitely-not-a-code
expect_eq "unknown code" "$STATUS" "404"

log ""
log "## 7. Deactivate"
call DELETE "/api/v1/links/$CODE"
expect_eq "deactivate" "$STATUS" "204"
call DELETE "/api/v1/links/$CODE"
expect_eq "deactivate is idempotent" "$STATUS" "204"
call GET "/$CODE"
expect_eq "deactivated link is gone" "$STATUS" "410"

log ""
log "## 8. Observability"
call GET /actuator/metrics/shortener.links.created
expect_eq "created counter exposed" "$STATUS" "200"
call GET /actuator/metrics/shortener.analytics.failures
expect_eq "analytics-failure counter exposed" "$STATUS" "200"

log ""
log "## 9. Operability (request correlation, stable error codes, readiness, HEAD)"
call GET /api/v1/links/nope -H 'X-Request-Id: smoke-trace-1'
expect_eq "unknown link" "$STATUS" "404"
expect_eq "caller request id echoed" "$(header X-Request-Id)" "smoke-trace-1"
expect_contains "stable machine-readable error code" "$BODY" '"code":"LINK_NOT_FOUND"'
call GET /actuator/health/readiness
expect_eq "readiness (includes the database)" "$STATUS" "200"
call POST /api/v1/links -H "$JSON" -d "{\"url\":\"$URL\"}"
HEAD_CODE="$(json_field code)"
HEAD_STATUS="$(curl -s -m 10 -I -o /dev/null -w '%{http_code}' "$BASE/$HEAD_CODE")" || HEAD_STATUS="000"
expect_eq "HEAD redirects like GET" "$HEAD_STATUS" "302"
call GET "/api/v1/links/$HEAD_CODE/analytics"
expect_contains "HEAD is not counted as a visit" "$BODY" '"totalClicks":0'

log ""
log "## 10. Change a link's expiry (brownfield enhancement)"
call POST /api/v1/links -H "$JSON" -d "{\"url\":\"$URL\",\"expiresInSeconds\":60}"
EXP_CODE="$(json_field code)"
call PATCH "/api/v1/links/$EXP_CODE" -H "$JSON" -d '{"expiresInSeconds":86400}'
expect_eq "extend expiry" "$STATUS" "200"
call PATCH "/api/v1/links/$EXP_CODE" -H "$JSON" -d '{"neverExpires":true}'
expect_eq "remove expiry" "$STATUS" "200"
expect_contains "expiresAt is now null" "$BODY" '"expiresAt":null'
call PATCH "/api/v1/links/$EXP_CODE" -H "$JSON" -d '{}'
expect_eq "empty change rejected" "$STATUS" "400"
call PATCH "/api/v1/links/definitely-missing" -H "$JSON" -d '{"neverExpires":true}'
expect_eq "unknown code" "$STATUS" "404"
call DELETE "/api/v1/links/$EXP_CODE"
call PATCH "/api/v1/links/$EXP_CODE" -H "$JSON" -d '{"expiresInSeconds":3600}'
expect_eq "deactivated link cannot be revived" "$STATUS" "410"

log ""
log "# RESULT: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
