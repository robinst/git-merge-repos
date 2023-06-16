#!/bin/sh

BASEDIR=`dirname $0`
./mvnw --file "$BASEDIR/pom.xml" --quiet clean compile exec:java -Dexec.args="$*"
