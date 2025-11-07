#!/bin/bash
# Run the compression benchmark

set -e

# Default seed
SEED=${1:-42}

# Check if bin directory exists
if [ ! -d "bin" ]; then
    echo "Error: bin/ directory not found. Run ./build.sh first."
    exit 1
fi

# Check if classes are compiled
if [ ! -f "bin/CompressionBenchmark.class" ]; then
    echo "Error: CompressionBenchmark.class not found. Run ./build.sh first."
    exit 1
fi

echo "Running compression benchmark with seed=$SEED..."
java -cp bin CompressionBenchmark $SEED

echo ""
echo "Results saved to compression_res.csv"
echo ""
echo "Summary:"
cat compression_res.csv | column -t -s,

