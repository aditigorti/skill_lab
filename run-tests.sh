#!/bin/sh
# Compiles the program and the tests, then runs the tests (macOS / Linux).
# Run it from anywhere:  ./run-tests.sh
cd "$(dirname "$0")" || exit 1
mkdir -p out-test
javac -encoding UTF-8 -d out-test src/*.java test/*.java || exit 1
java -cp out-test SchedulerTests
