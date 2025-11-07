import java.io.*;
import java.nio.*;
import java.util.*;
import java.util.zip.*;

public class CompressionBenchmark {
    
    // Compressor interface
    interface Compressor {
        byte[] compress(byte[] input) throws IOException;
        byte[] decompress(byte[] compressed) throws IOException;
        String getName();
    }
    
    // 1. Identity (no compression)
    static class IdentityCompressor implements Compressor {
        public String getName() { return "IDENTITY"; }
        public byte[] compress(byte[] input) { return input.clone(); }
        public byte[] decompress(byte[] compressed) { return compressed.clone(); }
    }
    
    // 2. GZIP compression
    static class GZIPCompressor implements Compressor {
        public String getName() { return "GZIP"; }
        
        public byte[] compress(byte[] input) throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (GZIPOutputStream gzos = new GZIPOutputStream(baos)) {
                gzos.write(input);
            }
            return baos.toByteArray();
        }
        
        public byte[] decompress(byte[] compressed) throws IOException {
            ByteArrayInputStream bais = new ByteArrayInputStream(compressed);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (GZIPInputStream gzis = new GZIPInputStream(bais)) {
                byte[] buffer = new byte[1024];
                int len;
                while ((len = gzis.read(buffer)) > 0) {
                    baos.write(buffer, 0, len);
                }
            }
            return baos.toByteArray();
        }
    }
    
    // 3. Zlib (DEFLATE) compression
    static class ZlibCompressor implements Compressor {
        public String getName() { return "ZLIB"; }
        
        public byte[] compress(byte[] input) throws IOException {
            Deflater deflater = new Deflater();
            deflater.setInput(input);
            deflater.finish();
            
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            while (!deflater.finished()) {
                int count = deflater.deflate(buffer);
                baos.write(buffer, 0, count);
            }
            deflater.end();
            return baos.toByteArray();
        }
        
        public byte[] decompress(byte[] compressed) throws IOException {
            Inflater inflater = new Inflater();
            inflater.setInput(compressed);
            
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            try {
                while (!inflater.finished()) {
                    int count = inflater.inflate(buffer);
                    baos.write(buffer, 0, count);
                }
            } catch (DataFormatException e) {
                throw new IOException("Decompression error", e);
            } finally {
                inflater.end();
            }
            return baos.toByteArray();
        }
    }
    
    // 4. Simple LZ77-based compression (simplified LZ4-like algorithm)
    static class SimpleLZCompressor implements Compressor {
        public String getName() { return "SIMPLE_LZ"; }
        
        public byte[] compress(byte[] input) throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);
            
            // Write original length
            dos.writeInt(input.length);
            
            int i = 0;
            while (i < input.length) {
                // Look for matches in the previous 4KB window
                int maxMatchLen = 0;
                int maxMatchPos = 0;
                int windowStart = Math.max(0, i - 4096);
                
                for (int j = windowStart; j < i; j++) {
                    int matchLen = 0;
                    while (i + matchLen < input.length && 
                           input[j + matchLen] == input[i + matchLen] && 
                           matchLen < 255) {
                        matchLen++;
                    }
                    if (matchLen > maxMatchLen) {
                        maxMatchLen = matchLen;
                        maxMatchPos = j;
                    }
                }
                
                if (maxMatchLen >= 4) {
                    // Write match: flag(1) + offset(2) + length(1)
                    dos.writeByte(1); // match flag
                    dos.writeShort(i - maxMatchPos);
                    dos.writeByte(maxMatchLen);
                    i += maxMatchLen;
                } else {
                    // Write literal: flag(0) + byte
                    dos.writeByte(0); // literal flag
                    dos.writeByte(input[i]);
                    i++;
                }
            }
            
            dos.close();
            return baos.toByteArray();
        }
        
        public byte[] decompress(byte[] compressed) throws IOException {
            DataInputStream dis = new DataInputStream(new ByteArrayInputStream(compressed));
            
            int originalLength = dis.readInt();
            ByteArrayOutputStream baos = new ByteArrayOutputStream(originalLength);
            
            while (dis.available() > 0) {
                int flag = dis.readByte() & 0xFF;
                if (flag == 1) {
                    // Match
                    int offset = dis.readShort() & 0xFFFF;
                    int length = dis.readByte() & 0xFF;
                    byte[] output = baos.toByteArray();
                    int pos = output.length - offset;
                    for (int i = 0; i < length; i++) {
                        baos.write(output[pos + i]);
                    }
                } else {
                    // Literal
                    baos.write(dis.readByte());
                }
            }
            
            dis.close();
            return baos.toByteArray();
        }
    }
    
    // 5. Run-Length Encoding (simple compression for repetitive data)
    static class RLECompressor implements Compressor {
        public String getName() { return "RLE"; }
        
        public byte[] compress(byte[] input) throws IOException {
            if (input.length == 0) return new byte[0];
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            
            int i = 0;
            while (i < input.length) {
                byte current = input[i];
                int count = 1;
                while (i + count < input.length && input[i + count] == current && count < 255) {
                    count++;
                }
                baos.write(current);
                baos.write(count);
                i += count;
            }
            return baos.toByteArray();
        }
        
        public byte[] decompress(byte[] compressed) throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            for (int i = 0; i < compressed.length; i += 2) {
                byte value = compressed[i];
                int count = compressed[i + 1] & 0xFF;
                for (int j = 0; j < count; j++) {
                    baos.write(value);
                }
            }
            return baos.toByteArray();
        }
    }
    
    // Benchmark result
    static class BenchmarkResult {
        String algorithm;
        String target;
        long compressTimeNs;
        long decompressTimeNs;
        int originalSize;
        int compressedSize;
        double ratio;
        
        BenchmarkResult(String algorithm, String target, long compressTimeNs, long decompressTimeNs, 
                       int originalSize, int compressedSize) {
            this.algorithm = algorithm;
            this.target = target;
            this.compressTimeNs = compressTimeNs;
            this.decompressTimeNs = decompressTimeNs;
            this.originalSize = originalSize;
            this.compressedSize = compressedSize;
            this.ratio = (double) compressedSize / originalSize;
        }
    }
    
    public static void main(String[] args) throws IOException {
        int seed = 42;
        if (args.length > 0) {
            seed = Integer.parseInt(args[0]);
        }
        
        // Generate deterministic batch
        BatchData.Batch batch = BatchData.generateDeterministic(seed);
        
        // Convert to byte arrays
        byte[] timestampsBytes = longsToBytes(batch.timestamps);
        byte[] valuesBytes = floatsToBytes(batch.values);
        byte[] combinedBytes = combineBytes(timestampsBytes, valuesBytes);
        
        // Create compressors
        List<Compressor> compressors = Arrays.asList(
            new IdentityCompressor(),
            new GZIPCompressor(),
            new ZlibCompressor(),
            new SimpleLZCompressor(),
            new RLECompressor()
        );
        
        List<BenchmarkResult> results = new ArrayList<>();
        
        // Benchmark each compressor on each target
        for (Compressor compressor : compressors) {
            // Timestamps
            results.add(benchmark(compressor, "timestamps", timestampsBytes));
            
            // Values
            results.add(benchmark(compressor, "values", valuesBytes));
            
            // Combined
            results.add(benchmark(compressor, "combined", combinedBytes));
        }
        
        // Write results to CSV
        writeResultsToCSV(results, "/Users/hossein/Desktop/db-encoding/compression_res.csv");
        
        System.out.println("Compression benchmark completed. Results written to compression_res.csv");
    }
    
    static BenchmarkResult benchmark(Compressor compressor, String target, byte[] data) {
        try {
            // Warmup
            for (int i = 0; i < 10; i++) {
                byte[] compressed = compressor.compress(data);
                compressor.decompress(compressed);
            }
            
            // Measure compress time
            long compressStart = System.nanoTime();
            byte[] compressed = compressor.compress(data);
            long compressEnd = System.nanoTime();
            
            // Measure decompress time
            long decompressStart = System.nanoTime();
            byte[] decompressed = compressor.decompress(compressed);
            long decompressEnd = System.nanoTime();
            
            // Verify correctness
            if (!Arrays.equals(data, decompressed)) {
                System.err.println("WARNING: " + compressor.getName() + " on " + target + " failed verification!");
            }
            
            return new BenchmarkResult(
                compressor.getName(),
                target,
                compressEnd - compressStart,
                decompressEnd - decompressStart,
                data.length,
                compressed.length
            );
        } catch (IOException e) {
            System.err.println("ERROR: " + compressor.getName() + " on " + target + " threw exception: " + e.getMessage());
            return new BenchmarkResult(compressor.getName(), target, 0, 0, data.length, data.length);
        }
    }
    
    static byte[] longsToBytes(long[] longs) {
        ByteBuffer bb = ByteBuffer.allocate(longs.length * 8);
        for (long l : longs) {
            bb.putLong(l);
        }
        return bb.array();
    }
    
    static byte[] floatsToBytes(float[][] values) {
        ByteBuffer bb = ByteBuffer.allocate(values.length * values[0].length * 4);
        for (float[] row : values) {
            for (float f : row) {
                bb.putFloat(f);
            }
        }
        return bb.array();
    }
    
    static byte[] combineBytes(byte[] a, byte[] b) {
        byte[] combined = new byte[a.length + b.length];
        System.arraycopy(a, 0, combined, 0, a.length);
        System.arraycopy(b, 0, combined, a.length, b.length);
        return combined;
    }
    
    static void writeResultsToCSV(List<BenchmarkResult> results, String filename) throws IOException {
        try (PrintWriter writer = new PrintWriter(new FileWriter(filename))) {
            writer.println("algorithm,target,compress_time_ns,decompress_time_ns,original_size_bytes,compressed_size_bytes,compression_ratio");
            for (BenchmarkResult r : results) {
                writer.printf("%s,%s,%d,%d,%d,%d,%.4f%n",
                    r.algorithm, r.target, r.compressTimeNs, r.decompressTimeNs,
                    r.originalSize, r.compressedSize, r.ratio);
            }
        }
    }
}

