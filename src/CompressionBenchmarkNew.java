import java.io.*;
import java.nio.*;
import java.util.*;
import java.util.zip.*;
import org.xerial.snappy.Snappy;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4Compressor;
import net.jpountz.lz4.LZ4FastDecompressor;

public class CompressionBenchmarkNew {
    
    // Compressor interface
    interface Compressor {
        byte[] compress(byte[] input) throws IOException;
        byte[] decompress(byte[] compressed) throws IOException;
        String getName();
    }
    
    // 1. IDENTITY (baseline)
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
    
    // 3. Snappy compression
    static class SnappyCompressor implements Compressor {
        public String getName() { return "SNAPPY"; }
        
        public byte[] compress(byte[] input) throws IOException {
            return Snappy.compress(input);
        }
        
        public byte[] decompress(byte[] compressed) throws IOException {
            return Snappy.uncompress(compressed);
        }
    }
    
    // 4. LZ4 compression
    static class LZ4Compressor implements Compressor {
        private final LZ4Factory factory = LZ4Factory.fastestInstance();
        
        public String getName() { return "LZ4"; }
        
        public byte[] compress(byte[] input) throws IOException {
            net.jpountz.lz4.LZ4Compressor compressor = factory.fastCompressor();
            int maxCompressedLength = compressor.maxCompressedLength(input.length);
            byte[] compressed = new byte[maxCompressedLength + 4]; // +4 for original length
            
            // Store original length
            compressed[0] = (byte) (input.length >>> 24);
            compressed[1] = (byte) (input.length >>> 16);
            compressed[2] = (byte) (input.length >>> 8);
            compressed[3] = (byte) input.length;
            
            int compressedLength = compressor.compress(input, 0, input.length, compressed, 4, maxCompressedLength);
            
            // Return only the used portion
            byte[] result = new byte[compressedLength + 4];
            System.arraycopy(compressed, 0, result, 0, compressedLength + 4);
            return result;
        }
        
        public byte[] decompress(byte[] compressed) throws IOException {
            // Read original length
            int originalLength = ((compressed[0] & 0xFF) << 24) |
                                ((compressed[1] & 0xFF) << 16) |
                                ((compressed[2] & 0xFF) << 8) |
                                (compressed[3] & 0xFF);
            
            LZ4FastDecompressor decompressor = factory.fastDecompressor();
            byte[] restored = new byte[originalLength];
            decompressor.decompress(compressed, 4, restored, 0, originalLength);
            return restored;
        }
    }
    
    // 5. Zlib (DEFLATE) compression
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
            new SnappyCompressor(),
            new LZ4Compressor(),
            new ZlibCompressor()
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

