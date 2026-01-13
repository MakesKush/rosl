package org.example.ui;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import org.example.model.Feature;
import org.example.model.PointVector;

import java.util.List;
import java.util.Random;

public final class PlotCanvas extends StackPane {

    private final Canvas canvas = new Canvas();
    private final GraphicsContext gc = canvas.getGraphicsContext2D();

    private List<PointVector> allPoints = List.of();

    private int[] assignment = null;
    private double[][] centroids = null;

    private Feature xFeat = null;
    private Feature yFeat = null;

    // sampling
    private int sampleLimit = 0;          // 0 => draw all
    private long sampleSeed = 12345L;
    private int[] drawIdx = new int[0];

    // scale cache
    private double minX = 0, maxX = 1, minY = 0, maxY = 1;
    private boolean hasScale = false;

    public PlotCanvas(double prefW, double prefH) {
        getChildren().add(canvas);
        setPrefSize(prefW, prefH);

        canvas.widthProperty().bind(widthProperty());
        canvas.heightProperty().bind(heightProperty());

        widthProperty().addListener((o, a, b) -> redraw());
        heightProperty().addListener((o, a, b) -> redraw());

        redraw();
    }

    public void setSampleLimit(int limit) {
        this.sampleLimit = Math.max(0, limit);
        recomputeDrawIdx();
        redraw();
    }

    public void setSampleSeed(long seed) {
        this.sampleSeed = seed;
        recomputeDrawIdx();
        redraw();
    }

    public int getSampleLimit() { return sampleLimit; }
    public int getDrawCount() { return drawIdx.length; }
    public int getTotalCount() { return allPoints.size(); }

    public void setData(List<PointVector> points, Feature x, Feature y) {
        this.allPoints = (points == null) ? List.of() : points;
        this.xFeat = x;
        this.yFeat = y;

        recomputeScale();
        recomputeDrawIdx();
        redraw();
    }

    public void setClustering(int[] assignment, double[][] centroids) {
        this.assignment = assignment;
        this.centroids = centroids;
        redraw();
    }

    public void clearClustering() {
        this.assignment = null;
        this.centroids = null;
        redraw();
    }

    private void recomputeDrawIdx() {
        int n = allPoints.size();
        if (n == 0) {
            drawIdx = new int[0];
            return;
        }

        if (sampleLimit <= 0 || n <= sampleLimit) {
            drawIdx = new int[n];
            for (int i = 0; i < n; i++) drawIdx[i] = i;
            return;
        }

        int m = sampleLimit;
        int[] idx = new int[n];
        for (int i = 0; i < n; i++) idx[i] = i;

        Random r = new Random(sampleSeed ^ n);
        for (int i = n - 1; i > 0; i--) {
            int j = r.nextInt(i + 1);
            int tmp = idx[i];
            idx[i] = idx[j];
            idx[j] = tmp;
        }

        drawIdx = new int[m];
        System.arraycopy(idx, 0, drawIdx, 0, m);
    }

    private void recomputeScale() {
        if (allPoints.isEmpty() || xFeat == null || yFeat == null) {
            hasScale = false;
            return;
        }

        int xi = xFeat.ordinal();
        int yi = yFeat.ordinal();

        double loX = Double.POSITIVE_INFINITY, hiX = Double.NEGATIVE_INFINITY;
        double loY = Double.POSITIVE_INFINITY, hiY = Double.NEGATIVE_INFINITY;

        for (PointVector p : allPoints) {
            double[] v = p.x();
            if (xi >= v.length || yi >= v.length) continue;

            double x = v[xi];
            double y = v[yi];

            if (x < loX) loX = x;
            if (x > hiX) hiX = x;
            if (y < loY) loY = y;
            if (y > hiY) hiY = y;
        }

        if (!Double.isFinite(loX) || !Double.isFinite(loY)) {
            hasScale = false;
            return;
        }

        // если все значения одинаковые — расширяем диапазон, чтобы график не пропал
        if (loX == hiX) { hiX = loX + 1.0; }
        if (loY == hiY) { hiY = loY + 1.0; }

        minX = loX; maxX = hiX;
        minY = loY; maxY = hiY;
        hasScale = true;
    }

    private void redraw() {
        double w = canvas.getWidth();
        double h = canvas.getHeight();

        gc.clearRect(0, 0, w, h);

        gc.setFill(Color.WHITE);
        gc.fillRect(0, 0, w, h);

        if (allPoints.isEmpty() || xFeat == null || yFeat == null || !hasScale) {
            gc.setFill(Color.GRAY);
            gc.setFont(Font.font(14));
            gc.fillText("Plot area", w * 0.5 - 30, h * 0.5);
            return;
        }

        double pad = 35;
        double left = pad, top = pad, right = w - pad, bottom = h - pad;

        gc.setStroke(Color.LIGHTGRAY);
        gc.strokeRect(left, top, right - left, bottom - top);

        gc.setFill(Color.GRAY);
        gc.setFont(Font.font(12));
        gc.fillText("X: " + xFeat.column, left + 5, bottom - 5);
        gc.fillText("Y: " + yFeat.column, left + 5, top + 12);

        int xi = xFeat.ordinal();
        int yi = yFeat.ordinal();

        for (int t = 0; t < drawIdx.length; t++) {
            int i = drawIdx[t];
            PointVector p = allPoints.get(i);
            double[] v = p.x();
            if (xi >= v.length || yi >= v.length) continue;

            double px = map(v[xi], minX, maxX, left, right);
            double py = map(v[yi], minY, maxY, bottom, top);

            int cl = -1;
            if (assignment != null && i < assignment.length) cl = assignment[i];

            gc.setFill(colorForCluster(cl));
            gc.fillOval(px - 2, py - 2, 4, 4);
        }

        if (centroids != null) {
            gc.setLineWidth(2.0);
            for (int k = 0; k < centroids.length; k++) {
                double[] c = centroids[k];
                if (xi >= c.length || yi >= c.length) continue;

                double cx = map(c[xi], minX, maxX, left, right);
                double cy = map(c[yi], minY, maxY, bottom, top);

                gc.setFill(colorForCluster(k));
                gc.fillOval(cx - 6, cy - 6, 12, 12);

                gc.setStroke(Color.BLACK);
                gc.strokeOval(cx - 6, cy - 6, 12, 12);
            }
        }
    }

    private static double map(double v, double a, double b, double lo, double hi) {
        double t = (v - a) / (b - a);
        if (t < 0) t = 0;
        if (t > 1) t = 1;
        return lo + t * (hi - lo);
    }

    private static Color colorForCluster(int cl) {
        if (cl < 0) return Color.rgb(60, 60, 60, 0.75);
        double hue = (cl * 47) % 360;
        return Color.hsb(hue, 0.85, 0.85);
    }
}
