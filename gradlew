#!/bin/bash
export TERM=dumb
JAVA_CMD=java
if [ -n "$JAVA_HOME" ]; then
  JAVA_CMD="$JAVA_HOME/bin/java"
fi
exec "$JAVA_CMD" -Dorg.gradle.jvmargs="$ORG_GRADLE_OPTS" -jar "$(dirname "$0")/gradle/wrapper/gradle-wrapper.jar" "$@"
