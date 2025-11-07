#!/bin/bash
# Clean build artifacts

echo "Cleaning build artifacts..."

# Remove compiled classes
rm -rf bin/*.class

# Optionally remove results
if [ "$1" == "--all" ]; then
    echo "Removing results file..."
    rm -f res.csv
fi

echo "Clean completed!"

