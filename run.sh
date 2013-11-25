#!/bin/sh

BASEDIR=`dirname $0`
mvn --file "$BASEDIR/pom.xml" --quiet clean compile exec:java -Dexec.args="$*"
