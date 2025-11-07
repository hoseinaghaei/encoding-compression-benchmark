# Quick Start Guide - IoTDB Encoding & Compression Benchmark

## 🚀 Get Started in 3 Steps

### Step 1: Build the Project

```bash
cd /Users/hossein/Desktop/db-encoding
make build
```

This will:
- Download Snappy and LZ4 dependencies
- Compile all Java source files
- Create the `bin/` directory

### Step 2: Run a Quick Test

```bash
make run-encoding
```

This runs the encoding benchmark and creates `encoding_res.csv`.

### Step 3: Run Full Benchmark (Optional)

```bash
chmod +x run_comprehensive_benchmark_new.sh
./run_comprehensive_benchmark_new.sh
```

This runs 100 benchmark combinations and generates a comprehensive report.

---

## 📊 What's Been Implemented

### Encoding Algorithms (12 total)

| Algorithm | Best For | Compression |
|-----------|----------|-------------|
| **IDENTITY** | Baseline | None |
| **TS_2DIFF** | Timestamps | 80-90% |
| **GORILLA** | Float values | 70-85% |
| **CHIMP** | Float values | 75-90% |
| **BIT_PACKING** | Integer data | 40-60% |
| **RLE** | Repetitive data | 50-95% |
| **HUFFMAN** | Text/symbols | 30-50% |
| **GOLOMB_RICE** | Geometric dist | 40-70% |
| **SPRINTZ** | Time-series | 75-90% |
| **RLBE** | Mixed data | 60-80% |
| **RAKE** | General data | 50-70% |
| **DICTIONARY** | Text data | 60-90% |

### Compression Algorithms (5 total)

| Algorithm | Speed | Compression | Use Case |
|-----------|-------|-------------|----------|
| **IDENTITY** | Instant | None | Baseline |
| **LZ4** | Very Fast | 40-60% | Real-time |
| **SNAPPY** | Fast | 45-65% | Real-time |
| **ZLIB** | Medium | 60-80% | Storage |
| **GZIP** | Medium | 65-85% | Storage |

### Hybrid Combinations

The benchmark tests **36 hybrid combinations**:
- 3 encoders (TS_2DIFF, GORILLA, RLE)
- 4 compressors (GZIP, SNAPPY, LZ4, ZLIB)
- 3 targets (timestamps, values, combined)

---

## 📁 Key Files

### Source Code
- `src/EncodingBenchmarkNew.java` - 12 encoding algorithms
- `src/CompressionBenchmarkNew.java` - 5 compression algorithms
- `src/HybridBenchmarkNew.java` - Hybrid combinations
- `src/BatchData.java` - Deterministic data generation

### Documentation
- `README_NEW.md` - Complete user guide
- `IMPLEMENTATION_STATUS.md` - Implementation details
- `COMPREHENSIVE_REPORT.md` - Previous benchmark results
- `QUICK_START.md` - This file

### Scripts
- `Makefile` - Build automation
- `download_deps.sh` - Download dependencies
- `run_comprehensive_benchmark_new.sh` - Full benchmark suite

---

## 🎯 Expected Results

### For Timestamps
```
Best: TS_2DIFF + GZIP
- Compression: ~90% reduction
- Time: ~50 μs total
```

### For Sensor Values
```
Best: GORILLA + LZ4
- Compression: ~75% reduction
- Time: ~40 μs total
```

### For Combined Data
```
Best: SPRINTZ + GZIP
- Compression: ~85% reduction
- Time: ~80 μs total
```

---

## 🔧 Customization

### Change Sample Count

Edit `src/BatchData.java`:

```java
public static final int SAMPLE_COUNT = 20; // Change this
```

### Change Data Variance

Edit `src/BatchData.java`:

```java
private static final float VARIANCE = 1.0f; // Change this
```

### Run with Different Seed

```bash
java -cp "bin:lib/*" EncodingBenchmarkNew 12345
```

---

## 📈 Benchmark Output

### Encoding Results (`encoding_res.csv`)

```csv
algorithm,target,encode_time_ns,decode_time_ns,original_size_bytes,encoded_size_bytes,encoding_ratio
TS_2DIFF,timestamps,8500,7200,800,120,0.1500
GORILLA,values,6800,5900,400,95,0.2375
...
```

### Compression Results (`compression_res.csv`)

```csv
algorithm,target,compress_time_ns,decompress_time_ns,original_size_bytes,compressed_size_bytes,compression_ratio
GZIP,timestamps,25000,18000,800,85,0.1063
LZ4,values,12000,8500,400,110,0.2750
...
```

### Hybrid Results (`hybrid_res.csv`)

```csv
encoder,compressor,target,encode_time_ns,compress_time_ns,decompress_time_ns,decode_time_ns,original_size_bytes,encoded_size_bytes,final_size_bytes,encoding_ratio,final_ratio
TS_2DIFF,GZIP,timestamps,8500,25000,18000,7200,800,120,68,0.1500,0.0850
...
```

---

## 🐛 Troubleshooting

### "Java not found"

Install Java 11+:
```bash
# macOS
brew install openjdk@11

# Or download from:
# https://adoptium.net/
```

### "Dependencies not downloaded"

Run manually:
```bash
chmod +x download_deps.sh
./download_deps.sh
```

### "Build failed"

Clean and rebuild:
```bash
make clean-all
make build
```

---

## 📚 Further Reading

- **README_NEW.md** - Detailed documentation
- **IMPLEMENTATION_STATUS.md** - What's been implemented
- **COMPREHENSIVE_REPORT.md** - Previous benchmark analysis

---

## ✅ Verification Checklist

- [ ] Java 11+ installed (`java -version`)
- [ ] Dependencies downloaded (`ls lib/`)
- [ ] Project built (`make build`)
- [ ] Quick test passed (`make run-encoding`)
- [ ] Results generated (`ls *.csv`)

---

## 🎉 You're Ready!

Everything is implemented and ready to run. Just execute:

```bash
make build && make run-all
```

This will run all benchmarks and generate results in CSV format.

For the comprehensive analysis with 100 runs:

```bash
./run_comprehensive_benchmark_new.sh
```

Then check `COMPREHENSIVE_REPORT_NEW.md` for the full analysis!

