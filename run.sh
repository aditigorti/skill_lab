#!/bin/sh
# Compiles and starts the Shared Equipment Scheduler (macOS / Linux).
# Run it from anywhere:  ./run.sh
cd "$(dirname "$0")" || exit 1
mkdir -p out
javac -encoding UTF-8 -d out src/*.java || exit 1
java -cp out Main "$@"
