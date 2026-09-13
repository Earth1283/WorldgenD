package io.github.eath1283.worldgend;

import java.util.concurrent.atomic.LongAdder;

public final class DensityBatch {
    public enum Operation { ABS, SQUARE, CUBE, HALF_NEGATIVE, QUARTER_NEGATIVE, INVERT, SQUEEZE, MUL, ADD, CLAMP }

    private static final String MODE = System.getProperty("orion.simd", "auto");
    private static final boolean VECTOR = selectVector();
    private static final boolean VERIFY = Boolean.getBoolean("orion.simd.verify");
    private static final LongAdder calls = new LongAdder();
    private static final LongAdder elements = new LongAdder();
    private static final java.util.concurrent.ConcurrentHashMap<Integer, LongAdder> lengths = new java.util.concurrent.ConcurrentHashMap<>();

    private DensityBatch() {}

    private static boolean selectVector() {
        if (!MODE.equals("auto") && !MODE.equals("scalar")) {
            throw new IllegalArgumentException("orion.simd must be auto or scalar, got " + MODE);
        }
        return MODE.equals("auto") && ModuleLayer.boot().findModule("jdk.incubator.vector").isPresent();
    }

    public static void mapped(double[] values, String operation) {
        apply(values, Operation.valueOf(operation), 0.0, 0.0);
    }

    public static void affine(double[] values, String operation, double argument) {
        apply(values, Operation.valueOf(operation), argument, 0.0);
    }

    public static void clamp(double[] values, double lower, double upper) {
        apply(values, Operation.CLAMP, lower, upper);
    }

    public static void apply(double[] values, Operation operation, double first, double second) {
        if (VERIFY) {
            calls.increment();
            elements.add(values.length);
            lengths.computeIfAbsent(values.length, key -> new LongAdder()).increment();
        }
        if (VECTOR) DensityVector.apply(values, operation, first, second);
        else scalar(values, operation, first, second, 0);
    }

    public static String report() {
        return "backend=" + (VECTOR ? "vector" : "scalar") + " lanes=" + (VECTOR ? DensityVector.lanes() : 1)
            + " mode=" + MODE + " verify=" + VERIFY + " calls=" + calls.sum() + " elements=" + elements.sum()
            + (VERIFY ? " lengths=" + new java.util.TreeMap<>(lengths) : "");
    }

    public static void scalar(double[] values, Operation operation, double first, double second, int start) {
        switch (operation) {
            case ABS -> {
                for (int i = start; i < values.length; i++) {
                    double x = values[i];
                    values[i] = Math.abs(x);
                }
            }
            case SQUARE -> {
                for (int i = start; i < values.length; i++) {
                    double x = values[i];
                    values[i] = x * x;
                }
            }
            case CUBE -> {
                for (int i = start; i < values.length; i++) {
                    double x = values[i];
                    values[i] = x * x * x;
                }
            }
            case HALF_NEGATIVE -> {
                for (int i = start; i < values.length; i++) {
                    double x = values[i];
                    values[i] = x > 0.0 ? x : x * 0.5;
                }
            }
            case QUARTER_NEGATIVE -> {
                for (int i = start; i < values.length; i++) {
                    double x = values[i];
                    values[i] = x > 0.0 ? x : x * 0.25;
                }
            }
            case INVERT -> {
                for (int i = start; i < values.length; i++) {
                    double x = values[i];
                    values[i] = 1.0 / x;
                }
            }
            case SQUEEZE -> {
                for (int i = start; i < values.length; i++) {
                    double x = values[i];
                    x = x < -1.0 ? -1.0 : Math.min(x, 1.0);
                    values[i] = x / 2.0 - x * x * x / 24.0;
                }
            }
            case MUL -> {
                for (int i = start; i < values.length; i++) {
                    double x = values[i];
                    values[i] = x * first;
                }
            }
            case ADD -> {
                for (int i = start; i < values.length; i++) {
                    double x = values[i];
                    values[i] = x + first;
                }
            }
            case CLAMP -> {
                for (int i = start; i < values.length; i++) {
                    double x = values[i];
                    values[i] = x < first ? first : Math.min(x, second);
                }
            }
        }
    }
}
