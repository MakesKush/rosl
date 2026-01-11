package org.example.core.kmeans;

public record IterationSnapshot(
        int iter,
        double sse,
        double assignMs,
        double updateMs,
        double totalMs,
        int[] assignment,
        double[][] centroids,
        String stopReason
) { }
