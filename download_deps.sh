#!/bin/bash

# Create lib directory
mkdir -p lib

# Download Snappy
echo "Downloading Snappy..."
curl -L -o lib/snappy-java-1.1.10.5.jar \
  "https://repo1.maven.org/maven2/org/xerial/snappy/snappy-java/1.1.10.5/snappy-java-1.1.10.5.jar"

# Download LZ4
echo "Downloading LZ4..."
curl -L -o lib/lz4-java-1.8.0.jar \
  "https://repo1.maven.org/maven2/org/lz4/lz4-java/1.8.0/lz4-java-1.8.0.jar"

echo "Dependencies downloaded to lib/"
ls -lh lib/

