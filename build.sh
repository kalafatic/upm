#!/usr/bin/env bash
# Builds the Universal Project Manager executable JAR (Java 21, zero dependencies).
set -euo pipefail
APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAVAC_BIN="${UPM_JAVAC:-/usr/lib/jvm/java-21-openjdk-amd64/bin/javac}"
JAR_BIN="${UPM_JAR:-/usr/lib/jvm/java-21-openjdk-amd64/bin/jar}"

BUILD_DIR="$APP_DIR/build"
CLASSES="$BUILD_DIR/classes"

mkdir -p "$CLASSES"

echo "Compiling Universal Project Manager backend (JDK 21, zero dependencies)..."
"$JAVAC_BIN" -Xlint:-unchecked -d "$CLASSES" "$APP_DIR"/src/main/java/upm/*.java

echo "Embedding web interface..."
mkdir -p "$CLASSES/web"
cp -r "$APP_DIR/web/." "$CLASSES/web/"

echo "Creating universal-project-manager.jar..."
"$JAR_BIN" --create --file "$APP_DIR/universal-project-manager.jar" \
  --main-class upm.Main -C "$CLASSES" .

echo "OK: $APP_DIR/universal-project-manager.jar"
