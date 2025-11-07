public class GenerateIoTDBBatch {
    public static void main(String[] args) {
        String[] forwarded = (args != null && args.length == 1)
                ? new String[]{args[0]}
                : new String[]{"0"};
        GenerateIoTDBBatchDeterministic.main(forwarded);
    }
}


