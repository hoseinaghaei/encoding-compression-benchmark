import java.io.*;
import java.nio.*;
import java.util.*;
import java.util.zip.*;

public class HybridBenchmark {
    
    // Encoder interface
    interface Encoder {
        byte[] encode(byte[] input);
        byte[] decode(byte[] encoded);
        String getName();
    }
    
    // Compressor interface
    interface Compressor {
        byte[] compress(byte[] input) throws IOException;
        byte[] decompress(byte[] compressed) throws IOException;
        String getName();
    }
    
    // ==================== ENCODERS ====================
    
    // Delta encoding for timestamps
    static class DeltaEncoder implements Encoder {
        public String getName() { return "DELTA_VARINT"; }
        
        public byte[] encode(byte[] input) {
            ByteBuffer bb = ByteBuffer.wrap(input);
            int count = input.length / 8;
            if (count == 0) return new byte[0];
            
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            long prev = bb.getLong();
            writeVarLong(out, prev);
            
            for (int i = 1; i < count; i++) {
                long curr = bb.getLong();
                long delta = curr - prev;
                writeVarLong(out, delta);
                prev = curr;
            }
            return out.toByteArray();
        }
        
        public byte[] decode(byte[] encoded) {
            ByteArrayInputStream in = new ByteArrayInputStream(encoded);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteBuffer bb = ByteBuffer.allocate(8);
            
            long prev = readVarLong(in);
            bb.putLong(prev);
            out.write(bb.array(), 0, 8);
            
            while (in.available() > 0) {
                long delta = readVarLong(in);
                prev = prev + delta;
                bb.clear();
                bb.putLong(prev);
                out.write(bb.array(), 0, 8);
            }
            return out.toByteArray();
        }
        
        private void writeVarLong(ByteArrayOutputStream out, long value) {
            long v = (value << 1) ^ (value >> 63);
            while ((v & ~0x7FL) != 0) {
                out.write((int)((v & 0x7F) | 0x80));
                v >>>= 7;
            }
            out.write((int)(v & 0x7F));
        }
        
        private long readVarLong(ByteArrayInputStream in) {
            long result = 0;
            int shift = 0;
            int b;
            do {
                b = in.read();
                result |= (long)(b & 0x7F) << shift;
                shift += 7;
            } while ((b & 0x80) != 0);
            return (result >>> 1) ^ -(result & 1);
        }
    }
    
    // Gorilla encoding for floats
    static class GorillaEncoder implements Encoder {
        public String getName() { return "GORILLA"; }
        
        public byte[] encode(byte[] input) {
            ByteBuffer bb = ByteBuffer.wrap(input);
            int count = input.length / 4;
            if (count == 0) return new byte[0];
            
            BitOutputStream out = new BitOutputStream();
            out.writeInt(count, 32);
            
            int prevBits = bb.getInt();
            out.writeInt(prevBits, 32);
            int prevLeadingZeros = 32;
            int prevTrailingZeros = 32;
            
            for (int i = 1; i < count; i++) {
                int currBits = bb.getInt();
                int xor = prevBits ^ currBits;
                
                if (xor == 0) {
                    out.writeBit(0);
                } else {
                    out.writeBit(1);
                    int leadingZeros = Integer.numberOfLeadingZeros(xor);
                    int trailingZeros = Integer.numberOfTrailingZeros(xor);
                    int significantBits = 32 - leadingZeros - trailingZeros;
                    
                    if (leadingZeros >= prevLeadingZeros && trailingZeros >= prevTrailingZeros) {
                        out.writeBit(0);
                        int useBits = 32 - prevLeadingZeros - prevTrailingZeros;
                        out.writeInt(xor >>> prevTrailingZeros, useBits);
                    } else {
                        out.writeBit(1);
                        out.writeInt(leadingZeros, 5);
                        out.writeInt(significantBits, 6);
                        out.writeInt(xor >>> trailingZeros, significantBits);
                        prevLeadingZeros = leadingZeros;
                        prevTrailingZeros = trailingZeros;
                    }
                }
                prevBits = currBits;
            }
            return out.toByteArray();
        }
        
        public byte[] decode(byte[] encoded) {
            BitInputStream in = new BitInputStream(encoded);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteBuffer bb = ByteBuffer.allocate(4);
            
            int count = in.readInt(32);
            int prevBits = in.readInt(32);
            bb.putInt(prevBits);
            out.write(bb.array(), 0, 4);
            
            int prevLeadingZeros = 32;
            int prevTrailingZeros = 32;
            
            for (int i = 1; i < count; i++) {
                bb.clear();
                int controlBit = in.readBit();
                
                if (controlBit == 0) {
                    bb.putInt(prevBits);
                } else {
                    int xor;
                    int typeBit = in.readBit();
                    
                    if (typeBit == 0) {
                        int significantBits = 32 - prevLeadingZeros - prevTrailingZeros;
                        int significantValue = in.readInt(significantBits);
                        xor = significantValue << prevTrailingZeros;
                    } else {
                        int leadingZeros = in.readInt(5);
                        int significantBits = in.readInt(6);
                        int significantValue = in.readInt(significantBits);
                        int trailingZeros = 32 - leadingZeros - significantBits;
                        xor = significantValue << trailingZeros;
                        prevLeadingZeros = leadingZeros;
                        prevTrailingZeros = trailingZeros;
                    }
                    prevBits = prevBits ^ xor;
                    bb.putInt(prevBits);
                }
                out.write(bb.array(), 0, 4);
            }
            return out.toByteArray();
        }
    }
    
    // Bit-level I/O helpers
    static class BitOutputStream {
        private ByteArrayOutputStream out = new ByteArrayOutputStream();
        private int currentByte = 0;
        private int numBitsInCurrentByte = 0;
        
        void writeBit(int bit) {
            currentByte = (currentByte << 1) | (bit & 1);
            numBitsInCurrentByte++;
            if (numBitsInCurrentByte == 8) {
                out.write(currentByte);
                currentByte = 0;
                numBitsInCurrentByte = 0;
            }
        }
        
        void writeInt(int value, int numBits) {
            for (int i = numBits - 1; i >= 0; i--) {
                writeBit((value >> i) & 1);
            }
        }
        
        byte[] toByteArray() {
            if (numBitsInCurrentByte > 0) {
                currentByte <<= (8 - numBitsInCurrentByte);
                out.write(currentByte);
            }
            return out.toByteArray();
        }
    }
    
    static class BitInputStream {
        private byte[] data;
        private int byteIndex = 0;
        private int bitIndex = 0;
        
        BitInputStream(byte[] data) {
            this.data = data;
        }
        
        int readBit() {
            if (byteIndex >= data.length) return -1;
            int bit = (data[byteIndex] >> (7 - bitIndex)) & 1;
            bitIndex++;
            if (bitIndex == 8) {
                bitIndex = 0;
                byteIndex++;
            }
            return bit;
        }
        
        int readInt(int numBits) {
            int result = 0;
            for (int i = 0; i < numBits; i++) {
                result = (result << 1) | readBit();
            }
            return result;
        }
    }
    
    // ==================== COMPRESSORS ====================
    
    // GZIP compression
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
    
    // Zlib (DEFLATE) compression
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
    static class HybridResult {
        String encoder;
        String compressor;
        String encodingTarget;
        String compressionTarget;
        long totalEncodeTime;
        long totalDecodeTime;
        int originalSize;
        int encodedSize;
        int compressedSize;
        double encodingRatio;
        double compressionRatio;
        double totalRatio;
        
        HybridResult(String encoder, String compressor, String encodingTarget, String compressionTarget,
                    long totalEncodeTime, long totalDecodeTime,
                    int originalSize, int encodedSize, int compressedSize) {
            this.encoder = encoder;
            this.compressor = compressor;
            this.encodingTarget = encodingTarget;
            this.compressionTarget = compressionTarget;
            this.totalEncodeTime = totalEncodeTime;
            this.totalDecodeTime = totalDecodeTime;
            this.originalSize = originalSize;
            this.encodedSize = encodedSize;
            this.compressedSize = compressedSize;
            this.encodingRatio = (double) encodedSize / originalSize;
            this.compressionRatio = (double) compressedSize / encodedSize;
            this.totalRatio = (double) compressedSize / originalSize;
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
        
        // Create encoders and compressors
        List<Encoder> encoders = Arrays.asList(
            new DeltaEncoder(),
            new GorillaEncoder()
        );
        
        List<Compressor> compressors = Arrays.asList(
            new GZIPCompressor(),
            new ZlibCompressor()
        );
        
        List<HybridResult> results = new ArrayList<>();
        
        // Test all combinations
        String[] targets = {"timestamps", "values", "combined"};
        Map<String, byte[]> targetData = new HashMap<>();
        targetData.put("timestamps", timestampsBytes);
        targetData.put("values", valuesBytes);
        targetData.put("combined", combinedBytes);
        
        for (Encoder encoder : encoders) {
            for (Compressor compressor : compressors) {
                for (String encTarget : targets) {
                    for (String compTarget : targets) {
                        HybridResult result = benchmarkHybrid(
                            encoder, compressor, 
                            encTarget, compTarget,
                            targetData
                        );
                        results.add(result);
                    }
                }
            }
        }
        
        // Write results to CSV
        writeResultsToCSV(results, "/Users/hossein/Desktop/db-encoding/hybrid_res.csv");
        
        System.out.println("Hybrid benchmark completed. Results written to hybrid_res.csv");
        System.out.println("Total combinations tested: " + results.size());
    }
    
    static HybridResult benchmarkHybrid(Encoder encoder, Compressor compressor,
                                        String encTarget, String compTarget,
                                        Map<String, byte[]> targetData) {
        try {
            byte[] encData = targetData.get(encTarget);
            byte[] compData = targetData.get(compTarget);
            int originalSize = encData.length;
            
            // Warmup
            for (int i = 0; i < 5; i++) {
                byte[] encoded = encoder.encode(encData);
                byte[] compressed = compressor.compress(compData);
                compressor.decompress(compressed);
                encoder.decode(encoded);
            }
            
            // Measure encode + compress time
            long startTime = System.nanoTime();
            byte[] encoded = encoder.encode(encData);
            byte[] compressed = compressor.compress(compData);
            long encodeCompressTime = System.nanoTime() - startTime;
            
            // Measure decompress + decode time
            startTime = System.nanoTime();
            byte[] decompressed = compressor.decompress(compressed);
            byte[] decoded = encoder.decode(encoded);
            long decompressDecodeTime = System.nanoTime() - startTime;
            
            // Verify correctness
            if (!Arrays.equals(encData, decoded)) {
                System.err.println("WARNING: " + encoder.getName() + " failed verification!");
            }
            if (!Arrays.equals(compData, decompressed)) {
                System.err.println("WARNING: " + compressor.getName() + " failed verification!");
            }
            
            return new HybridResult(
                encoder.getName(),
                compressor.getName(),
                encTarget,
                compTarget,
                encodeCompressTime,
                decompressDecodeTime,
                originalSize,
                encoded.length,
                compressed.length
            );
        } catch (Exception e) {
            System.err.println("ERROR: " + encoder.getName() + "+" + compressor.getName() + 
                             " on " + encTarget + "/" + compTarget + ": " + e.getMessage());
            return new HybridResult(encoder.getName(), compressor.getName(), 
                                  encTarget, compTarget, 0, 0, 0, 0, 0);
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
    
    static void writeResultsToCSV(List<HybridResult> results, String filename) throws IOException {
        try (PrintWriter writer = new PrintWriter(new FileWriter(filename))) {
            writer.println("encoder,compressor,encoding_target,compression_target," +
                         "total_encode_compress_time_ns,total_decompress_decode_time_ns," +
                         "original_size_bytes,encoded_size_bytes,compressed_size_bytes," +
                         "encoding_ratio,compression_ratio,total_ratio");
            for (HybridResult r : results) {
                writer.printf("%s,%s,%s,%s,%d,%d,%d,%d,%d,%.4f,%.4f,%.4f%n",
                    r.encoder, r.compressor, r.encodingTarget, r.compressionTarget,
                    r.totalEncodeTime, r.totalDecodeTime,
                    r.originalSize, r.encodedSize, r.compressedSize,
                    r.encodingRatio, r.compressionRatio, r.totalRatio);
            }
        }
    }
}

