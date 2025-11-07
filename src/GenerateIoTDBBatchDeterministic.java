import java.util.Locale;
import java.util.Random;

public class GenerateIoTDBBatchDeterministic {
    private static final String DEVICE_PATH = "root.sg1.d1";
    private static final int SAMPLE_COUNT = 2;
    private static final long BASE_TIMESTAMP_MS = 1697040000000L;
    private static final long INTERVAL_MS = 10L; // 10 ms = 10000 microseconds

    private static final float MEAN = 24.0f;
    private static final float VARIANCE = 1.0f;
    private static final float STD_DEV = (float) Math.sqrt(VARIANCE);

    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Usage: java GenerateIoTDBBatchDeterministic <integer-seed>");
            System.exit(1);
        }

        final int seed;
        try {
            seed = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            System.err.println("Error: seed must be an integer.");
            System.exit(1);
            return; // unreachable, but keeps compiler happy
        }

        Random random = new Random(seed);

        StringBuilder jsonBuilder = new StringBuilder();
        jsonBuilder.append("{\n");
        jsonBuilder.append("  \"device\": \"").append(DEVICE_PATH).append("\",\n");
        jsonBuilder.append("  \"measurements\": [\"temp\", \"hum\"],\n");

        // timestamps
        jsonBuilder.append("  \"timestamps\": [");
        for (int i = 0; i < SAMPLE_COUNT; i++) {
            long ts = BASE_TIMESTAMP_MS + (i * INTERVAL_MS);
            jsonBuilder.append(ts);
            if (i < SAMPLE_COUNT - 1) {
                jsonBuilder.append(", ");
            }
        }
        jsonBuilder.append("],\n");

        // values as list of [temp, hum]
        jsonBuilder.append("  \"values\": [\n");
        for (int i = 0; i < SAMPLE_COUNT; i++) {
            float temperature = generateGaussianValue(random, MEAN, STD_DEV);
            float humidity = generateGaussianValue(random, MEAN, STD_DEV);
            jsonBuilder.append("    [")
                    .append(formatFloat(temperature)).append(", ")
                    .append(formatFloat(humidity)).append("]");
            if (i < SAMPLE_COUNT - 1) {
                jsonBuilder.append(",\n");
            } else {
                jsonBuilder.append("\n");
            }
        }
        jsonBuilder.append("  ]\n");
        jsonBuilder.append("}\n");

        System.out.print(jsonBuilder.toString());
    }

    private static float generateGaussianValue(Random random, float mean, float stdDev) {
        return (float) (mean + random.nextGaussian() * stdDev);
    }

    private static String formatFloat(float value) {
        return String.format(Locale.US, "%.4f", value);
    }
}


