#!/bin/bash
# Build script for IoTDB Encoding Benchmark

set -e

echo "Building IoTDB Encoding Benchmark..."

# Create directories if they don't exist
mkdir -p bin

# Compile all Java source files
javac -d bin src/*.java

echo "Build completed successfully!"
echo "Compiled classes are in bin/ directory"

