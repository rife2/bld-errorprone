#!/bin/bash

java -jar example/spotbugs-4.*/lib/spotbugs.jar -textui 2>&1 |
  grep -oE '^\s+-[a-zA-Z][a-zA-Z]+' | sed 's/^[[:space:]]*//' | sort -u >src/test/resources/spotbugs-args.txt

