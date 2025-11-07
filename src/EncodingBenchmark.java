import java.io.*;
import java.nio.*;
import java.util.*;

public class EncodingBenchmark {
    
    // Encoder interface
    interface Encoder {
        byte[] encode(byte[] input);
        byte[] decode(byte[] encoded);
        String getName();
    }
    
    // 1. Identity (no encoding)
    static class IdentityEncoder implements Encoder {
        public String getName() { return "IDENTITY"; }
        public byte[] encode(byte[] input) { return input.clone(); }
        public byte[] decode(byte[] encoded) { return encoded.clone(); }
    }
    
    // 2. Delta encoding for timestamps (TS_2DIFF variant with varint)
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
            long v = (value << 1) ^ (value >> 63); // zigzag encoding
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
            return (result >>> 1) ^ -(result & 1); // zigzag decode
        }
    }
    
    // 3. Gorilla encoding for floats (simplified version)
    static class GorillaEncoder implements Encoder {
        public String getName() { return "GORILLA"; }
        
        public byte[] encode(byte[] input) {
            ByteBuffer bb = ByteBuffer.wrap(input);
            int count = input.length / 4;
            if (count == 0) return new byte[0];
            
            BitOutputStream out = new BitOutputStream();
            
            // Write count for decoding
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
    
    // 4. Simple RLE
    static class RLEEncoder implements Encoder {
        public String getName() { return "RLE"; }
        
        public byte[] encode(byte[] input) {
            if (input.length == 0) return new byte[0];
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            
            int i = 0;
            while (i < input.length) {
                byte current = input[i];
                int count = 1;
                while (i + count < input.length && input[i + count] == current && count < 255) {
                    count++;
                }
                out.write(current);
                out.write(count);
                i += count;
            }
            return out.toByteArray();
        }
        
        public byte[] decode(byte[] encoded) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (int i = 0; i < encoded.length; i += 2) {
                byte value = encoded[i];
                int count = encoded[i + 1] & 0xFF;
                for (int j = 0; j < count; j++) {
                    out.write(value);
                }
            }
            return out.toByteArray();
        }
    }
    
    // 5. Simple Huffman encoding
    static class HuffmanEncoder implements Encoder {
        public String getName() { return "HUFFMAN"; }
        
        public byte[] encode(byte[] input) {
            if (input.length == 0) return new byte[0];
            
            // Build frequency map
            Map<Byte, Integer> freqMap = new HashMap<>();
            for (byte b : input) {
                freqMap.put(b, freqMap.getOrDefault(b, 0) + 1);
            }
            
            // Build Huffman tree
            PriorityQueue<HuffmanNode> pq = new PriorityQueue<>();
            for (Map.Entry<Byte, Integer> entry : freqMap.entrySet()) {
                pq.offer(new HuffmanNode(entry.getKey(), entry.getValue()));
            }
            
            while (pq.size() > 1) {
                HuffmanNode left = pq.poll();
                HuffmanNode right = pq.poll();
                pq.offer(new HuffmanNode(left, right));
            }
            
            HuffmanNode root = pq.poll();
            Map<Byte, String> codeMap = new HashMap<>();
            buildCodeMap(root, "", codeMap);
            
            // Encode data
            BitOutputStream out = new BitOutputStream();
            
            // Write tree structure (simplified: write frequency map)
            out.writeInt(freqMap.size(), 16);
            for (Map.Entry<Byte, Integer> entry : freqMap.entrySet()) {
                out.writeInt(entry.getKey() & 0xFF, 8);
                out.writeInt(entry.getValue(), 32);
            }
            
            // Write encoded data
            out.writeInt(input.length, 32);
            for (byte b : input) {
                String code = codeMap.get(b);
                for (char c : code.toCharArray()) {
                    out.writeBit(c == '1' ? 1 : 0);
                }
            }
            
            return out.toByteArray();
        }
        
        public byte[] decode(byte[] encoded) {
            BitInputStream in = new BitInputStream(encoded);
            
            // Read frequency map
            int mapSize = in.readInt(16);
            Map<Byte, Integer> freqMap = new HashMap<>();
            for (int i = 0; i < mapSize; i++) {
                byte key = (byte) in.readInt(8);
                int freq = in.readInt(32);
                freqMap.put(key, freq);
            }
            
            // Rebuild Huffman tree
            PriorityQueue<HuffmanNode> pq = new PriorityQueue<>();
            for (Map.Entry<Byte, Integer> entry : freqMap.entrySet()) {
                pq.offer(new HuffmanNode(entry.getKey(), entry.getValue()));
            }
            
            while (pq.size() > 1) {
                HuffmanNode left = pq.poll();
                HuffmanNode right = pq.poll();
                pq.offer(new HuffmanNode(left, right));
            }
            
            HuffmanNode root = pq.poll();
            
            // Decode data
            int dataLength = in.readInt(32);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            HuffmanNode current = root;
            
            for (int i = 0; i < dataLength; i++) {
                while (!current.isLeaf()) {
                    int bit = in.readBit();
                    current = (bit == 0) ? current.left : current.right;
                }
                out.write(current.value);
                current = root;
            }
            
            return out.toByteArray();
        }
        
        private void buildCodeMap(HuffmanNode node, String code, Map<Byte, String> codeMap) {
            if (node.isLeaf()) {
                codeMap.put(node.value, code.isEmpty() ? "0" : code);
            } else {
                buildCodeMap(node.left, code + "0", codeMap);
                buildCodeMap(node.right, code + "1", codeMap);
            }
        }
    }
    
    // Huffman tree node
    static class HuffmanNode implements Comparable<HuffmanNode> {
        byte value;
        int frequency;
        HuffmanNode left, right;
        
        HuffmanNode(byte value, int frequency) {
            this.value = value;
            this.frequency = frequency;
        }
        
        HuffmanNode(HuffmanNode left, HuffmanNode right) {
            this.frequency = left.frequency + right.frequency;
            this.left = left;
            this.right = right;
        }
        
        boolean isLeaf() {
            return left == null && right == null;
        }
        
        public int compareTo(HuffmanNode other) {
            return Integer.compare(this.frequency, other.frequency);
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
        
        boolean hasMore() {
            return byteIndex < data.length;
        }
    }
    
    // Benchmark result
    static class BenchmarkResult {
        String algorithm;
        String target;
        long encodeTimeNs;
        long decodeTimeNs;
        int originalSize;
        int encodedSize;
        double ratio;
        
        BenchmarkResult(String algorithm, String target, long encodeTimeNs, long decodeTimeNs, 
                       int originalSize, int encodedSize) {
            this.algorithm = algorithm;
            this.target = target;
            this.encodeTimeNs = encodeTimeNs;
            this.decodeTimeNs = decodeTimeNs;
            this.originalSize = originalSize;
            this.encodedSize = encodedSize;
            this.ratio = (double) encodedSize / originalSize;
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
        
        // Create encoders
        List<Encoder> encoders = Arrays.asList(
            new IdentityEncoder(),
            new DeltaEncoder(),
            new GorillaEncoder(),
            new RLEEncoder(),
            new HuffmanEncoder()
        );
        
        List<BenchmarkResult> results = new ArrayList<>();
        
        // Benchmark each encoder on each target
        for (Encoder encoder : encoders) {
            // Timestamps
            results.add(benchmark(encoder, "timestamps", timestampsBytes));
            
            // Values
            results.add(benchmark(encoder, "values", valuesBytes));
            
            // Combined
            results.add(benchmark(encoder, "combined", combinedBytes));
        }
        
        // Write results to CSV
        writeResultsToCSV(results, "/Users/hossein/Desktop/db-encoding/res.csv");
        
        System.out.println("Benchmark completed. Results written to res.csv");
    }
    
    static BenchmarkResult benchmark(Encoder encoder, String target, byte[] data) {
        // Warmup
        for (int i = 0; i < 10; i++) {
            byte[] encoded = encoder.encode(data);
            encoder.decode(encoded);
        }
        
        // Measure encode time
        long encodeStart = System.nanoTime();
        byte[] encoded = encoder.encode(data);
        long encodeEnd = System.nanoTime();
        
        // Measure decode time
        long decodeStart = System.nanoTime();
        byte[] decoded = encoder.decode(encoded);
        long decodeEnd = System.nanoTime();
        
        // Verify correctness
        if (!Arrays.equals(data, decoded)) {
            System.err.println("WARNING: " + encoder.getName() + " on " + target + " failed verification!");
        }
        
        return new BenchmarkResult(
            encoder.getName(),
            target,
            encodeEnd - encodeStart,
            decodeEnd - decodeStart,
            data.length,
            encoded.length
        );
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
            writer.println("algorithm,target,encode_time_ns,decode_time_ns,original_size_bytes,encoded_size_bytes,compression_ratio");
            for (BenchmarkResult r : results) {
                writer.printf("%s,%s,%d,%d,%d,%d,%.4f%n",
                    r.algorithm, r.target, r.encodeTimeNs, r.decodeTimeNs,
                    r.originalSize, r.encodedSize, r.ratio);
            }
        }
    }
}

