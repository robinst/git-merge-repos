#!/bin/bash


cd $(dirname $0)
(mkdir repo-one && cd repo-one && git init && cat ../repo-one.git-fast-export | git fast-import)
(mkdir repo-two && cd repo-two && git init && cat ../repo-two.git-fast-export | git fast-import)
