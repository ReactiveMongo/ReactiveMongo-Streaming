#! /usr/bin/env bash

set -euo pipefail

ENV_FILE="$1"

if [ -r "$ENV_FILE" ]; then
  source "$ENV_FILE"
fi

export LD_LIBRARY_PATH
export PATH

if [ -z "${MONGO_CONF:-}" ]; then
  echo "[ERROR] MONGO_CONF is not set"
  exit 1
fi

if [ ! -r "$MONGO_CONF" ]; then
  echo "[ERROR] MongoDB config is not readable: $MONGO_CONF"
  exit 1
fi

echo "[INFO] Starting mongod with config: $MONGO_CONF"
echo "[INFO] PATH: $PATH"
echo "[INFO] LD_LIBRARY_PATH: ${LD_LIBRARY_PATH:-<empty>}"

if ! command -v mongod >/dev/null 2>&1; then
  echo "[ERROR] mongod command not found"
  exit 1
fi

check_mongod_socket() {
  if command -v ss >/dev/null 2>&1; then
    ss -lnt 2>/dev/null | grep -Eq '[:.]27017[[:space:]]'
    return $?
  fi

  if command -v netstat >/dev/null 2>&1; then
    netstat -lnt 2>/dev/null | grep -Eq '[:.]27017[[:space:]]'
    return $?
  fi

  return 1
}

MONGOD_ARGS=(mongod -f "$MONGO_CONF" --fork)

if command -v numactl >/dev/null 2>&1; then
  MONGOD_ARGS=(numactl --interleave=all "${MONGOD_ARGS[@]}")
fi

set +e
START_OUTPUT=$("${MONGOD_ARGS[@]}" 2>&1)
START_STATUS=$?
set -e

if [ -n "$START_OUTPUT" ]; then
  echo "$START_OUTPUT"
fi

MONGOD_COUNT=$(ps -ef | grep '[m]ongod' | wc -l)
SOCKET_READY=0

if check_mongod_socket; then
  SOCKET_READY=1
fi

if [ "$START_STATUS" -ne 0 ]; then
  echo "[WARN] mongod --fork returned non-zero status: $START_STATUS"
fi

if [ "$MONGOD_COUNT" -gt 0 ]; then
  echo "[INFO] mongod process count: $MONGOD_COUNT"
else
  echo "[WARN] No mongod process currently detected"
fi

if [ "$SOCKET_READY" -eq 1 ]; then
  echo "[INFO] mongod is listening on port 27017"
else
  echo "[WARN] Could not confirm listening socket on port 27017"
fi

if [ "$START_STATUS" -ne 0 ] && [ "$MONGOD_COUNT" -eq 0 ] && [ "$SOCKET_READY" -eq 0 ]; then
  echo "[ERROR] mongod did not start successfully"
  ps -ef | grep '[m]ongod' || true

  if command -v ss >/dev/null 2>&1; then
    ss -lntp || true
  elif command -v netstat >/dev/null 2>&1; then
    netstat -lnt || true
  fi

  if [ -r /tmp/mongod.log ]; then
    cat /tmp/mongod.log
  fi

  exit "$START_STATUS"
fi
