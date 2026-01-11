package org.example.core.kmeans;

import org.example.model.PointVector;

import java.util.List;

public final class ClusterMetricsCalc {
    private ClusterMetricsCalc() {}

    public record ClusterMetricsResult(
            int[] size,
            double[] clusterSse,
            double[] avgDist,
            double[] maxDist
    ) {}

    public static ClusterMetricsResult compute(List<PointVector> points, int[] assign, double[][] centroids) {
        int n = points.size();
        int k = centroids.length;

        int[] size = new int[k];
        double[] sse = new double[k];
        double[] sumDist = new double[k];
        double[] maxDist = new double[k];

        for (int i = 0; i < n; i++) {
            int cl = assign[i];
            if (cl < 0 || cl >= k) continue;

            double d2 = dist2(points.get(i).x(), centroids[cl]);
            double d = Math.sqrt(d2);

            size[cl]++;
            sse[cl] += d2;
            sumDist[cl] += d;
            if (d > maxDist[cl]) maxDist[cl] = d;
        }

        double[] avg = new double[k];
        for (int cl = 0; cl < k; cl++) {
            avg[cl] = size[cl] == 0 ? 0.0 : (sumDist[cl] / size[cl]);
        }

        return new ClusterMetricsResult(size, sse, avg, maxDist);
    }

    private static double dist2(double[] a, double[] b) {
        double s = 0.0;
        for (int i = 0; i < a.length; i++) {
            double dx = a[i] - b[i];
            s += dx * dx;
        }
        return s;
    }
}
