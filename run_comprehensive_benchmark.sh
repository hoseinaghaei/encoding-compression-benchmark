#!/bin/bash
# Comprehensive benchmark script
# Tests 10 random seeds × 10 sample counts (1-50)

set -e

OUTPUT_DIR="benchmark_results"
REPORT_FILE="COMPREHENSIVE_REPORT.md"

# Create output directory
mkdir -p $OUTPUT_DIR

# 10 random seeds
SEEDS=(42 123 456 789 1024 2048 3141 5678 8192 9999)

# 10 sample counts from 1 to 50
SAMPLE_COUNTS=(1 5 10 15 20 25 30 35 40 50)

echo "=========================================="
echo "Comprehensive IoTDB Encoding Benchmark"
echo "=========================================="
echo "Seeds: ${SEEDS[@]}"
echo "Sample Counts: ${SAMPLE_COUNTS[@]}"
echo "Total runs: $((${#SEEDS[@]} * ${#SAMPLE_COUNTS[@]})) = 100 runs"
echo ""

# Initialize report
cat > $REPORT_FILE << 'EOF'
# Comprehensive IoTDB Encoding & Compression Benchmark Report

**Generated:** $(date)

## Executive Summary

This report analyzes the performance of encoding and compression algorithms for IoT time-series data across:
- **10 different random seeds**: 42, 123, 456, 789, 1024, 2048, 3141, 5678, 8192, 9999
- **10 sample counts**: 1, 5, 10, 15, 20, 25, 30, 35, 40, 50
- **Total benchmark runs**: 100

## Methodology

### Data Generation
- **Device**: root.sg1.d1
- **Measurements**: temperature (temp) and humidity (hum)
- **Time interval**: 10ms (10,000 microseconds)
- **Value distribution**: Gaussian with mean=24, variance=1 (σ=1)
- **Base timestamp**: 1697040000000 (epoch milliseconds)

### Algorithms Tested

#### Encoding Algorithms
1. **DELTA_VARINT**: Delta encoding with zigzag varint compression
2. **GORILLA**: XOR-based encoding for floating-point values

#### Compression Algorithms
1. **GZIP**: Standard GZIP compression
2. **ZLIB**: Raw DEFLATE compression (no GZIP headers)

#### Hybrid Combinations
- DELTA_VARINT + GZIP
- DELTA_VARINT + ZLIB
- GORILLA + GZIP
- GORILLA + ZLIB

### Targets
- **timestamps**: Encode/compress timestamps only
- **values**: Encode/compress sensor values only
- **combined**: Encode/compress all data

---

## Benchmark Results

EOF

# Counter
total_runs=$((${#SEEDS[@]} * ${#SAMPLE_COUNTS[@]}))
current_run=0

# Run benchmarks
for sample_count in "${SAMPLE_COUNTS[@]}"; do
    echo "================================================"
    echo "Running benchmarks for SAMPLE_COUNT=$sample_count"
    echo "================================================"
    
    # Update BatchData.java with current sample count
    sed -i.bak "s/public static final int SAMPLE_COUNT = [0-9]*;/public static final int SAMPLE_COUNT = $sample_count;/" src/BatchData.java
    
    # Compile
    echo "Compiling..."
    javac -d bin src/*.java 2>/dev/null || {
        echo "Compilation failed for SAMPLE_COUNT=$sample_count"
        continue
    }
    
    for seed in "${SEEDS[@]}"; do
        current_run=$((current_run + 1))
        echo "[$current_run/$total_runs] Running: SAMPLE_COUNT=$sample_count, SEED=$seed"
        
        # Create subdirectory for this run
        RUN_DIR="$OUTPUT_DIR/samples_${sample_count}_seed_${seed}"
        mkdir -p $RUN_DIR
        
        # Run hybrid benchmark (includes encoding and compression)
        java -cp bin HybridBenchmark $seed 2>/dev/null || {
            echo "  ⚠️  Benchmark failed"
            continue
        }
        
        # Move results
        mv hybrid_res.csv "$RUN_DIR/" 2>/dev/null
        
        echo "  ✓ Completed"
    done
    
    echo ""
done

# Restore original BatchData.java
mv src/BatchData.java.bak src/BatchData.java 2>/dev/null || true

echo "=========================================="
echo "All benchmarks completed!"
echo "Results saved in: $OUTPUT_DIR/"
echo "=========================================="
echo ""
echo "Analyzing results and generating report..."

# Generate analysis
python3 << 'PYTHON_SCRIPT'
import csv
import os
from collections import defaultdict
import statistics

output_dir = "benchmark_results"
report_file = "COMPREHENSIVE_REPORT.md"

# Data structures
results_by_sample = defaultdict(list)
best_combinations = []

# Read all results
for root, dirs, files in os.walk(output_dir):
    for file in files:
        if file == "hybrid_res.csv":
            filepath = os.path.join(root, file)
            # Extract sample count and seed from path
            parts = root.split('_')
            if len(parts) >= 4:
                sample_count = int(parts[1])
                seed = int(parts[3])
                
                with open(filepath, 'r') as f:
                    reader = csv.DictReader(f)
                    for row in reader:
                        row['sample_count'] = sample_count
                        row['seed'] = seed
                        results_by_sample[sample_count].append(row)

# Analyze results
with open(report_file, 'a') as report:
    report.write("\n### Results by Sample Count\n\n")
    
    for sample_count in sorted(results_by_sample.keys()):
        rows = results_by_sample[sample_count]
        
        # Filter for timestamps target
        timestamp_results = [r for r in rows 
                           if r['encoding_target'] == 'timestamps' 
                           and r['compression_target'] == 'timestamps']
        
        if not timestamp_results:
            continue
            
        report.write(f"\n#### Sample Count: {sample_count}\n\n")
        report.write(f"**Original size**: {int(sample_count) * 8} bytes (timestamps)\n\n")
        
        # Group by algorithm combination
        by_algo = defaultdict(list)
        for r in timestamp_results:
            key = f"{r['encoder']}+{r['compressor']}"
            by_algo[key].append(r)
        
        # Calculate averages
        report.write("| Algorithm | Avg Encode+Compress (ns) | Avg Decompress+Decode (ns) | Avg Final Size (bytes) | Avg Ratio |\n")
        report.write("|-----------|-------------------------|---------------------------|----------------------|----------|\n")
        
        for algo, data in sorted(by_algo.items()):
            avg_enc_time = statistics.mean([float(r['total_encode_compress_time_ns']) for r in data])
            avg_dec_time = statistics.mean([float(r['total_decompress_decode_time_ns']) for r in data])
            avg_size = statistics.mean([float(r['compressed_size_bytes']) for r in data])
            avg_ratio = statistics.mean([float(r['total_ratio']) for r in data])
            
            report.write(f"| {algo} | {avg_enc_time:.0f} | {avg_dec_time:.0f} | {avg_size:.1f} | {avg_ratio:.4f} |\n")
            
            # Track best combinations
            best_combinations.append({
                'sample_count': sample_count,
                'algorithm': algo,
                'avg_enc_time': avg_enc_time,
                'avg_dec_time': avg_dec_time,
                'avg_size': avg_size,
                'avg_ratio': avg_ratio
            })
        
        report.write("\n")
    
    # Find overall best
    report.write("\n---\n\n## Overall Best Combinations\n\n")
    
    # Best by compression ratio
    best_by_ratio = min(best_combinations, key=lambda x: x['avg_ratio'])
    report.write(f"### 🏆 Best Compression Ratio\n\n")
    report.write(f"- **Algorithm**: {best_by_ratio['algorithm']}\n")
    report.write(f"- **Sample Count**: {best_by_ratio['sample_count']}\n")
    report.write(f"- **Compression Ratio**: {best_by_ratio['avg_ratio']:.4f}\n")
    report.write(f"- **Final Size**: {best_by_ratio['avg_size']:.1f} bytes\n")
    report.write(f"- **Encode+Compress Time**: {best_by_ratio['avg_enc_time']:.0f} ns\n")
    report.write(f"- **Decompress+Decode Time**: {best_by_ratio['avg_dec_time']:.0f} ns\n\n")
    
    # Best by speed (encode+compress)
    best_by_speed = min(best_combinations, key=lambda x: x['avg_enc_time'])
    report.write(f"### ⚡ Fastest Encoding+Compression\n\n")
    report.write(f"- **Algorithm**: {best_by_speed['algorithm']}\n")
    report.write(f"- **Sample Count**: {best_by_speed['sample_count']}\n")
    report.write(f"- **Encode+Compress Time**: {best_by_speed['avg_enc_time']:.0f} ns\n")
    report.write(f"- **Compression Ratio**: {best_by_speed['avg_ratio']:.4f}\n")
    report.write(f"- **Final Size**: {best_by_speed['avg_size']:.1f} bytes\n\n")
    
    # Best balanced (ratio * time)
    for combo in best_combinations:
        combo['score'] = combo['avg_ratio'] * (combo['avg_enc_time'] / 10000)
    best_balanced = min(best_combinations, key=lambda x: x['score'])
    
    report.write(f"### ⚖️ Best Balanced (Ratio × Time)\n\n")
    report.write(f"- **Algorithm**: {best_balanced['algorithm']}\n")
    report.write(f"- **Sample Count**: {best_balanced['sample_count']}\n")
    report.write(f"- **Compression Ratio**: {best_balanced['avg_ratio']:.4f}\n")
    report.write(f"- **Encode+Compress Time**: {best_balanced['avg_enc_time']:.0f} ns\n")
    report.write(f"- **Final Size**: {best_balanced['avg_size']:.1f} bytes\n")
    report.write(f"- **Balance Score**: {best_balanced['score']:.4f} (lower is better)\n\n")

print("Analysis complete!")

PYTHON_SCRIPT

echo ""
echo "=========================================="
echo "Report generated: $REPORT_FILE"
echo "=========================================="
echo ""
echo "Opening report..."
cat $REPORT_FILE

