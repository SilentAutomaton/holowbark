#!/bin/sh

##############################################################################
# Gradle startup script for POSIX systems
##############################################################################

APP_BASE_NAME=`basename "$0"`
APP_HOME=`dirname "$0"`
APP_HOME=`cd "$APP_HOME" && pwd`

GRADLE_WRAPPER_JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
GRADLE_WRAPPER_PROPERTIES="$APP_HOME/gradle/wrapper/gradle-wrapper.properties"

# Find java
if [ -n "$JAVA_HOME" ] ; then
    JAVACMD="$JAVA_HOME/bin/java"
else
    JAVACMD=java
fi

# JVM opts
exec "$JAVACMD" \
  -classpath "$GRADLE_WRAPPER_JAR" \
  "-Dorg.gradle.appname=$APP_BASE_NAME" \
  org.gradle.wrapper.GradleWrapperMain \
  "$@"
