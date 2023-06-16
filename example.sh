#!/bin/bash

cd $(dirname $0)
pwd=$(pwd)

./run.sh $pwd/example/repo-one:. $pwd/example/repo-two:.
