package org.example.core;

import org.example.model.Feature;
import org.example.model.PointVector;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public final class DataGenerator {
    private DataGenerator() {}

    // Порядок признаков ОЖИДАЕТСЯ такой:
    // 0..4   -> likes:    sport, music, games, films, memes
    // 5..9   -> comments: sport, music, games, films, memes
    // 10..14 -> posts:    sport, music, games, films, memes
    private static final int TOPICS = 5;

    public static List<PointVector> generate(int n, long seed, int trueClusters, double noiseSigma) {
        int d = Feature.count();
        if (d != TOPICS * 3) {
            throw new IllegalStateException("Feature.count() должен быть 15 (5 тем * 3 метрики). Сейчас: " + d);
        }

        Random rnd = new Random(seed);

        double[][] meanLikes = new double[trueClusters][TOPICS];
        double[][] meanComments = new double[trueClusters][TOPICS];
        double[][] meanPosts = new double[trueClusters][TOPICS];

        for (int k = 0; k < trueClusters; k++) {
            int fav1 = rnd.nextInt(TOPICS);
            int fav2 = rnd.nextInt(TOPICS - 1);
            if (fav2 >= fav1) fav2++; // гарантируем fav2 != fav1

            for (int t = 0; t < TOPICS; t++) {
                boolean fav = (t == fav1 || t == fav2);

                double likesMean = fav
                        ? (200 + rnd.nextDouble() * 400)   // 200..600
                        : (20 + rnd.nextDouble() * 180);   // 20..200

                double commentsMean = likesMean * (0.06 + rnd.nextDouble() * 0.18); // ~6%..24%

                double postsMean = Math.max(0.0, likesMean / 30.0 + rnd.nextDouble() * 4.0); // примерно 0..24

                meanLikes[k][t] = likesMean;
                meanComments[k][t] = commentsMean;
                meanPosts[k][t] = postsMean;
            }
        }

        List<PointVector> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int k = rnd.nextInt(trueClusters);

            double[] x = new double[d];

            for (int t = 0; t < TOPICS; t++) {
                double likes = sampleNonNegativeInt(meanLikes[k][t], noiseSigma, rnd);
                double comments = sampleNonNegativeInt(meanComments[k][t], noiseSigma, rnd);
                double posts = sampleNonNegativeInt(meanPosts[k][t], noiseSigma, rnd);

                // блоками: likes[0..4], comments[5..9], posts[10..14]
                x[t] = likes;
                x[TOPICS + t] = comments;
                x[2 * TOPICS + t] = posts;
            }

            out.add(new PointVector(i, x));
        }

        return out;
    }

    private static double sampleNonNegativeInt(double mean, double relSigma, Random rnd) {
        double v = mean + rnd.nextGaussian() * (relSigma * Math.max(mean, 1.0));
        if (v < 0) v = 0;
        return Math.rint(v);
    }
}
