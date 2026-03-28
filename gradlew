#!/bin/sh
APP_NAME="Gradle"
APP_BASE_NAME=`basename "$0"`
DEFAULT_JVM_OPTS="-Xmx64m -Xms64m"
die () { echo; echo "ERROR: $*"; echo; exit 1; } >&2
warn () { echo "$*"; } >&2
SAVED="`pwd`"
cd "`dirname \"$0\"`/" >/dev/null
APP_HOME="`pwd -P`"
cd "$SAVED" >/dev/null
CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar
JAVACMD=java
exec "$JAVACMD" $DEFAULT_JVM_OPTS $JAVA_OPTS $GRADLE_OPTS \
  -classpath "$CLASSPATH" \
  org.gradle.wrapper.GradleWrapperMain \
  "$@"
