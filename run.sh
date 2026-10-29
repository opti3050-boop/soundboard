#!/usr/bin/env bash
# Build (if needed) and run the Soundboard.
set -uo pipefail
cd "$(dirname "$0")"

# Prerequisites
if ! command -v mvn &>/dev/null; then
    echo "ERROR: Maven not found.  sudo apt install maven"; exit 1
fi
if ! command -v java &>/dev/null; then
    echo "ERROR: Java not found.  sudo apt install openjdk-21-jdk"; exit 1
fi

# Find or build the JAR
JAR=$(find target -maxdepth 1 -name "*.jar" ! -name "*-thin*" ! -name "original-*" 2>/dev/null | head -1)
NEEDS_BUILD=0
if [ -z "$JAR" ]; then
    echo "[soundboard] No JAR found – building..."
    NEEDS_BUILD=1
else
    NEWER=$(find src -name "*.java" -newer "$JAR" 2>/dev/null | head -1)
    if [ -n "$NEWER" ]; then
        echo "[soundboard] Source changed – rebuilding..."
        NEEDS_BUILD=1
    fi
fi

if [ "$NEEDS_BUILD" -eq 1 ]; then
    mvn clean package -q
    JAR=$(find target -maxdepth 1 -name "*.jar" ! -name "*-thin*" ! -name "original-*" 2>/dev/null | head -1)
    if [ -z "$JAR" ]; then
        echo "ERROR: build finished but no JAR found in target/"; exit 1
    fi
    MAIN=$(unzip -p "$JAR" META-INF/MANIFEST.MF 2>/dev/null | grep "^Main-Class" || true)
    if [ -z "$MAIN" ]; then
        echo "ERROR: Main-Class missing from manifest. Run 'bash verify.sh'."; exit 1
    fi
    echo "[soundboard] Build OK: $JAR"
fi

echo "[soundboard] Launching $JAR ..."
exec java -jar "$JAR"
