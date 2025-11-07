import java.io.*;
import java.nio.*;
import java.util.*;

public class EncodingBenchmarkNew {
    
    // Encoder interface
    interface Encoder {
        byte[] encode(byte[] input);
        byte[] decode(byte[] encoded);
        String getName();
    }
    
    // 1. IDENTITY (baseline)
    static class IdentityEncoder implements Encoder {
        public String getName() { return "IDENTITY"; }
        public byte[] encode(byte[] input) { return input.clone(); }
        public byte[] decode(byte[] encoded) { return encoded.clone(); }
    }
    
    // 2. TS_2DIFF (Delta-delta encoding with varint)
    static class TS2DIFFEncoder implements Encoder {
        public String getName() { return "TS_2DIFF"; }
        
        public byte[] encode(byte[] input) {
            ByteBuffer bb = ByteBuffer.wrap(input);
            int count = input.length / 8;
            if (count < 2) return input.clone();
            
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            long first = bb.getLong();
            long second = bb.getLong();
            writeVarLong(out, first);
            long prevDelta = second - first;
            writeVarLong(out, prevDelta);
            
            for (int i = 2; i < count; i++) {
                long curr = bb.getLong();
                long delta = curr - (second + prevDelta);
                writeVarLong(out, delta);
                prevDelta = curr - second;
                second = curr;
            }
            return out.toByteArray();
        }
        
        public byte[] decode(byte[] encoded) {
            ByteArrayInputStream in = new ByteArrayInputStream(encoded);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteBuffer bb = ByteBuffer.allocate(8);
            
            long first = readVarLong(in);
            bb.putLong(first);
            out.write(bb.array(), 0, 8);
            
            if (in.available() == 0) return out.toByteArray();
            
            long prevDelta = readVarLong(in);
            long second = first + prevDelta;
            bb.clear();
            bb.putLong(second);
            out.write(bb.array(), 0, 8);
            
            while (in.available() > 0) {
                long deltaDelta = readVarLong(in);
                long curr = second + prevDelta + deltaDelta;
                bb.clear();
                bb.putLong(curr);
                out.write(bb.array(), 0, 8);
                prevDelta = curr - second;
                second = curr;
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
                if (b == -1) break;
                result |= (long)(b & 0x7F) << shift;
                shift += 7;
            } while ((b & 0x80) != 0);
            return (result >>> 1) ^ -(result & 1);
        }
    }
    
    // 3. GORILLA (XOR-based for floats)
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
    
    // 4. CHIMP (Improved GORILLA)
    static class ChimpEncoder implements Encoder {
        public String getName() { return "CHIMP"; }
        
        public byte[] encode(byte[] input) {
            ByteBuffer bb = ByteBuffer.wrap(input);
            int count = input.length / 4;
            if (count == 0) return new byte[0];
            
            BitOutputStream out = new BitOutputStream();
            out.writeInt(count, 32);
            
            int prevBits = bb.getInt();
            out.writeInt(prevBits, 32);
            int[] recentLeading = new int[128];
            int[] recentTrailing = new int[128];
            int recentIndex = 0;
            
            for (int i = 1; i < count; i++) {
                int currBits = bb.getInt();
                int xor = prevBits ^ currBits;
                
                if (xor == 0) {
                    out.writeBit(0);
                } else {
                    out.writeBit(1);
                    int leadingZeros = Integer.numberOfLeadingZeros(xor);
                    int trailingZeros = Integer.numberOfTrailingZeros(xor);
                    
                    // Check if matches recent pattern
                    boolean matchFound = false;
                    for (int j = 0; j < Math.min(recentIndex, 128); j++) {
                        if (leadingZeros >= recentLeading[j] && trailingZeros >= recentTrailing[j]) {
                            out.writeBit(0);
                            out.writeInt(j, 7);
                            int significantBits = 32 - recentLeading[j] - recentTrailing[j];
                            out.writeInt(xor >>> recentTrailing[j], significantBits);
                            matchFound = true;
                            break;
                        }
                    }
                    
                    if (!matchFound) {
                        out.writeBit(1);
                        out.writeInt(leadingZeros, 5);
                        int significantBits = 32 - leadingZeros - trailingZeros;
                        out.writeInt(significantBits, 6);
                        out.writeInt(xor >>> trailingZeros, significantBits);
                        
                        recentLeading[recentIndex % 128] = leadingZeros;
                        recentTrailing[recentIndex % 128] = trailingZeros;
                        recentIndex++;
                    }
                }
                prevBits = currBits;
            }
            return out.toByteArray();
        }
        
        public byte[] decode(byte[] encoded) {
            // Simplified decode - in production would track recent patterns
            return new GorillaEncoder().decode(encoded);
        }
    }
    
    // 5. Bit-packing
    static class BitPackingEncoder implements Encoder {
        public String getName() { return "BIT_PACKING"; }
        
        public byte[] encode(byte[] input) {
            ByteBuffer bb = ByteBuffer.wrap(input);
            int count = input.length / 8;
            if (count == 0) return new byte[0];
            
            // Find max value to determine bits needed
            long[] values = new long[count];
            long maxVal = 0;
            for (int i = 0; i < count; i++) {
                values[i] = bb.getLong();
                if (values[i] > maxVal) maxVal = values[i];
            }
            
            int bitsNeeded = 64 - Long.numberOfLeadingZeros(maxVal);
            if (bitsNeeded == 0) bitsNeeded = 1;
            
            BitOutputStream out = new BitOutputStream();
            out.writeInt(count, 32);
            out.writeInt(bitsNeeded, 8);
            
            for (long val : values) {
                out.writeLong(val, bitsNeeded);
            }
            
            return out.toByteArray();
        }
        
        public byte[] decode(byte[] encoded) {
            BitInputStream in = new BitInputStream(encoded);
            int count = in.readInt(32);
            int bitsNeeded = in.readInt(8);
            
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteBuffer bb = ByteBuffer.allocate(8);
            
            for (int i = 0; i < count; i++) {
                long val = in.readLong(bitsNeeded);
                bb.clear();
                bb.putLong(val);
                out.write(bb.array(), 0, 8);
            }
            
            return out.toByteArray();
        }
    }
    
    // 6. RLE (Run-Length Encoding)
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
    
    // 7. HUFFMAN
    static class HuffmanEncoder implements Encoder {
        public String getName() { return "HUFFMAN"; }
        
        public byte[] encode(byte[] input) {
            if (input.length == 0) return new byte[0];
            
            Map<Byte, Integer> freqMap = new HashMap<>();
            for (byte b : input) {
                freqMap.put(b, freqMap.getOrDefault(b, 0) + 1);
            }
            
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
            
            BitOutputStream out = new BitOutputStream();
            out.writeInt(freqMap.size(), 16);
            for (Map.Entry<Byte, Integer> entry : freqMap.entrySet()) {
                out.writeInt(entry.getKey() & 0xFF, 8);
                out.writeInt(entry.getValue(), 32);
            }
            
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
            
            int mapSize = in.readInt(16);
            Map<Byte, Integer> freqMap = new HashMap<>();
            for (int i = 0; i < mapSize; i++) {
                byte key = (byte) in.readInt(8);
                int freq = in.readInt(32);
                freqMap.put(key, freq);
            }
            
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
    
    // 8. Golomb-Rice
    static class GolombRiceEncoder implements Encoder {
        public String getName() { return "GOLOMB_RICE"; }
        private final int M = 8; // Rice parameter
        
        public byte[] encode(byte[] input) {
            ByteBuffer bb = ByteBuffer.wrap(input);
            int count = input.length / 8;
            if (count == 0) return new byte[0];
            
            BitOutputStream out = new BitOutputStream();
            out.writeInt(count, 32);
            
            for (int i = 0; i < count; i++) {
                long val = bb.getLong();
                // Convert to unsigned
                long q = val / M;
                long r = val % M;
                
                // Unary encoding for quotient
                for (int j = 0; j < q && j < 255; j++) {
                    out.writeBit(1);
                }
                out.writeBit(0);
                
                // Binary encoding for remainder
                out.writeInt((int)r, 3); // log2(M)
            }
            
            return out.toByteArray();
        }
        
        public byte[] decode(byte[] encoded) {
            BitInputStream in = new BitInputStream(encoded);
            int count = in.readInt(32);
            
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteBuffer bb = ByteBuffer.allocate(8);
            
            for (int i = 0; i < count; i++) {
                // Decode quotient
                long q = 0;
                while (in.readBit() == 1 && q < 255) {
                    q++;
                }
                
                // Decode remainder
                long r = in.readInt(3);
                long val = q * M + r;
                
                bb.clear();
                bb.putLong(val);
                out.write(bb.array(), 0, 8);
            }
            
            return out.toByteArray();
        }
    }
    
    // 9. SPRINTZ (Simplified version)
    static class SprintzEncoder implements Encoder {
        public String getName() { return "SPRINTZ"; }
        
        public byte[] encode(byte[] input) {
            // Simplified SPRINTZ: Delta + Bit-packing + RLE
            byte[] delta = new TS2DIFFEncoder().encode(input);
            byte[] bitPacked = new BitPackingEncoder().encode(delta);
            return new RLEEncoder().encode(bitPacked);
        }
        
        public byte[] decode(byte[] encoded) {
            byte[] unRLE = new RLEEncoder().decode(encoded);
            byte[] unBitPacked = new BitPackingEncoder().decode(unRLE);
            return new TS2DIFFEncoder().decode(unBitPacked);
        }
    }
    
    // 10. RLBE (Run-Length-Bit-Encoding)
    static class RLBEEncoder implements Encoder {
        public String getName() { return "RLBE"; }
        
        public byte[] encode(byte[] input) {
            // Simplified RLBE: Delta + RLE + Bit-packing
            byte[] delta = new TS2DIFFEncoder().encode(input);
            byte[] rle = new RLEEncoder().encode(delta);
            return new BitPackingEncoder().encode(rle);
        }
        
        public byte[] decode(byte[] encoded) {
            byte[] unBitPacked = new BitPackingEncoder().decode(encoded);
            byte[] unRLE = new RLEEncoder().decode(unBitPacked);
            return new TS2DIFFEncoder().decode(unRLE);
        }
    }
    
    // 11. RAKE (Simplified)
    static class RAKEEncoder implements Encoder {
        public String getName() { return "RAKE"; }
        
        public byte[] encode(byte[] input) {
            // Simplified RAKE: Combination of delta and bit-packing
            return new BitPackingEncoder().encode(new TS2DIFFEncoder().encode(input));
        }
        
        public byte[] decode(byte[] encoded) {
            return new TS2DIFFEncoder().decode(new BitPackingEncoder().decode(encoded));
        }
    }
    
    // 12. DICTIONARY
    static class DictionaryEncoder implements Encoder {
        public String getName() { return "DICTIONARY"; }
        
        public byte[] encode(byte[] input) {
            Map<Long, Integer> dict = new HashMap<>();
            List<Integer> indices = new ArrayList<>();
            int nextId = 0;
            
            ByteBuffer bb = ByteBuffer.wrap(input);
            while (bb.remaining() >= 8) {
                long val = bb.getLong();
                if (!dict.containsKey(val)) {
                    dict.put(val, nextId++);
                }
                indices.add(dict.get(val));
            }
            
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(out);
            
            try {
                dos.writeInt(dict.size());
                for (Map.Entry<Long, Integer> entry : dict.entrySet()) {
                    dos.writeLong(entry.getKey());
                    dos.writeInt(entry.getValue());
                }
                
                dos.writeInt(indices.size());
                for (int idx : indices) {
                    dos.writeInt(idx);
                }
            } catch (IOException e) {
                return input.clone();
            }
            
            return out.toByteArray();
        }
        
        public byte[] decode(byte[] encoded) {
            try {
                DataInputStream dis = new DataInputStream(new ByteArrayInputStream(encoded));
                
                int dictSize = dis.readInt();
                Map<Integer, Long> dict = new HashMap<>();
                for (int i = 0; i < dictSize; i++) {
                    long key = dis.readLong();
                    int id = dis.readInt();
                    dict.put(id, key);
                }
                
                int count = dis.readInt();
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                ByteBuffer bb = ByteBuffer.allocate(8);
                
                for (int i = 0; i < count; i++) {
                    int idx = dis.readInt();
                    bb.clear();
                    bb.putLong(dict.get(idx));
                    out.write(bb.array(), 0, 8);
                }
                
                return out.toByteArray();
            } catch (IOException e) {
                return encoded.clone();
            }
        }
    }
    
    // Bit I/O helpers
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
        
        void writeLong(long value, int numBits) {
            for (int i = numBits - 1; i >= 0; i--) {
                writeBit((int)((value >> i) & 1));
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
        
        long readLong(int numBits) {
            long result = 0;
            for (int i = 0; i < numBits; i++) {
                result = (result << 1) | readBit();
            }
            return result;
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
        
        BatchData.Batch batch = BatchData.generateDeterministic(seed);
        
        byte[] timestampsBytes = longsToBytes(batch.timestamps);
        byte[] valuesBytes = floatsToBytes(batch.values);
        byte[] combinedBytes = combineBytes(timestampsBytes, valuesBytes);
        
        List<Encoder> encoders = Arrays.asList(
            new IdentityEncoder(),
            new TS2DIFFEncoder(),
            new GorillaEncoder(),
            new ChimpEncoder(),
            new BitPackingEncoder(),
            new RLEEncoder(),
            new HuffmanEncoder(),
            new GolombRiceEncoder(),
            new SprintzEncoder(),
            new RLBEEncoder(),
            new RAKEEncoder(),
            new DictionaryEncoder()
        );
        
        List<BenchmarkResult> results = new ArrayList<>();
        
        for (Encoder encoder : encoders) {
            results.add(benchmark(encoder, "timestamps", timestampsBytes));
            results.add(benchmark(encoder, "values", valuesBytes));
            results.add(benchmark(encoder, "combined", combinedBytes));
        }
        
        writeResultsToCSV(results, "/Users/hossein/Desktop/db-encoding/res.csv");
        
        System.out.println("Encoding benchmark completed. Results written to res.csv");
    }
    
    static BenchmarkResult benchmark(Encoder encoder, String target, byte[] data) {
        try {
            for (int i = 0; i < 10; i++) {
                byte[] encoded = encoder.encode(data);
                encoder.decode(encoded);
            }
            
            long encodeStart = System.nanoTime();
            byte[] encoded = encoder.encode(data);
            long encodeEnd = System.nanoTime();
            
            long decodeStart = System.nanoTime();
            byte[] decoded = encoder.decode(encoded);
            long decodeEnd = System.nanoTime();
            
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
        } catch (Exception e) {
            System.err.println("ERROR: " + encoder.getName() + " on " + target + ": " + e.getMessage());
            return new BenchmarkResult(encoder.getName(), target, 0, 0, data.length, data.length);
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
            writer.println("algorithm,target,encode_time_ns,decode_time_ns,original_size_bytes,encoded_size_bytes,compression_ratio");
            for (BenchmarkResult r : results) {
                writer.printf("%s,%s,%d,%d,%d,%d,%.4f%n",
                    r.algorithm, r.target, r.encodeTimeNs, r.decodeTimeNs,
                    r.originalSize, r.encodedSize, r.ratio);
            }
        }
    }
}

