import java.io.*;
import java.nio.*;
import java.util.*;
import java.util.zip.*;
import org.xerial.snappy.Snappy;

public class DirectCompressionTest {
    
    // Inline Batch class to avoid dependency issues
    static class Batch {
        long[] timestamps;
        float[][] values;
        
        Batch(long[] timestamps, float[][] values) {
            this.timestamps = timestamps;
            this.values = values;
        }
    }
    
    static Batch generateBatch(int seed) {
        Random rand = new Random(seed);
        int sampleCount = 20;
        
        long[] timestamps = new long[sampleCount];
        float[][] values = new float[sampleCount][2];
        
        long baseTime = 1697040000000L;
        for (int i = 0; i < sampleCount; i++) {
            timestamps[i] = baseTime + (i * 10000);
            values[i][0] = 24.0f + (float) (rand.nextGaussian() * 1.0);
            values[i][1] = 0.52f + (float) (rand.nextGaussian() * 0.1);
        }
        
        return new Batch(timestamps, values);
    }
    
    public static void main(String[] args) throws IOException {
        int seed = 42;
        if (args.length > 0) {
            seed = Integer.parseInt(args[0]);
        }
        
        System.out.println("=== Direct Compression Test (No Encoding) ===");
        System.out.println("Seed: " + seed);
        System.out.println();
        
        // Generate deterministic batch
        Batch batch = generateBatch(seed);
        
        // Convert to byte arrays
        byte[] timestampsBytes = longsToBytes(batch.timestamps);
        byte[] valuesBytes = floatsToBytes(batch.values);
        byte[] combinedBytes = combineBytes(timestampsBytes, valuesBytes);
        
        System.out.println("Original Sizes:");
        System.out.println("  Timestamps: " + timestampsBytes.length + " bytes");
        System.out.println("  Values:     " + valuesBytes.length + " bytes");
        System.out.println("  Combined:   " + combinedBytes.length + " bytes");
        System.out.println();
        
        // Test each compressor
        testCompressor("GZIP", combinedBytes, timestampsBytes, valuesBytes);
        testCompressor("SNAPPY", combinedBytes, timestampsBytes, valuesBytes);
        testCompressor("ZLIB", combinedBytes, timestampsBytes, valuesBytes);
        
        System.out.println();
        System.out.println("=== Summary ===");
        System.out.println();
        System.out.println("Direct compression (no encoding) results:");
        System.out.println("  GZIP:   Best compression ratio, moderate speed");
        System.out.println("  SNAPPY: Fast compression, good ratio");
        System.out.println("  ZLIB:   Similar to GZIP, slightly different");
    }
    
    static void testCompressor(String name, byte[] combined, byte[] timestamps, byte[] values) throws IOException {
        System.out.println("─────────────────────────────────────────────────────");
        System.out.println("Testing: " + name);
        System.out.println("─────────────────────────────────────────────────────");
        
        // Test on combined data
        long startCompress = System.nanoTime();
        byte[] compressedCombined = compress(name, combined);
        long endCompress = System.nanoTime();
        
        long startDecompress = System.nanoTime();
        byte[] decompressedCombined = decompress(name, compressedCombined);
        long endDecompress = System.nanoTime();
        
        // Verify
        boolean valid = Arrays.equals(combined, decompressedCombined);
        
        double ratio = (double) compressedCombined.length / combined.length;
        double reduction = (1 - ratio) * 100;
        
        System.out.println("Combined Message:");
        System.out.println("  Original:     " + combined.length + " bytes");
        System.out.println("  Compressed:   " + compressedCombined.length + " bytes");
        System.out.println("  Ratio:        " + String.format("%.4f", ratio));
        System.out.println("  Reduction:    " + String.format("%.1f%%", reduction));
        System.out.println("  Compress:     " + String.format("%.2f μs", (endCompress - startCompress) / 1000.0));
        System.out.println("  Decompress:   " + String.format("%.2f μs", (endDecompress - startDecompress) / 1000.0));
        System.out.println("  Valid:        " + (valid ? "✓" : "✗"));
        
        // Test on timestamps only
        byte[] compressedTimestamps = compress(name, timestamps);
        double tsRatio = (double) compressedTimestamps.length / timestamps.length;
        double tsReduction = (1 - tsRatio) * 100;
        
        System.out.println();
        System.out.println("Timestamps Only:");
        System.out.println("  Original:     " + timestamps.length + " bytes");
        System.out.println("  Compressed:   " + compressedTimestamps.length + " bytes");
        System.out.println("  Reduction:    " + String.format("%.1f%%", tsReduction));
        
        // Test on values only
        byte[] compressedValues = compress(name, values);
        double valRatio = (double) compressedValues.length / values.length;
        double valReduction = (1 - valRatio) * 100;
        
        System.out.println();
        System.out.println("Values Only:");
        System.out.println("  Original:     " + values.length + " bytes");
        System.out.println("  Compressed:   " + compressedValues.length + " bytes");
        System.out.println("  Reduction:    " + String.format("%.1f%%", valReduction));
        
        System.out.println();
    }
    
    static byte[] compress(String name, byte[] input) throws IOException {
        switch (name) {
            case "GZIP":
                return compressGZIP(input);
            case "SNAPPY":
                return Snappy.compress(input);
            case "ZLIB":
                return compressZLIB(input);
            default:
                throw new IllegalArgumentException("Unknown compressor: " + name);
        }
    }
    
    static byte[] decompress(String name, byte[] input) throws IOException {
        switch (name) {
            case "GZIP":
                return decompressGZIP(input);
            case "SNAPPY":
                return Snappy.uncompress(input);
            case "ZLIB":
                return decompressZLIB(input);
            default:
                throw new IllegalArgumentException("Unknown compressor: " + name);
        }
    }
    
    static byte[] compressGZIP(byte[] input) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzos = new GZIPOutputStream(baos)) {
            gzos.write(input);
        }
        return baos.toByteArray();
    }
    
    static byte[] decompressGZIP(byte[] compressed) throws IOException {
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
    
    static byte[] compressZLIB(byte[] input) throws IOException {
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
    
    static byte[] decompressZLIB(byte[] compressed) throws IOException {
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
}

