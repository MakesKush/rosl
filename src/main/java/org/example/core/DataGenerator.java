package org.example.core;

import org.example.model.Feature;
import org.example.model.PointVector;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public final class DataGenerator {
    private DataGenerator() {}

    /**
     * Генерим "соцсеточных" пользователей как векторы длины D.
     * Делается несколько скрытых центров + гауссов шум.
     * Значения ограничиваем [0..1].
     */
    public static List<PointVector> generate(int n, long seed, int trueClusters, double noiseSigma) {
        int d = Feature.count();
        Random rnd = new Random(seed);

        // центры "истинных" групп
        double[][] centers = new double[trueClusters][d];
        for (int k = 0; k < trueClusters; k++) {
            for (int j = 0; j < d; j++) {
                centers[k][j] = rnd.nextDouble(); // [0..1]
            }
        }

        List<PointVector> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int k = rnd.nextInt(trueClusters);
            double[] x = new double[d];
            for (int j = 0; j < d; j++) {
                double v = centers[k][j] + rnd.nextGaussian() * noiseSigma;
                // clamp to [0..1]
                if (v < 0) v = 0;
                if (v > 1) v = 1;
                x[j] = v;
            }
            out.add(new PointVector(i, x));
        }
        return out;
    }
}
