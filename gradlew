#!/bin/sh
# Gradle wrapper startup script for POSIX

##############################################################################
APP_HOME="$(cd "${APP_HOME:-./}" > /dev/null && pwd -P)"
DEFAULT_JVM_OPTS='"-Xmx64m" "-Xms64m"'

# Resolve links
PRG="$0"
while [ -h "$PRG" ] ; do
    ls=`ls -ld "$PRG"`
    link=`expr "$ls" : '.*-> \(.*\)$'`
    if expr "$link" : '/.*' > /dev/null; then
        PRG="$link"
    else
        PRG=`dirname "$PRG"`"/$link"
    fi
done

CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

# Find java
if [ -n "$JAVA_HOME" ] ; then
    JAVACMD="$JAVA_HOME/bin/java"
else
    JAVACMD="java"
fi

exec "$JAVACMD" $DEFAULT_JVM_OPTS $JAVA_OPTS $GRADLE_OPTS "-Dorg.gradle.appname=gradle" -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"