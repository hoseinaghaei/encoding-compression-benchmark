import java.util.Locale;
import java.util.Random;

public class BatchData {
    public static final String DEVICE_PATH = "root.sg1.d1";
    public static final int SAMPLE_COUNT = 20;
    public static final long BASE_TIMESTAMP_MS = 1697040000000L;
    public static final long INTERVAL_MS = 10L; // 10 ms = 10000 microseconds
    public static final float MEAN = 24.0f;
    public static final float VARIANCE = 1.0f;
    public static final float STD_DEV = (float) Math.sqrt(VARIANCE);

    public static class Batch {
        public final String device;
        public final String[] measurements;
        public final long[] timestamps;
        public final float[][] values; // rows x columns

        public Batch(String device, String[] measurements, long[] timestamps, float[][] values) {
            this.device = device;
            this.measurements = measurements;
            this.timestamps = timestamps;
            this.values = values;
        }
    }

    public static Batch generateDeterministic(int seed) {
        return generateDeterministic(seed, SAMPLE_COUNT, BASE_TIMESTAMP_MS, INTERVAL_MS);
    }

    public static Batch generateDeterministic(int seed, int count, long baseTimestampMs, long intervalMs) {
        Random random = new Random(seed);
        long[] timestamps = new long[count];
        float[][] values = new float[count][2];
        for (int i = 0; i < count; i++) {
            timestamps[i] = baseTimestampMs + (i * intervalMs);
            values[i][0] = generateGaussianValue(random, MEAN, STD_DEV);
            values[i][1] = generateGaussianValue(random, MEAN, STD_DEV);
        }
        return new Batch(DEVICE_PATH, new String[]{"temp", "hum"}, timestamps, values);
    }

    public static String toJson(Batch batch) {
        StringBuilder jsonBuilder = new StringBuilder();
        jsonBuilder.append("{\n");
        jsonBuilder.append("  \"device\": \"").append(batch.device).append("\",\n");
        jsonBuilder.append("  \"measurements\": [\"temp\", \"hum\"],\n");

        jsonBuilder.append("  \"timestamps\": [");
        for (int i = 0; i < batch.timestamps.length; i++) {
            jsonBuilder.append(batch.timestamps[i]);
            if (i < batch.timestamps.length - 1) jsonBuilder.append(", ");
        }
        jsonBuilder.append("],\n");

        jsonBuilder.append("  \"values\": [\n");
        for (int i = 0; i < batch.values.length; i++) {
            jsonBuilder.append("    [")
                    .append(formatFloat(batch.values[i][0])).append(", ")
                    .append(formatFloat(batch.values[i][1])).append("]");
            if (i < batch.values.length - 1) jsonBuilder.append(",\n"); else jsonBuilder.append("\n");
        }
        jsonBuilder.append("  ]\n");
        jsonBuilder.append("}\n");
        return jsonBuilder.toString();
    }

    private static float generateGaussianValue(Random random, float mean, float stdDev) {
        return (float) (mean + random.nextGaussian() * stdDev);
    }

    private static String formatFloat(float value) {
        // Format with 5 decimal places
        String formatted = String.format(Locale.US, "%.5f", value);
        
        // Remove trailing zeros, but keep at least 3 decimal places
        int decimalPos = formatted.indexOf('.');
        if (decimalPos != -1) {
            // Find last non-zero digit
            int lastNonZero = formatted.length() - 1;
            while (lastNonZero > decimalPos && formatted.charAt(lastNonZero) == '0') {
                lastNonZero--;
            }
            
            // Keep at least 3 decimal places
            int minLength = decimalPos + 4; // decimal point + 3 digits
            int keepLength = Math.max(lastNonZero + 1, minLength);
            
            // But don't exceed 5 decimal places
            int maxLength = decimalPos + 6; // decimal point + 5 digits
            keepLength = Math.min(keepLength, maxLength);
            
            formatted = formatted.substring(0, keepLength);
        }
        
        return formatted;
    }
}


