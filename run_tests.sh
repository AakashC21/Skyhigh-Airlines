#!/bin/sh
# run_tests.sh — Execute Maven tests + generate Jacoco coverage report
# Run this inside the Maven Docker container:
#   docker run --rm -v "$(pwd):/project" -w /project maven:3.9-eclipse-temurin-21 sh run_tests.sh

set -e

echo "=============================================="
echo " SkyHigh Core — Test + Coverage Run"
echo "=============================================="

mvn verify \
  -Dspring.profiles.active=test \
  --no-transfer-progress

echo ""
echo "=============================================="
echo " Coverage report: target/site/jacoco/index.html"
echo "=============================================="
