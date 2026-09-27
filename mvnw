#!/bin/sh
# ----------------------------------------------------------------------------
# Apache Maven Wrapper (portable, simplified)
# Downloads Maven on first run into ~/.m2/wrapper/dists, then delegates.
# Usage: ./mvnw clean compile
# ----------------------------------------------------------------------------
set -e

# 1. 定位项目根目录（向上查找含 .mvn 的目录）
BASE_DIR="$(pwd)"
WDIR="$(pwd)"
while [ "$WDIR" != "/" ] && [ -n "$WDIR" ]; do
  if [ -d "$WDIR/.mvn" ]; then BASE_DIR="$WDIR"; break; fi
  WDIR="$(cd "$WDIR/.." && pwd)"
done

PROPS="$BASE_DIR/.mvn/wrapper/maven-wrapper.properties"
if [ ! -f "$PROPS" ]; then
  echo "ERROR: not found: $PROPS" >&2
  exit 1
fi

# 2. 读取 distributionUrl
DIST_URL="$(sed -n 's/^distributionUrl=//p' "$PROPS" | tr -d '\r' | tail -n 1)"
if [ -z "$DIST_URL" ]; then
  echo "ERROR: distributionUrl missing in $PROPS" >&2
  exit 1
fi

# 3. 本地安装路径：~/.m2/wrapper/dists/apache-maven-3.9.9
DIST_ZIP="$(basename "$DIST_URL")"
DIST_NAME="$(echo "$DIST_ZIP" | sed 's/-bin\.zip$//; s/\.zip$//')"
MAVEN_USER_HOME="${MAVEN_USER_HOME:-$HOME/.m2}"
DISTS_DIR="$MAVEN_USER_HOME/wrapper/dists"
MAVEN_HOME="$DISTS_DIR/$DIST_NAME"

# 4. 首次运行时下载并解压
if [ ! -x "$MAVEN_HOME/bin/mvn" ]; then
  echo ">> First run: downloading $DIST_URL"
  mkdir -p "$DISTS_DIR"
  TMP_ZIP="$DISTS_DIR/$DIST_ZIP.tmp"
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL -o "$TMP_ZIP" "$DIST_URL"
  elif command -v wget >/dev/null 2>&1; then
    wget -q -O "$TMP_ZIP" "$DIST_URL"
  else
    echo "ERROR: need curl or wget" >&2; exit 1
  fi
  echo ">> Extracting to $DISTS_DIR"
  if command -v unzip >/dev/null 2>&1; then
    unzip -q -o "$TMP_ZIP" -d "$DISTS_DIR"
  else
    echo "ERROR: need unzip" >&2; exit 1
  fi
  rm -f "$TMP_ZIP"
  chmod +x "$MAVEN_HOME/bin/mvn" 2>/dev/null || true
fi

# 5. 委托执行
exec "$MAVEN_HOME/bin/mvn" "$@"
