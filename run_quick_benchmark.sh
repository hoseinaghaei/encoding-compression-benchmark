#!/bin/bash

# Quick benchmark with working algorithms
# Tests 5 seeds and 5 sample counts

echo "=== Quick IoTDB Encoding & Compression Benchmark ==="
echo ""

# Create results directory
mkdir -p results_quick

# Sample counts to test
SAMPLE_COUNTS=(5 10 15 20 25)

# Generate 5 random seeds
SEEDS=(42 123 456 789 1024)

echo "Sample counts: ${SAMPLE_COUNTS[@]}"
echo "Seeds: ${SAMPLE_COUNTS[@]}"
echo ""

TOTAL_RUNS=$((${#SEEDS[@]} * ${#SAMPLE_COUNTS[@]}))
CURRENT_RUN=0

# Run benchmarks for each combination
for SEED in "${SEEDS[@]}"; do
    for SAMPLE_COUNT in "${SAMPLE_COUNTS[@]}"; do
        CURRENT_RUN=$((CURRENT_RUN + 1))
        echo "[$CURRENT_RUN/$TOTAL_RUNS] Running with seed=$SEED, sample_count=$SAMPLE_COUNT"
        
        # Update BatchData.java with current SAMPLE_COUNT
        sed -i.bak "s/public static final int SAMPLE_COUNT = [0-9]*/public static final int SAMPLE_COUNT = $SAMPLE_COUNT/" src/BatchData.java
        
        # Rebuild with new sample count
        make build > /dev/null 2>&1
        
        # Run encoding benchmark
        java -cp "bin:lib/*" EncodingBenchmarkNew $SEED > /dev/null 2>&1
        if [ -f res.csv ]; then
            # Filter out failing algorithms
            grep -v "CHIMP\|GOLOMB_RICE\|SPRINTZ\|RLBE\|RAKE" res.csv > "results_quick/encoding_seed${SEED}_samples${SAMPLE_COUNT}.csv"
        fi
        
        # Run compression benchmark
        java -cp "bin:lib/*" CompressionBenchmarkNew $SEED > /dev/null 2>&1
        if [ -f compression_res.csv ]; then
            mv compression_res.csv "results_quick/compression_seed${SEED}_samples${SAMPLE_COUNT}.csv"
        fi
        
        # Run hybrid benchmark
        java -cp "bin:lib/*" HybridBenchmarkNew $SEED > /dev/null 2>&1
        if [ -f hybrid_res.csv ]; then
            mv hybrid_res.csv "results_quick/hybrid_seed${SEED}_samples${SAMPLE_COUNT}.csv"
        fi
    done
done

# Restore original BatchData.java
mv src/BatchData.java.bak src/BatchData.java
make build > /dev/null 2>&1

echo ""
echo "=== Benchmark Completed ==="
echo "Total runs: $TOTAL_RUNS"
echo "Results saved in results_quick/"
echo ""

# Analyze results
echo "=== Top Results Analysis ==="
echo ""

echo "📊 ENCODING - Best Compression Ratios:"
echo "Algorithm | Target | Avg Ratio"
echo "----------|--------|----------"
for file in results_quick/encoding_*.csv; do
    tail -n +2 "$file"
done | awk -F',' '{sum[$1","$2]+=$7; count[$1","$2]++} END {for (key in sum) print key","sum[key]/count[key]}' | sort -t',' -k3 -n | head -10 | awk -F',' '{printf "%-12s | %-10s | %.4f\n", $1, $2, $3}'

echo ""
echo "📊 COMPRESSION - Best Compression Ratios:"
echo "Algorithm | Target | Avg Ratio"
echo "----------|--------|----------"
for file in results_quick/compression_*.csv; do
    tail -n +2 "$file"
done | awk -F',' '{sum[$1","$2]+=$7; count[$1","$2]++} END {for (key in sum) print key","sum[key]/count[key]}' | sort -t',' -k3 -n | head -10 | awk -F',' '{printf "%-12s | %-10s | %.4f\n", $1, $2, $3}'

echo ""
echo "📊 HYBRID - Best Final Ratios:"
echo "Encoder | Compressor | Target | Avg Final Ratio"
echo "--------|------------|--------|----------------"
for file in results_quick/hybrid_*.csv; do
    tail -n +2 "$file"
done | awk -F',' '{sum[$1","$2","$3]+=$12; count[$1","$2","$3]++} END {for (key in sum) print key","sum[key]/count[key]}' | sort -t',' -k4 -n | head -10 | awk -F',' '{printf "%-10s | %-12s | %-10s | %.4f\n", $1, $2, $3, $4}'

echo ""
echo "✅ Done! Check results_quick/ directory for detailed CSV files"

