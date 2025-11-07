# IoTDB Encoding & Compression Benchmark - New Algorithms

## Overview

This project benchmarks **11 encoding algorithms** and **5 compression algorithms** for IoT time-series data destined for Apache IoTDB via MQTT.

### Encoding Algorithms

1. **IDENTITY** - Baseline (no encoding)
2. **TS_2DIFF** - Delta-delta encoding for timestamps
3. **GORILLA** - Facebook's Gorilla compression for floating-point values
4. **BIT_PACKING** - Bit-level packing to remove leading zeros
5. **RLE** - Run-Length Encoding for consecutive identical values
6. **HUFFMAN** - Huffman coding for frequency-based compression
7. **GOLOMB_RICE** - Golomb-Rice coding for geometrically distributed data
8. **SPRINTZ** - Hybrid encoding (predictive + bit-packing + RLE)
9. **RLBE** - Run-Length-Bit-Encoding (delta + RLE + Fibonacci)
10. **RAKE** - Simple and efficient lossless compression
11. **DICTIONARY** - Dictionary-based encoding for text data
12. **CHIMP** - Improvement on GORILLA for floating-point values

### Compression Algorithms

1. **IDENTITY** - Baseline (no compression)
2. **GZIP** - General-purpose DEFLATE compression
3. **SNAPPY** - Fast compression/decompression by Google
4. **LZ4** - Extremely fast compression algorithm
5. **ZLIB** - Versatile DEFLATE compression

## Prerequisites

- **Java 11+** (JDK required for compilation)
- **curl** (for downloading dependencies)
- **Python 3** (for report generation)
- **make** (for build automation)

## Quick Start

### 1. Download Dependencies

```bash
make deps
```

This downloads Snappy and LZ4 JAR files to the `lib/` directory.

### 2. Build the Project

```bash
make build
```

This compiles all Java source files to the `bin/` directory.

### 3. Run Individual Benchmarks

```bash
# Run encoding benchmark
make run-encoding

# Run compression benchmark
make run-compression

# Run hybrid benchmark (encoding + compression)
make run-hybrid

# Run all benchmarks
make run-all
```

### 4. Run Comprehensive Benchmark

To run all benchmarks with 10 random seeds and 10 different sample counts:

```bash
chmod +x run_comprehensive_benchmark_new.sh
./run_comprehensive_benchmark_new.sh
```

This will:
- Run 100 benchmark combinations (10 seeds × 10 sample counts)
- Save results to `results_new/` directory
- Generate a comprehensive report: `COMPREHENSIVE_REPORT_NEW.md`

## Project Structure

```
db-encoding/
├── src/                          # Source code
│   ├── BatchData.java            # Deterministic data generation
│   ├── EncodingBenchmarkNew.java # 11 encoding algorithms
│   ├── CompressionBenchmarkNew.java # 5 compression algorithms
│   └── HybridBenchmarkNew.java   # Hybrid combinations
├── bin/                          # Compiled classes (generated)
├── lib/                          # External dependencies (generated)
├── results_new/                  # Benchmark results (generated)
├── Makefile                      # Build automation
├── download_deps.sh              # Dependency downloader
├── run_comprehensive_benchmark_new.sh # Comprehensive benchmark script
├── README_NEW.md                 # This file
└── COMPREHENSIVE_REPORT_NEW.md   # Generated report
```

## Benchmark Targets

Each algorithm is benchmarked on three data targets:

1. **timestamps** - Long array of timestamps (8 bytes each)
2. **values** - Float array of sensor values (4 bytes each)
3. **combined** - Concatenated timestamps + values

## Metrics Collected

### Encoding Benchmarks
- Encode time (nanoseconds)
- Decode time (nanoseconds)
- Original size (bytes)
- Encoded size (bytes)
- Encoding ratio

### Compression Benchmarks
- Compress time (nanoseconds)
- Decompress time (nanoseconds)
- Original size (bytes)
- Compressed size (bytes)
- Compression ratio

### Hybrid Benchmarks
- Encode time (nanoseconds)
- Compress time (nanoseconds)
- Decompress time (nanoseconds)
- Decode time (nanoseconds)
- Original size (bytes)
- Encoded size (bytes)
- Final size (bytes)
- Encoding ratio
- Final ratio

## Customization

### Change Sample Count

Edit `src/BatchData.java`:

```java
public static final int SAMPLE_COUNT = 20; // Change this value
```

### Change Data Characteristics

Edit `src/BatchData.java`:

```java
private static final float MEAN = 24.0f;     // Mean value
private static final float VARIANCE = 1.0f;  // Variance
```

### Run with Custom Seed

```bash
java -cp "bin:lib/*" EncodingBenchmarkNew 12345
java -cp "bin:lib/*" CompressionBenchmarkNew 12345
java -cp "bin:lib/*" HybridBenchmarkNew 12345
```

## Results

Results are saved as CSV files:

- `encoding_res.csv` - Encoding benchmark results
- `compression_res.csv` - Compression benchmark results
- `hybrid_res.csv` - Hybrid benchmark results

## Cleaning Up

```bash
# Clean build artifacts
make clean

# Clean result files
make clean-results

# Clean everything (including dependencies)
make clean-all
```

## Algorithm Details

### TS_2DIFF (Delta-Delta Encoding)

Best for: Timestamps with regular intervals

- Stores first value as-is
- Stores first delta (difference between consecutive values)
- Stores delta-of-deltas for remaining values using varint encoding
- Highly effective for monotonically increasing timestamps

### GORILLA

Best for: Floating-point values with small variations

- XOR-based compression
- Stores leading and trailing zeros efficiently
- Uses control bits to indicate value changes
- Optimized for time-series sensor data

### CHIMP

Best for: Floating-point values (improvement over GORILLA)

- Enhanced version of GORILLA
- Better handling of leading zeros
- More efficient bit packing
- Improved compression ratio for certain data patterns

### SPRINTZ

Best for: General time-series data

- Hybrid approach combining multiple techniques
- Predictive encoding
- Bit-packing
- Run-length encoding
- Entropy encoding

### LZ4

Best for: Fast compression/decompression

- Extremely fast algorithm
- Good compression ratio
- Low CPU overhead
- Ideal for real-time IoT applications

### GZIP/ZLIB

Best for: Maximum compression ratio

- DEFLATE algorithm (LZ77 + Huffman)
- Higher compression ratio than LZ4
- Slower than LZ4
- Good for storage-constrained scenarios

## Performance Tips

1. **For real-time applications**: Use LZ4 or Snappy
2. **For storage optimization**: Use GZIP or ZLIB
3. **For timestamps**: Use TS_2DIFF + LZ4
4. **For sensor values**: Use GORILLA/CHIMP + LZ4
5. **For maximum compression**: Use SPRINTZ + GZIP

## Troubleshooting

### Java Not Found

Ensure Java 11+ is installed:

```bash
java -version
```

If not installed, download from: https://adoptium.net/

### Dependencies Not Downloaded

Manually download dependencies:

```bash
chmod +x download_deps.sh
./download_deps.sh
```

### Build Errors

Clean and rebuild:

```bash
make clean-all
make deps
make build
```

## References

- [Apache IoTDB](https://iotdb.apache.org/)
- [Gorilla Paper](https://www.vldb.org/pvldb/vol8/p1816-teller.pdf)
- [LZ4 Algorithm](https://github.com/lz4/lz4)
- [Snappy by Google](https://github.com/google/snappy)

## License

This project is for educational and benchmarking purposes.

