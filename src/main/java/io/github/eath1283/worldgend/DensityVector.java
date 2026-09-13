package io.github.eath1283.worldgend;

import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

public final class DensityVector {
    private static final VectorSpecies<Double> SPECIES = DoubleVector.SPECIES_PREFERRED;

    private DensityVector() {}

    public static int lanes() { return SPECIES.length(); }

    public static void apply(double[] values, DensityBatch.Operation operation, double first, double second) {
        int limit = SPECIES.loopBound(values.length);
        switch (operation) {
            case ABS -> {
                for (int i = 0; i < limit; i += SPECIES.length()) {
                    DoubleVector x = DoubleVector.fromArray(SPECIES, values, i);
                    x.abs().intoArray(values, i);
                }
            }
            case SQUARE -> {
                for (int i = 0; i < limit; i += SPECIES.length()) {
                    DoubleVector x = DoubleVector.fromArray(SPECIES, values, i);
                    x.mul(x).intoArray(values, i);
                }
            }
            case CUBE -> {
                for (int i = 0; i < limit; i += SPECIES.length()) {
                    DoubleVector x = DoubleVector.fromArray(SPECIES, values, i);
                    x.mul(x).mul(x).intoArray(values, i);
                }
            }
            case HALF_NEGATIVE -> {
                for (int i = 0; i < limit; i += SPECIES.length()) {
                    DoubleVector x = DoubleVector.fromArray(SPECIES, values, i);
                    x.mul(0.5).blend(x, x.compare(VectorOperators.GT, 0.0)).intoArray(values, i);
                }
            }
            case QUARTER_NEGATIVE -> {
                for (int i = 0; i < limit; i += SPECIES.length()) {
                    DoubleVector x = DoubleVector.fromArray(SPECIES, values, i);
                    x.mul(0.25).blend(x, x.compare(VectorOperators.GT, 0.0)).intoArray(values, i);
                }
            }
            case INVERT -> {
                for (int i = 0; i < limit; i += SPECIES.length()) {
                    DoubleVector x = DoubleVector.fromArray(SPECIES, values, i);
                    DoubleVector.broadcast(SPECIES, 1.0).div(x).intoArray(values, i);
                }
            }
            case SQUEEZE -> {
                for (int i = 0; i < limit; i += SPECIES.length()) {
                    DoubleVector x = DoubleVector.fromArray(SPECIES, values, i);
                    x = x.min(1.0).blend(-1.0, x.compare(VectorOperators.LT, -1.0));
                    x.div(2.0).sub(x.mul(x).mul(x).div(24.0)).intoArray(values, i);
                }
            }
            case MUL -> {
                for (int i = 0; i < limit; i += SPECIES.length()) {
                    DoubleVector x = DoubleVector.fromArray(SPECIES, values, i);
                    x.mul(first).intoArray(values, i);
                }
            }
            case ADD -> {
                for (int i = 0; i < limit; i += SPECIES.length()) {
                    DoubleVector x = DoubleVector.fromArray(SPECIES, values, i);
                    x.add(first).intoArray(values, i);
                }
            }
            case CLAMP -> {
                for (int i = 0; i < limit; i += SPECIES.length()) {
                    DoubleVector x = DoubleVector.fromArray(SPECIES, values, i);
                    x.min(second).blend(first, x.compare(VectorOperators.LT, first)).intoArray(values, i);
                }
            }
        }
        DensityBatch.scalar(values, operation, first, second, limit);
    }
}
