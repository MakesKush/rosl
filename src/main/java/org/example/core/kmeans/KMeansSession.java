package org.example.core.kmeans;

import org.example.model.PointVector;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.*;

public final class KMeansSession implements AutoCloseable {

    private final List<PointVector> points;
    private final int n;
    private final int d;
    private final int k;
    private final int maxIter;
    private final double eps;

    private final int threads;
    private final ExecutorService pool; // null если threads==1

    private final Random rnd;

    private int iter = 0;
    private final int[] assignment;
    private double[][] centroids;

    public KMeansSession(List<PointVector> points, int k, int maxIter, double eps, long seed, int threads) {
        if (points == null || points.isEmpty()) throw new IllegalArgumentException("points empty");
        this.points = points;
        this.n = points.size();
        this.d = points.get(0).x().length;
        this.k = k;
        this.maxIter = maxIter;
        this.eps = eps;

        this.threads = Math.max(1, threads);
        this.pool = (this.threads > 1)
                ? Executors.newFixedThreadPool(this.threads, r -> {
            Thread t = new Thread(r, "kmeans-assign");
            t.setDaemon(true);
            return t;
        })
                : null;

        this.rnd = new Random(seed);

        this.assignment = new int[n];
        Arrays.fill(this.assignment, -1);
        this.centroids = initRandomCentroids();
    }

    public int getIter() { return iter; }
    public int[] getAssignment() { return assignment; }
    public double[][] getCentroids() { return centroids; }

    public IterationSnapshot step() {
        if (iter >= maxIter) {
            return new IterationSnapshot(
                    iter, Double.NaN, 0, 0, 0,
                    Arrays.copyOf(assignment, assignment.length),
                    deepCopy(centroids),
                    "MAX_ITER"
            );
        }

        long t0 = System.nanoTime();

        long a0 = System.nanoTime();
        int changes = (threads <= 1) ? assignPointsSequential() : assignPointsParallel();
        long a1 = System.nanoTime();

        long u0 = System.nanoTime();
        double shift = recomputeCentroids();
        long u1 = System.nanoTime();

        double sse = computeSSE();

        long t1 = System.nanoTime();

        iter++;

        double assignMs = (a1 - a0) / 1_000_000.0;
        double updateMs = (u1 - u0) / 1_000_000.0;
        double totalMs  = (t1 - t0) / 1_000_000.0;

        String stop = null;
        if (changes == 0) stop = "NO_CHANGES";
        if (shift < eps) stop = "EPS_REACHED";
        if (iter >= maxIter) stop = "MAX_ITER";

        return new IterationSnapshot(
                iter, sse, assignMs, updateMs, totalMs,
                Arrays.copyOf(assignment, assignment.length),
                deepCopy(centroids),
                stop
        );
    }

    private double[][] initRandomCentroids() {
        double[][] c = new double[k][d];
        for (int i = 0; i < k; i++) {
            int idx = rnd.nextInt(n);
            c[i] = Arrays.copyOf(points.get(idx).x(), d);
        }
        return c;
    }

    private int assignPointsSequential() {
        int changes = 0;
        for (int i = 0; i < n; i++) {
            double[] x = points.get(i).x();

            int bestK = 0;
            double bestDist = dist2(x, centroids[0]);

            for (int kk = 1; kk < k; kk++) {
                double d2 = dist2(x, centroids[kk]);
                if (d2 < bestDist) {
                    bestDist = d2;
                    bestK = kk;
                }
            }

            if (assignment[i] != bestK) {
                assignment[i] = bestK;
                changes++;
            }
        }
        return changes;
    }

    private int assignPointsParallel() {
        if (pool == null) return assignPointsSequential();

        final double[][] c = centroids; // локальная ссылка
        int chunks = Math.min(threads, n);
        int chunkSize = (n + chunks - 1) / chunks;

        List<Callable<Integer>> tasks = new ArrayList<>(chunks);
        for (int part = 0; part < chunks; part++) {
            final int start = part * chunkSize;
            final int end = Math.min(n, start + chunkSize);
            if (start >= end) continue;

            tasks.add(() -> {
                int localChanges = 0;
                for (int i = start; i < end; i++) {
                    double[] x = points.get(i).x();

                    int bestK = 0;
                    double bestDist = dist2(x, c[0]);
                    for (int kk = 1; kk < k; kk++) {
                        double d2 = dist2(x, c[kk]);
                        if (d2 < bestDist) {
                            bestDist = d2;
                            bestK = kk;
                        }
                    }

                    if (assignment[i] != bestK) {
                        assignment[i] = bestK;
                        localChanges++;
                    }
                }
                return localChanges;
            });
        }

        try {
            int changes = 0;
            List<Future<Integer>> futures = pool.invokeAll(tasks);
            for (Future<Integer> f : futures) changes += f.get();
            return changes;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("assignPointsParallel interrupted", ie);
        } catch (ExecutionException ee) {
            throw new RuntimeException("assignPointsParallel failed", ee.getCause());
        }
    }

    private double recomputeCentroids() {
        double[][] sum = new double[k][d];
        int[] cnt = new int[k];

        for (int i = 0; i < n; i++) {
            int cl = assignment[i];
            double[] x = points.get(i).x();
            cnt[cl]++;
            for (int j = 0; j < d; j++) sum[cl][j] += x[j];
        }

        double shift = 0.0;
        for (int kk = 0; kk < k; kk++) {
            if (cnt[kk] == 0) {
                int idx = rnd.nextInt(n);
                double[] newC = Arrays.copyOf(points.get(idx).x(), d);
                shift += Math.sqrt(dist2(centroids[kk], newC));
                centroids[kk] = newC;
                continue;
            }

            double[] newC = new double[d];
            for (int j = 0; j < d; j++) newC[j] = sum[kk][j] / cnt[kk];

            shift += Math.sqrt(dist2(centroids[kk], newC));
            centroids[kk] = newC;
        }
        return shift;
    }

    private double computeSSE() {
        double sse = 0.0;
        for (int i = 0; i < n; i++) {
            int cl = assignment[i];
            sse += dist2(points.get(i).x(), centroids[cl]);
        }
        return sse;
    }

    private static double dist2(double[] a, double[] b) {
        double s = 0.0;
        for (int i = 0; i < a.length; i++) {
            double dx = a[i] - b[i];
            s += dx * dx;
        }
        return s;
    }

    private static double[][] deepCopy(double[][] m) {
        double[][] out = new double[m.length][];
        for (int i = 0; i < m.length; i++) out[i] = Arrays.copyOf(m[i], m[i].length);
        return out;
    }

    @Override
    public void close() {
        if (pool != null) {
            pool.shutdownNow();
        }
    }
}
