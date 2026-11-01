#!/bin/sh
# ----------------------------------------------------------------------------
# Lightweight Maven wrapper.
#
# Downloads the pinned Maven version the first time it runs (into
# ~/.m2/wrapper/dists), then runs it. The only thing you need installed to
# build this project is a JDK (21 or newer).
#
# Usage: ./mvnw spring-boot:run      ./mvnw test      ./mvnw package
# ----------------------------------------------------------------------------
set -eu

MAVEN_VERSION=3.9.11
DEFAULT_DIST_URL="https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/$MAVEN_VERSION/apache-maven-$MAVEN_VERSION-bin.zip"

BASE_DIR=$(cd "$(dirname "$0")" && pwd)
PROPS_FILE="$BASE_DIR/.mvn/wrapper/maven-wrapper.properties"

# An optional .mvn/wrapper/maven-wrapper.properties may override the download URL.
DIST_URL=""
if [ -f "$PROPS_FILE" ]; then
  DIST_URL=$(sed -n 's/^distributionUrl=//p' "$PROPS_FILE" | tr -d '\r')
fi
DIST_URL=${DIST_URL:-$DEFAULT_DIST_URL}
DIST_FILE=${DIST_URL##*/}          # e.g. apache-maven-3.9.11-bin.zip
DIST_NAME=${DIST_FILE%-bin.zip}    # e.g. apache-maven-3.9.11
DISTS_DIR="${MAVEN_USER_HOME:-$HOME/.m2}/wrapper/dists"
MAVEN_DIR="$DISTS_DIR/$DIST_NAME"

if ! command -v java >/dev/null 2>&1 && [ -z "${JAVA_HOME:-}" ]; then
  echo "Error: no JDK found. Install JDK 21+ (e.g. https://adoptium.net) and try again." >&2
  exit 1
fi

if [ ! -x "$MAVEN_DIR/bin/mvn" ]; then
  echo "Downloading $DIST_NAME (first run only)..." >&2
  mkdir -p "$DISTS_DIR"
  TMP_DIR=$(mktemp -d "$DISTS_DIR/.download.XXXXXX")
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL -o "$TMP_DIR/$DIST_FILE" "$DIST_URL"
  elif command -v wget >/dev/null 2>&1; then
    wget -q -O "$TMP_DIR/$DIST_FILE" "$DIST_URL"
  else
    echo "Error: curl or wget is needed to download Maven." >&2
    rm -rf "$TMP_DIR"
    exit 1
  fi
  unzip -q "$TMP_DIR/$DIST_FILE" -d "$TMP_DIR"
  rm -rf "$MAVEN_DIR"
  mv "$TMP_DIR/$DIST_NAME" "$MAVEN_DIR"
  rm -rf "$TMP_DIR"
  chmod +x "$MAVEN_DIR/bin/mvn"
fi

cd "$BASE_DIR"
exec "$MAVEN_DIR/bin/mvn" "$@"
