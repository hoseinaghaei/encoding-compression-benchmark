#!/bin/bash
# Run the encoding benchmark

set -e

# Default seed
SEED=${1:-42}

# Check if bin directory exists
if [ ! -d "bin" ]; then
    echo "Error: bin/ directory not found. Run ./build.sh first."
    exit 1
fi

# Check if classes are compiled
if [ ! -f "bin/EncodingBenchmark.class" ]; then
    echo "Error: EncodingBenchmark.class not found. Run ./build.sh first."
    exit 1
fi

echo "Running encoding benchmark with seed=$SEED..."
java -cp bin EncodingBenchmark $SEED

echo ""
echo "Results saved to res.csv"
echo ""
echo "Summary:"
cat res.csv | column -t -s,

