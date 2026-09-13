import io.github.eath1283.worldgend.DensityBatch;

public final class DensityInstructionProbe {
    public static void main(String[] args) {
        double[] values = new double[128];
        DensityBatch.Operation[] operations = DensityBatch.Operation.values();
        java.util.Random random = new java.util.Random(69);
        for (DensityBatch.Operation operation : operations) {
            double[] actual = random.doubles(257, -2.0, 2.0).toArray();
            double[] expected = actual.clone();
            DensityBatch.scalar(expected, operation, 0.5, 1.0, 0);
            DensityBatch.apply(actual, operation, 0.5, 1.0);
            for (int i = 0; i < actual.length; i++) {
                if (Double.doubleToLongBits(actual[i]) != Double.doubleToLongBits(expected[i])) {
                    throw new AssertionError(operation + " at " + i);
                }
            }
        }
        for (int i = 0; i < 100_000; i++) {
            java.util.Arrays.fill(values, 0.25);
            DensityBatch.apply(values, operations[i % operations.length], 0.5, 1.0);
        }
        System.out.println(DensityBatch.report());
        System.out.println("checksum=" + java.util.Arrays.stream(values).sum());
    }
}
