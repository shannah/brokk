#!/usr/bin/env sh
set -eu

# Resolve the directory where this script lives to locate the packaged jdeploy.args
SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
SRC_FILE="$SCRIPT_DIR/jdeploy.args"

# Determine the user's home directory in a cross-env way
HOME_DIR="${HOME:-}"
if [ -z "${HOME_DIR}" ]; then
  if [ -n "${USERPROFILE:-}" ]; then
    HOME_DIR="${USERPROFILE}"
  elif [ -n "${HOMEDRIVE:-}" ] && [ -n "${HOMEPATH:-}" ]; then
    HOME_DIR="${HOMEDRIVE}${HOMEPATH}"
  else
    echo "Unable to determine HOME directory. Skipping jdeploy.args installation." >&2
    exit 0
  fi
fi

TARGET_DIR="${HOME_DIR}/.brokk"
TARGET_FILE="${TARGET_DIR}/jdeploy.args"

# If target already exists, do nothing
if [ -f "${TARGET_FILE}" ]; then
  echo "jdeploy.args already exists at ${TARGET_FILE}; skipping copy."
  exit 0
fi

# Ensure target dir exists
mkdir -p "${TARGET_DIR}"

# Copy the packaged argfile
if [ ! -f "${SRC_FILE}" ]; then
  echo "Source argfile not found at ${SRC_FILE}; skipping copy." >&2
  exit 0
fi

cp -f "${SRC_FILE}" "${TARGET_FILE}" || {
  echo "Failed to copy ${SRC_FILE} to ${TARGET_FILE}" >&2
  exit 1
}

# Best-effort sane permissions
chmod 644 "${TARGET_FILE}" 2>/dev/null || true

echo "Installed argfile to ${TARGET_FILE}"
exit 0
