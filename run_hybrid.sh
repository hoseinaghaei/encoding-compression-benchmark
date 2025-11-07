#!/bin/bash
# Run the hybrid (encoding + compression) benchmark

set -e

# Default seed
SEED=${1:-42}

# Check if bin directory exists
if [ ! -d "bin" ]; then
    echo "Error: bin/ directory not found. Run ./build.sh first."
    exit 1
fi

# Check if classes are compiled
if [ ! -f "bin/HybridBenchmark.class" ]; then
    echo "Error: HybridBenchmark.class not found. Run ./build.sh first."
    exit 1
fi

echo "Running hybrid benchmark with seed=$SEED..."
echo "Testing all combinations of encoding + compression..."
java -cp bin HybridBenchmark $SEED

echo ""
echo "Results saved to hybrid_res.csv"
echo ""
echo "Summary (first 20 combinations):"
head -21 hybrid_res.csv | column -t -s,

