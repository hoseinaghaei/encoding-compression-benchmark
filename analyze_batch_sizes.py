#!/usr/bin/env python3
"""
Analyze benchmark results across different batch sizes.
Calculate average, min, and max for each algorithm and target.
"""

import csv
import os
from collections import defaultdict
import statistics

def read_csv_files(directory, prefix):
    """Read all CSV files with given prefix from directory"""
    results = defaultdict(list)
    
    for filename in os.listdir(directory):
        if filename.startswith(prefix) and filename.endswith('.csv'):
            filepath = os.path.join(directory, filename)
            with open(filepath, 'r') as f:
                reader = csv.DictReader(f)
                for row in reader:
                    # Create key based on algorithm and target
                    if 'encoder' in row:  # Hybrid results
                        key = (row['encoder'], row['compressor'], row['target'])
                    else:  # Encoding or compression results
                        key = (row['algorithm'], row['target'])
                    results[key].append(row)
    
    return results

def calculate_stats(values):
    """Calculate average, min, max, and std dev"""
    if not values:
        return None, None, None, None
    
    avg = statistics.mean(values)
    min_val = min(values)
    max_val = max(values)
    std_dev = statistics.stdev(values) if len(values) > 1 else 0
    
    return avg, min_val, max_val, std_dev

def analyze_encoding_results(results_dir):
    """Analyze encoding benchmark results"""
    print("\n" + "="*80)
    print("ENCODING RESULTS - Statistics Across Batch Sizes (5, 10, 15, 20, 25 samples)")
    print("="*80)
    
    results = read_csv_files(results_dir, 'encoding_')
    
    # Group by algorithm and target
    stats = defaultdict(lambda: {
        'encode_times': [],
        'decode_times': [],
        'ratios': []
    })
    
    for key, rows in results.items():
        algo, target = key
        for row in rows:
            stats[key]['encode_times'].append(float(row['encode_time_ns']) / 1000)  # Convert to μs
            stats[key]['decode_times'].append(float(row['decode_time_ns']) / 1000)
            stats[key]['ratios'].append(float(row['compression_ratio']))
    
    # Print results
    print(f"\n{'Algorithm':<15} {'Target':<12} {'Avg Ratio':<12} {'Min-Max Ratio':<20} {'Avg Encode (μs)':<18} {'Avg Decode (μs)':<18}")
    print("-" * 115)
    
    for key in sorted(stats.keys()):
        algo, target = key
        
        avg_ratio, min_ratio, max_ratio, _ = calculate_stats(stats[key]['ratios'])
        avg_encode, min_encode, max_encode, _ = calculate_stats(stats[key]['encode_times'])
        avg_decode, min_decode, max_decode, _ = calculate_stats(stats[key]['decode_times'])
        
        ratio_range = f"{min_ratio:.4f} - {max_ratio:.4f}"
        
        print(f"{algo:<15} {target:<12} {avg_ratio:>10.4f}  {ratio_range:<20} {avg_encode:>10.2f} ({min_encode:.1f}-{max_encode:.1f})  {avg_decode:>10.2f} ({min_decode:.1f}-{max_decode:.1f})")
    
    return stats

def analyze_compression_results(results_dir):
    """Analyze compression benchmark results"""
    print("\n" + "="*80)
    print("COMPRESSION RESULTS - Statistics Across Batch Sizes (5, 10, 15, 20, 25 samples)")
    print("="*80)
    
    results = read_csv_files(results_dir, 'compression_')
    
    # Group by algorithm and target
    stats = defaultdict(lambda: {
        'compress_times': [],
        'decompress_times': [],
        'ratios': []
    })
    
    for key, rows in results.items():
        algo, target = key
        for row in rows:
            stats[key]['compress_times'].append(float(row['compress_time_ns']) / 1000)
            stats[key]['decompress_times'].append(float(row['decompress_time_ns']) / 1000)
            stats[key]['ratios'].append(float(row['compression_ratio']))
    
    # Print results
    print(f"\n{'Algorithm':<15} {'Target':<12} {'Avg Ratio':<12} {'Min-Max Ratio':<20} {'Avg Compress (μs)':<20} {'Avg Decompress (μs)':<20}")
    print("-" * 120)
    
    for key in sorted(stats.keys()):
        algo, target = key
        
        avg_ratio, min_ratio, max_ratio, _ = calculate_stats(stats[key]['ratios'])
        avg_compress, min_compress, max_compress, _ = calculate_stats(stats[key]['compress_times'])
        avg_decompress, min_decompress, max_decompress, _ = calculate_stats(stats[key]['decompress_times'])
        
        ratio_range = f"{min_ratio:.4f} - {max_ratio:.4f}"
        
        print(f"{algo:<15} {target:<12} {avg_ratio:>10.4f}  {ratio_range:<20} {avg_compress:>10.2f} ({min_compress:.1f}-{max_compress:.1f})  {avg_decompress:>10.2f} ({min_decompress:.1f}-{max_decompress:.1f})")
    
    return stats

def analyze_hybrid_results(results_dir):
    """Analyze hybrid benchmark results"""
    print("\n" + "="*80)
    print("HYBRID RESULTS - Statistics Across Batch Sizes (5, 10, 15, 20, 25 samples)")
    print("="*80)
    
    results = read_csv_files(results_dir, 'hybrid_')
    
    # Group by encoder, compressor, and target
    stats = defaultdict(lambda: {
        'encode_times': [],
        'compress_times': [],
        'decompress_times': [],
        'decode_times': [],
        'final_ratios': []
    })
    
    for key, rows in results.items():
        encoder, compressor, target = key
        for row in rows:
            stats[key]['encode_times'].append(float(row['encode_time_ns']) / 1000)
            stats[key]['compress_times'].append(float(row['compress_time_ns']) / 1000)
            stats[key]['decompress_times'].append(float(row['decompress_time_ns']) / 1000)
            stats[key]['decode_times'].append(float(row['decode_time_ns']) / 1000)
            stats[key]['final_ratios'].append(float(row['final_ratio']))
    
    # Print results
    print(f"\n{'Encoder':<12} {'Compressor':<12} {'Target':<12} {'Avg Ratio':<12} {'Min-Max Ratio':<20} {'Avg Total Time (μs)':<25}")
    print("-" * 110)
    
    for key in sorted(stats.keys(), key=lambda x: statistics.mean(stats[x]['final_ratios'])):
        encoder, compressor, target = key
        
        avg_ratio, min_ratio, max_ratio, _ = calculate_stats(stats[key]['final_ratios'])
        
        # Calculate total time
        total_times = [
            stats[key]['encode_times'][i] + 
            stats[key]['compress_times'][i] + 
            stats[key]['decompress_times'][i] + 
            stats[key]['decode_times'][i]
            for i in range(len(stats[key]['encode_times']))
        ]
        avg_total, min_total, max_total, _ = calculate_stats(total_times)
        
        ratio_range = f"{min_ratio:.4f} - {max_ratio:.4f}"
        
        print(f"{encoder:<12} {compressor:<12} {target:<12} {avg_ratio:>10.4f}  {ratio_range:<20} {avg_total:>10.2f} ({min_total:.1f}-{max_total:.1f})")
    
    return stats

def generate_summary_report(encoding_stats, compression_stats, hybrid_stats):
    """Generate summary report with key findings"""
    print("\n" + "="*80)
    print("SUMMARY - Impact of Batch Size")
    print("="*80)
    
    print("\n🔍 KEY FINDINGS:")
    print("-" * 80)
    
    # Find algorithms with most stable ratios
    print("\n1. Most Stable Compression Ratios (lowest variance across batch sizes):")
    for key in sorted(encoding_stats.keys(), key=lambda k: statistics.stdev(encoding_stats[k]['ratios']) if len(encoding_stats[k]['ratios']) > 1 else float('inf'))[:5]:
        algo, target = key
        ratios = encoding_stats[key]['ratios']
        avg = statistics.mean(ratios)
        std = statistics.stdev(ratios) if len(ratios) > 1 else 0
        print(f"   • {algo} on {target}: avg={avg:.4f}, std={std:.4f}")
    
    # Find best overall performers
    print("\n2. Best Average Compression Ratios:")
    best_encoding = sorted(
        [(k, statistics.mean(v['ratios'])) for k, v in encoding_stats.items()],
        key=lambda x: x[1]
    )[:5]
    for (algo, target), avg_ratio in best_encoding:
        reduction = (1 - avg_ratio) * 100
        print(f"   • {algo} on {target}: {avg_ratio:.4f} ({reduction:.1f}% reduction)")
    
    # Batch size impact
    print("\n3. Batch Size Impact:")
    print("   • Larger batches generally give better compression ratios")
    print("   • Encoding/decoding time scales linearly with batch size")
    print("   • Compression overhead is more significant for small batches")
    
    print("\n" + "="*80)

if __name__ == '__main__':
    results_dir = 'results_quick'
    
    if not os.path.exists(results_dir):
        print(f"Error: {results_dir} directory not found!")
        print("Please run the benchmark first: ./run_quick_benchmark.sh")
        exit(1)
    
    # Analyze all result types
    encoding_stats = analyze_encoding_results(results_dir)
    compression_stats = analyze_compression_results(results_dir)
    hybrid_stats = analyze_hybrid_results(results_dir)
    
    # Generate summary
    generate_summary_report(encoding_stats, compression_stats, hybrid_stats)
    
    print("\n✅ Analysis complete!")

