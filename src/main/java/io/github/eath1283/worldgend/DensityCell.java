package io.github.eath1283.worldgend;

import java.util.Arrays;

public final class DensityCell {
    private static final ThreadLocal<DensityCell> LOCAL = ThreadLocal.withInitial(DensityCell::new);
    private static final DensityBatch.Operation[] OPERATIONS = DensityBatch.Operation.values();

    private double[][] slots = new double[16][];
    private int depth;

    private DensityCell() {}

    public static DensityCell get() {
        return LOCAL.get();
    }

    // Exact-length buffers: callees loop over array.length.
    public double[] push(int length) {
        if (depth + 1 >= slots.length) slots = Arrays.copyOf(slots, slots.length * 2);
        double[] buffer = slots[depth];
        if (buffer == null || buffer.length != length) {
            double[] spare = slots[depth + 1];
            if (spare != null && spare.length == length) {
                slots[depth + 1] = buffer;
                buffer = spare;
            } else {
                if (spare == null) slots[depth + 1] = buffer;
                buffer = new double[length];
            }
            slots[depth] = buffer;
        }
        depth += 2;
        return buffer;
    }

    public void pop() {
        depth -= 2;
    }

    public static boolean pure(Object function) {
        return function instanceof DensityCellNode node && node.orionCellPure();
    }

    public static void mapped(double[] values, int ordinal) {
        DensityBatch.apply(values, OPERATIONS[ordinal], 0.0, 0.0);
    }

    public static void affine(double[] values, int ordinal, double argument) {
        DensityBatch.apply(values, OPERATIONS[7 + ordinal], argument, 0.0);
    }

    public static void add(double[] output, double[] other) {
        for (int i = 0; i < output.length; i++) output[i] += other[i];
    }

    public static boolean anyNonZero(double[] values) {
        for (double v : values) if (v != 0.0) return true;
        return false;
    }

    public static void mulSkipZero(double[] output, double[] other) {
        for (int i = 0; i < output.length; i++) {
            double v = output[i];
            output[i] = v == 0.0 ? 0.0 : v * other[i];
        }
    }

    public static void zeroFill(double[] output) {
        Arrays.fill(output, 0.0);
    }

    public static boolean anyNotBelow(double[] values, double bound) {
        for (double v : values) if (!(v < bound)) return true;
        return false;
    }

    public static void minSelect(double[] output, double[] other, double bound) {
        for (int i = 0; i < output.length; i++) {
            double v = output[i];
            output[i] = v < bound ? v : Math.min(v, other[i]);
        }
    }

    public static boolean anyNotAbove(double[] values, double bound) {
        for (double v : values) if (!(v > bound)) return true;
        return false;
    }

    public static void maxSelect(double[] output, double[] other, double bound) {
        for (int i = 0; i < output.length; i++) {
            double v = output[i];
            output[i] = v > bound ? v : Math.max(v, other[i]);
        }
    }

    // 1 = every element in [min, max), 2 = none, 0 = mixed
    public static int rangeClass(double[] values, double min, double max) {
        boolean in = false;
        boolean out = false;
        for (double v : values) {
            if (v >= min && v < max) in = true;
            else out = true;
        }
        return in ? (out ? 0 : 1) : 2;
    }

    public static void rangeSelect(double[] output, double[] input, double[] whenIn, double min, double max) {
        for (int i = 0; i < output.length; i++) {
            double v = input[i];
            if (v >= min && v < max) output[i] = whenIn[i];
        }
    }

    // Same operation order as Mth.lerp3(x, y, z, ...) per element, iterated like NoiseChunk.fillAllDirectly.
    public static void lerp3Fill(double[] output, int width, int height,
                                 double n000, double n100, double n010, double n110,
                                 double n001, double n101, double n011, double n111) {
        int index = 0;
        for (int y = height - 1; y >= 0; y--) {
            double ay = (double) y / height;
            for (int x = 0; x < width; x++) {
                double ax = (double) x / width;
                double x00 = n000 + ax * (n100 - n000);
                double x10 = n010 + ax * (n110 - n010);
                double x01 = n001 + ax * (n101 - n001);
                double x11 = n011 + ax * (n111 - n011);
                double y0 = x00 + ay * (x10 - x00);
                double y1 = x01 + ay * (x11 - x01);
                for (int z = 0; z < width; z++) {
                    double az = (double) z / width;
                    output[index++] = y0 + az * (y1 - y0);
                }
            }
        }
    }

    // Same operation order as NoiseInterpolator.updateForY/X/Z.
    public static double lerpYXZ(double fy, double fx, double fz,
                                 double n000, double n100, double n010, double n110,
                                 double n001, double n101, double n011, double n111) {
        double xz00 = n000 + fy * (n010 - n000);
        double xz10 = n100 + fy * (n110 - n100);
        double xz01 = n001 + fy * (n011 - n001);
        double xz11 = n101 + fy * (n111 - n101);
        double z0 = xz00 + fx * (xz10 - xz00);
        double z1 = xz01 + fx * (xz11 - xz01);
        return z0 + fz * (z1 - z0);
    }
}
