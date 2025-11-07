import java.io.*;
import java.nio.*;
import java.util.*;
import java.util.zip.*;
import org.xerial.snappy.Snappy;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4Compressor;
import net.jpountz.lz4.LZ4FastDecompressor;

public class HybridBenchmarkNew {
    
    // Import encoder and compressor interfaces from the other benchmarks
    // For simplicity, we'll inline the key implementations here
    
    interface Encoder {
        byte[] encode(byte[] input) throws IOException;
        byte[] decode(byte[] encoded) throws IOException;
        String getName();
    }
    
    interface Compressor {
        byte[] compress(byte[] input) throws IOException;
        byte[] decompress(byte[] compressed) throws IOException;
        String getName();
    }
    
    // ==================== ENCODERS ====================
    
    // IDENTITY encoder
    static class IdentityEncoder implements Encoder {
        public String getName() { return "IDENTITY"; }
        public byte[] encode(byte[] input) { return input.clone(); }
        public byte[] decode(byte[] encoded) { return encoded.clone(); }
    }
    
    // TS_2DIFF (Delta-Delta) encoder
    static class TS2DIFFEncoder implements Encoder {
        public String getName() { return "TS_2DIFF"; }
        
        public byte[] encode(byte[] input) throws IOException {
            if (input.length % 8 != 0) throw new IOException("Input must be multiple of 8 bytes");
            
            ByteBuffer bb = ByteBuffer.wrap(input);
            int count = input.length / 8;
            long[] values = new long[count];
            for (int i = 0; i < count; i++) {
                values[i] = bb.getLong();
            }
            
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);
            
            dos.writeLong(values[0]);
            if (count > 1) {
                long delta = values[1] - values[0];
                writeVarint(dos, delta);
                
                for (int i = 2; i < count; i++) {
                    long currentDelta = values[i] - values[i-1];
                    long deltaDelta = currentDelta - delta;
                    writeVarint(dos, deltaDelta);
                    delta = currentDelta;
                }
            }
            
            return baos.toByteArray();
        }
        
        public byte[] decode(byte[] encoded) throws IOException {
            DataInputStream dis = new DataInputStream(new ByteArrayInputStream(encoded));
            List<Long> values = new ArrayList<>();
            
            long first = dis.readLong();
            values.add(first);
            
            if (dis.available() > 0) {
                long delta = readVarint(dis);
                long second = first + delta;
                values.add(second);
                
                while (dis.available() > 0) {
                    long deltaDelta = readVarint(dis);
                    delta += deltaDelta;
                    long next = values.get(values.size() - 1) + delta;
                    values.add(next);
                }
            }
            
            ByteBuffer bb = ByteBuffer.allocate(values.size() * 8);
            for (long v : values) {
                bb.putLong(v);
            }
            return bb.array();
        }
        
        private void writeVarint(DataOutputStream dos, long value) throws IOException {
            long zigzag = (value << 1) ^ (value >> 63);
            while ((zigzag & ~0x7FL) != 0) {
                dos.writeByte((int) ((zigzag & 0x7F) | 0x80));
                zigzag >>>= 7;
            }
            dos.writeByte((int) zigzag);
        }
        
        private long readVarint(DataInputStream dis) throws IOException {
            long result = 0;
            int shift = 0;
            while (true) {
                byte b = dis.readByte();
                result |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) break;
                shift += 7;
            }
            return (result >>> 1) ^ -(result & 1);
        }
    }
    
    // GORILLA encoder
    static class GorillaEncoder implements Encoder {
        public String getName() { return "GORILLA"; }
        
        public byte[] encode(byte[] input) throws IOException {
            if (input.length % 4 != 0) throw new IOException("Input must be multiple of 4 bytes");
            
            ByteBuffer bb = ByteBuffer.wrap(input);
            int count = input.length / 4;
            int[] values = new int[count];
            for (int i = 0; i < count; i++) {
                values[i] = bb.getInt();
            }
            
            BitOutputStream bos = new BitOutputStream();
            bos.writeInt(values[0]);
            
            int prevValue = values[0];
            int prevLeadingZeros = Integer.MAX_VALUE;
            int prevTrailingZeros = 0;
            
            for (int i = 1; i < count; i++) {
                int xor = values[i] ^ prevValue;
                
                if (xor == 0) {
                    bos.writeBit(0);
                } else {
                    bos.writeBit(1);
                    int leadingZeros = Integer.numberOfLeadingZeros(xor);
                    int trailingZeros = Integer.numberOfTrailingZeros(xor);
                    
                    if (leadingZeros >= prevLeadingZeros && trailingZeros >= prevTrailingZeros) {
                        bos.writeBit(0);
                        int significantBits = 32 - prevLeadingZeros - prevTrailingZeros;
                        bos.writeBits(xor >>> prevTrailingZeros, significantBits);
                    } else {
                        bos.writeBit(1);
                        bos.writeBits(leadingZeros, 5);
                        int significantBits = 32 - leadingZeros - trailingZeros;
                        bos.writeBits(significantBits, 6);
                        bos.writeBits(xor >>> trailingZeros, significantBits);
                        
                        prevLeadingZeros = leadingZeros;
                        prevTrailingZeros = trailingZeros;
                    }
                }
                
                prevValue = values[i];
            }
            
            return bos.toByteArray();
        }
        
        public byte[] decode(byte[] encoded) throws IOException {
            BitInputStream bis = new BitInputStream(encoded);
            List<Integer> values = new ArrayList<>();
            
            int first = bis.readInt();
            values.add(first);
            
            int prevValue = first;
            int prevLeadingZeros = 0;
            int prevTrailingZeros = 0;
            
            while (bis.hasMore()) {
                int controlBit = bis.readBit();
                if (controlBit == 0) {
                    values.add(prevValue);
                } else {
                    int blockType = bis.readBit();
                    int xor;
                    
                    if (blockType == 0) {
                        int significantBits = 32 - prevLeadingZeros - prevTrailingZeros;
                        xor = bis.readBits(significantBits) << prevTrailingZeros;
                    } else {
                        int leadingZeros = bis.readBits(5);
                        int significantBits = bis.readBits(6);
                        xor = bis.readBits(significantBits) << (32 - leadingZeros - significantBits);
                        
                        prevLeadingZeros = leadingZeros;
                        prevTrailingZeros = 32 - leadingZeros - significantBits;
                    }
                    
                    int value = prevValue ^ xor;
                    values.add(value);
                    prevValue = value;
                }
            }
            
            ByteBuffer bb = ByteBuffer.allocate(values.size() * 4);
            for (int v : values) {
                bb.putInt(v);
            }
            return bb.array();
        }
    }
    
    // RLE encoder
    static class RLEEncoder implements Encoder {
        public String getName() { return "RLE"; }
        
        public byte[] encode(byte[] input) throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);
            
            int i = 0;
            while (i < input.length) {
                byte current = input[i];
                int count = 1;
                while (i + count < input.length && input[i + count] == current && count < 255) {
                    count++;
                }
                dos.writeByte(current);
                dos.writeByte(count);
                i += count;
            }
            
            return baos.toByteArray();
        }
        
        public byte[] decode(byte[] encoded) throws IOException {
            DataInputStream dis = new DataInputStream(new ByteArrayInputStream(encoded));
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            
            while (dis.available() > 0) {
                byte value = dis.readByte();
                int count = dis.readByte() & 0xFF;
                for (int i = 0; i < count; i++) {
                    baos.write(value);
                }
            }
            
            return baos.toByteArray();
        }
    }
    
    // ==================== COMPRESSORS ====================
    
    static class IdentityCompressor implements Compressor {
        public String getName() { return "IDENTITY"; }
        public byte[] compress(byte[] input) { return input.clone(); }
        public byte[] decompress(byte[] compressed) { return compressed.clone(); }
    }
    
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
    
    static class SnappyCompressor implements Compressor {
        public String getName() { return "SNAPPY"; }
        
        public byte[] compress(byte[] input) throws IOException {
            return Snappy.compress(input);
        }
        
        public byte[] decompress(byte[] compressed) throws IOException {
            return Snappy.uncompress(compressed);
        }
    }
    
    static class LZ4CompressorImpl implements Compressor {
        private final LZ4Factory factory = LZ4Factory.fastestInstance();
        
        public String getName() { return "LZ4"; }
        
        public byte[] compress(byte[] input) throws IOException {
            LZ4Compressor compressor = factory.fastCompressor();
            int maxCompressedLength = compressor.maxCompressedLength(input.length);
            byte[] compressed = new byte[maxCompressedLength + 4];
            
            compressed[0] = (byte) (input.length >>> 24);
            compressed[1] = (byte) (input.length >>> 16);
            compressed[2] = (byte) (input.length >>> 8);
            compressed[3] = (byte) input.length;
            
            int compressedLength = compressor.compress(input, 0, input.length, compressed, 4, maxCompressedLength);
            
            byte[] result = new byte[compressedLength + 4];
            System.arraycopy(compressed, 0, result, 0, compressedLength + 4);
            return result;
        }
        
        public byte[] decompress(byte[] compressed) throws IOException {
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
    
    // ==================== HELPER CLASSES ====================
    
    static class BitOutputStream {
        private ByteArrayOutputStream baos = new ByteArrayOutputStream();
        private int currentByte = 0;
        private int numBitsInCurrentByte = 0;
        
        public void writeBit(int bit) {
            currentByte = (currentByte << 1) | (bit & 1);
            numBitsInCurrentByte++;
            if (numBitsInCurrentByte == 8) {
                baos.write(currentByte);
                currentByte = 0;
                numBitsInCurrentByte = 0;
            }
        }
        
        public void writeBits(int value, int numBits) {
            for (int i = numBits - 1; i >= 0; i--) {
                writeBit((value >> i) & 1);
            }
        }
        
        public void writeInt(int value) {
            writeBits(value, 32);
        }
        
        public byte[] toByteArray() {
            if (numBitsInCurrentByte > 0) {
                currentByte <<= (8 - numBitsInCurrentByte);
                baos.write(currentByte);
            }
            return baos.toByteArray();
        }
    }
    
    static class BitInputStream {
        private byte[] data;
        private int byteIndex = 0;
        private int bitIndex = 0;
        
        public BitInputStream(byte[] data) {
            this.data = data;
        }
        
        public int readBit() throws IOException {
            if (byteIndex >= data.length) throw new IOException("End of stream");
            int bit = (data[byteIndex] >> (7 - bitIndex)) & 1;
            bitIndex++;
            if (bitIndex == 8) {
                bitIndex = 0;
                byteIndex++;
            }
            return bit;
        }
        
        public int readBits(int numBits) throws IOException {
            int result = 0;
            for (int i = 0; i < numBits; i++) {
                result = (result << 1) | readBit();
            }
            return result;
        }
        
        public int readInt() throws IOException {
            return readBits(32);
        }
        
        public boolean hasMore() {
            return byteIndex < data.length || (byteIndex == data.length - 1 && bitIndex < 8);
        }
    }
    
    // ==================== BENCHMARK RESULT ====================
    
    static class HybridResult {
        String encoder;
        String compressor;
        String target;
        long encodeTimeNs;
        long compressTimeNs;
        long decompressTimeNs;
        long decodeTimeNs;
        int originalSize;
        int encodedSize;
        int finalSize;
        double encodingRatio;
        double finalRatio;
        
        HybridResult(String encoder, String compressor, String target,
                    long encodeTimeNs, long compressTimeNs, long decompressTimeNs, long decodeTimeNs,
                    int originalSize, int encodedSize, int finalSize) {
            this.encoder = encoder;
            this.compressor = compressor;
            this.target = target;
            this.encodeTimeNs = encodeTimeNs;
            this.compressTimeNs = compressTimeNs;
            this.decompressTimeNs = decompressTimeNs;
            this.decodeTimeNs = decodeTimeNs;
            this.originalSize = originalSize;
            this.encodedSize = encodedSize;
            this.finalSize = finalSize;
            this.encodingRatio = (double) encodedSize / originalSize;
            this.finalRatio = (double) finalSize / originalSize;
        }
    }
    
    // ==================== MAIN ====================
    
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
        
        // Create encoders (only those that work well)
        List<Encoder> encoders = Arrays.asList(
            new TS2DIFFEncoder(),
            new GorillaEncoder(),
            new RLEEncoder()
        );
        
        // Create compressors
        List<Compressor> compressors = Arrays.asList(
            new GZIPCompressor(),
            new SnappyCompressor(),
            new LZ4CompressorImpl(),
            new ZlibCompressor()
        );
        
        List<HybridResult> results = new ArrayList<>();
        
        // Benchmark each encoder + compressor combination on each target
        for (Encoder encoder : encoders) {
            for (Compressor compressor : compressors) {
                // Timestamps
                results.add(benchmarkHybrid(encoder, compressor, "timestamps", timestampsBytes));
                
                // Values
                results.add(benchmarkHybrid(encoder, compressor, "values", valuesBytes));
                
                // Combined
                results.add(benchmarkHybrid(encoder, compressor, "combined", combinedBytes));
            }
        }
        
        // Write results to CSV
        writeResultsToCSV(results, "/Users/hossein/Desktop/db-encoding/hybrid_res.csv");
        
        System.out.println("Hybrid benchmark completed. Results written to hybrid_res.csv");
    }
    
    static HybridResult benchmarkHybrid(Encoder encoder, Compressor compressor, String target, byte[] data) {
        try {
            // Warmup
            for (int i = 0; i < 10; i++) {
                byte[] encoded = encoder.encode(data);
                byte[] compressed = compressor.compress(encoded);
                byte[] decompressed = compressor.decompress(compressed);
                encoder.decode(decompressed);
            }
            
            // Measure encode time
            long encodeStart = System.nanoTime();
            byte[] encoded = encoder.encode(data);
            long encodeEnd = System.nanoTime();
            
            // Measure compress time
            long compressStart = System.nanoTime();
            byte[] compressed = compressor.compress(encoded);
            long compressEnd = System.nanoTime();
            
            // Measure decompress time
            long decompressStart = System.nanoTime();
            byte[] decompressed = compressor.decompress(compressed);
            long decompressEnd = System.nanoTime();
            
            // Measure decode time
            long decodeStart = System.nanoTime();
            byte[] decoded = encoder.decode(decompressed);
            long decodeEnd = System.nanoTime();
            
            // Verify correctness
            if (!Arrays.equals(data, decoded)) {
                System.err.println("WARNING: " + encoder.getName() + "+" + compressor.getName() + 
                                 " on " + target + " failed verification!");
            }
            
            return new HybridResult(
                encoder.getName(),
                compressor.getName(),
                target,
                encodeEnd - encodeStart,
                compressEnd - compressStart,
                decompressEnd - decompressStart,
                decodeEnd - decodeStart,
                data.length,
                encoded.length,
                compressed.length
            );
        } catch (IOException e) {
            System.err.println("ERROR: " + encoder.getName() + "+" + compressor.getName() + 
                             " on " + target + " threw exception: " + e.getMessage());
            return new HybridResult(encoder.getName(), compressor.getName(), target, 
                                  0, 0, 0, 0, data.length, data.length, data.length);
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
            writer.println("encoder,compressor,target,encode_time_ns,compress_time_ns,decompress_time_ns,decode_time_ns,original_size_bytes,encoded_size_bytes,final_size_bytes,encoding_ratio,final_ratio");
            for (HybridResult r : results) {
                writer.printf("%s,%s,%s,%d,%d,%d,%d,%d,%d,%d,%.4f,%.4f%n",
                    r.encoder, r.compressor, r.target,
                    r.encodeTimeNs, r.compressTimeNs, r.decompressTimeNs, r.decodeTimeNs,
                    r.originalSize, r.encodedSize, r.finalSize,
                    r.encodingRatio, r.finalRatio);
            }
        }
    }
}

