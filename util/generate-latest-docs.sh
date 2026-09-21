#!/bin/bash

set -eu

echo -e "Generating latest javadoc...\n"

rm -rf build/docs
./gradlew aggregateJavadoc --console=plain
