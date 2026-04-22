#!/usr/bin/env bash
set -euo pipefail

PROXY_URL="${PROXY_URL:-http://127.0.0.1:8080/}"
DIRECT_URL="${DIRECT_URL:-http://127.0.0.1:9001/}"
THREADS="${THREADS:-8}"
CONNECTIONS="${CONNECTIONS:-2000}"
DURATION="${DURATION:-20s}"
TARGET_OVERHEAD_MS="${TARGET_OVERHEAD_MS:-2.0}"

if ! command -v wrk >/dev/null 2>&1; then
  echo "wrk is required but was not found on PATH." >&2
  exit 2
fi

convert_to_ms() {
  local value="$1"
  local unit="$2"
  case "${unit}" in
    us) awk -v v="$value" 'BEGIN { printf "%.6f", v / 1000.0 }' ;;
    ms) awk -v v="$value" 'BEGIN { printf "%.6f", v }' ;;
    s) awk -v v="$value" 'BEGIN { printf "%.6f", v * 1000.0 }' ;;
    *)
      echo "Unsupported latency unit: ${unit}" >&2
      return 1
      ;;
  esac
}

run_wrk() {
  local url="$1"
  wrk -t"${THREADS}" -c"${CONNECTIONS}" -d"${DURATION}" --latency "${url}"
}

parse_latency_token() {
  local output="$1"
  printf '%s\n' "${output}" | awk '/^[[:space:]]*Latency[[:space:]]+/ { print $2; exit }'
}

parse_rps() {
  local output="$1"
  printf '%s\n' "${output}" | awk '/Requests\/sec:/ { print $2; exit }'
}

parse_token_value() {
  local token="$1"
  printf '%s' "${token}" | sed -E 's/^([0-9]+(\.[0-9]+)?).*/\1/'
}

parse_token_unit() {
  local token="$1"
  printf '%s' "${token}" | sed -E 's/^[0-9]+(\.[0-9]+)?([a-zA-Z]+)$/\2/' | tr '[:upper:]' '[:lower:]'
}

echo "Running direct benchmark on ${DIRECT_URL}"
DIRECT_OUT="$(run_wrk "${DIRECT_URL}")"
DIRECT_TOKEN="$(parse_latency_token "${DIRECT_OUT}")"
DIRECT_VALUE="$(parse_token_value "${DIRECT_TOKEN}")"
DIRECT_UNIT="$(parse_token_unit "${DIRECT_TOKEN}")"
DIRECT_LAT_MS="$(convert_to_ms "${DIRECT_VALUE}" "${DIRECT_UNIT}")"
DIRECT_RPS="$(parse_rps "${DIRECT_OUT}")"

echo "Running proxy benchmark on ${PROXY_URL}"
PROXY_OUT="$(run_wrk "${PROXY_URL}")"
PROXY_TOKEN="$(parse_latency_token "${PROXY_OUT}")"
PROXY_VALUE="$(parse_token_value "${PROXY_TOKEN}")"
PROXY_UNIT="$(parse_token_unit "${PROXY_TOKEN}")"
PROXY_LAT_MS="$(convert_to_ms "${PROXY_VALUE}" "${PROXY_UNIT}")"
PROXY_RPS="$(parse_rps "${PROXY_OUT}")"

OVERHEAD_MS="$(awk -v p="${PROXY_LAT_MS}" -v d="${DIRECT_LAT_MS}" 'BEGIN { printf "%.6f", p - d }')"
PASS="$(awk -v o="${OVERHEAD_MS}" -v t="${TARGET_OVERHEAD_MS}" 'BEGIN { if (o < t) print "PASS"; else print "FAIL" }')"

echo
echo "=== Benchmark Summary ==="
printf "Direct latency avg:      %.4f ms\n" "${DIRECT_LAT_MS}"
printf "Proxy latency avg:       %.4f ms\n" "${PROXY_LAT_MS}"
printf "Latency overhead:        %.4f ms\n" "${OVERHEAD_MS}"
printf "Direct requests/sec:     %.2f\n" "${DIRECT_RPS}"
printf "Proxy requests/sec:      %.2f\n" "${PROXY_RPS}"
echo "Target overhead (< ${TARGET_OVERHEAD_MS}): ${PASS}"

if [[ "${PASS}" != "PASS" ]]; then
  exit 1
fi
