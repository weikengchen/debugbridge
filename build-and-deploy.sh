#!/bin/bash

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MOD_DIR="${SCRIPT_DIR}/mod"
TARGET_DIR="/Users/cusgadmin/Library/Application Support/ModrinthApp/profiles/Fabric 1.19/mods/"

JAR_NAME="debugbridge-1.19-1.1.0.jar"
OLD_JAR_NAMES=("luabridge-1.19-1.0.0.jar" "fabric-1.19-1.0.0.jar" "debugbridge-1.19-1.0.0.jar")
SOURCE_JAR="${MOD_DIR}/fabric-1.19/build/libs/${JAR_NAME}"
TARGET_JAR="${TARGET_DIR}/${JAR_NAME}"

echo "Building DebugBridge mod (fabric-1.19)..."
cd "${MOD_DIR}"
# Wipe stale build outputs from any previous archivesName.
for old in "${OLD_JAR_NAMES[@]}"; do
    rm -f "${MOD_DIR}/fabric-1.19/build/libs/${old}"
done
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :fabric-1.19:build

if [ ! -f "${SOURCE_JAR}" ]; then
    echo "Error: Build artifact not found at ${SOURCE_JAR}"
    exit 1
fi

echo "Creating target directory if it doesn't exist..."
mkdir -p "${TARGET_DIR}"

verify_jar() {
    local jar_file="$1"
    unzip -tq "${jar_file}" 2>/dev/null
}

# Stage the new jar next to the target so the final swap can be an atomic
# rename(2) on the same filesystem. Overwriting the target in place would
# corrupt any running JVM's open ZipFile handle (its cached central-directory
# offsets become garbage the moment the bytes change).
TMP_JAR="${TARGET_JAR}.new"
trap 'rm -f "${TMP_JAR}"' EXIT

MAX_RETRIES=3
RETRY_COUNT=0

while [ $RETRY_COUNT -lt $MAX_RETRIES ]; do
    echo "Staging jar at ${TMP_JAR}..."
    cp -f "${SOURCE_JAR}" "${TMP_JAR}"

    echo "Verifying staged jar integrity..."
    if verify_jar "${TMP_JAR}"; then
        # mv on the same filesystem is rename(2) — atomic, and the running
        # JVM keeps its old inode until it releases the handle. New launches
        # pick up the new inode. (On Windows this would fail because renaming
        # over an open file isn't allowed; use versioned filenames there.)
        echo "Swapping staged jar into place..."
        mv -f "${TMP_JAR}" "${TARGET_JAR}"
        echo "Jar swap successful!"
        break
    else
        RETRY_COUNT=$((RETRY_COUNT + 1))
        if [ $RETRY_COUNT -lt $MAX_RETRIES ]; then
            echo "Warning: Jar verification failed (attempt $RETRY_COUNT/$MAX_RETRIES). Retrying..."
            sleep 1
        else
            echo "Error: Failed to stage a valid jar after $MAX_RETRIES attempts"
            echo "Source: ${SOURCE_JAR}"
            echo "Target: ${TARGET_JAR}"
            exit 1
        fi
    fi
done

echo "Build and deployment complete!"
echo "Jar deployed to: ${TARGET_JAR}"
