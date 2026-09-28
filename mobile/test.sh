#!/usr/bin/env bash
# Runs the JVM unit tests for the app's core (networking, scanning, bridge,
# commands, markdown, storage) against mock Ollama / LaunchBridge servers.
set -euo pipefail
cd "$(dirname "$0")"

LIBS=.cache/libs
mkdir -p "$LIBS"
fetch() { # group/artifact/version/file
  local dst="$LIBS/$(basename "$1")"
  [ -s "$dst" ] || curl -fsSL --retry 3 -o "$dst" "https://repo1.maven.org/maven2/$1"
}
fetch junit/junit/4.13.2/junit-4.13.2.jar
fetch org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar
# Android's own org.json implementation, repackaged for the JVM.
fetch com/vaadin/external/google/android-json/0.0.20131108.vaadin1/android-json-0.0.20131108.vaadin1.jar

CP="$LIBS/junit-4.13.2.jar:$LIBS/hamcrest-core-1.3.jar:$LIBS/android-json-0.0.20131108.vaadin1.jar"
OUT=build/test-classes
rm -rf "$OUT" && mkdir -p "$OUT"
javac -nowarn -encoding UTF-8 --release 8 -Xlint:-options -cp "$CP" -d "$OUT" \
  $(find src/com/omnideck/mobile/core test -name '*.java') 2>&1 | grep -v '^Picked up JAVA_TOOL_OPTIONS' || true
[ -f "$OUT/com/omnideck/mobile/core/OllamaClientTest.class" ] || { echo "compile failed"; exit 1; }

TESTS=$(cd test && find . -name '*Test.java' | sed 's|^\./||; s|\.java$||; s|/|.|g' | sort)
java -cp "$OUT:$CP" org.junit.runner.JUnitCore $TESTS 2>&1 | grep -v '^Picked up JAVA_TOOL_OPTIONS'
