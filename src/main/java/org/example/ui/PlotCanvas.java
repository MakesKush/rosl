package org.example.ui;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import org.example.model.Feature;
import org.example.model.PointVector;

import java.util.List;

public final class PlotCanvas extends Canvas {

    private List<PointVector> points = List.of();
    private int xFeature = 0;
    private int yFeature = 1;

    private int[] assignment = null;
    private double[][] centroids = null;

    private static final Color[] PALETTE = new Color[] {
            Color.web("#1f77b4"),
            Color.web("#ff7f0e"),
            Color.web("#2ca02c"),
            Color.web("#d62728"),
            Color.web("#9467bd"),
            Color.web("#8c564b"),
            Color.web("#e377c2"),
            Color.web("#7f7f7f"),
            Color.web("#bcbd22"),
            Color.web("#17becf")
    };

    public PlotCanvas(double w, double h) {
        super(w, h);
        widthProperty().addListener((obs, o, n) -> redraw());
        heightProperty().addListener((obs, o, n) -> redraw());
    }

    public void setData(List<PointVector> points, Feature x, Feature y) {
        this.points = points != null ? points : List.of();
        this.xFeature = x.ordinal();
        this.yFeature = y.ordinal();
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

    public void redraw() {
        double w = getWidth();
        double h = getHeight();
        GraphicsContext g = getGraphicsContext2D();

        g.setFill(Color.WHITE);
        g.fillRect(0, 0, w, h);

        g.setStroke(Color.LIGHTGRAY);
        g.strokeRect(0.5, 0.5, w - 1, h - 1);

        if (points.isEmpty()) {
            g.setFill(Color.GRAY);
            g.fillText("No data selected", 20, 30);
            return;
        }

        double pad = 25;
        double ww = Math.max(1, w - 2 * pad);
        double hh = Math.max(1, h - 2 * pad);

        g.setFill(Color.BLACK);
        g.fillText("X: " + Feature.values()[xFeature].column, 10, h - 10);
        g.fillText("Y: " + Feature.values()[yFeature].column, 10, 20);

        // points
        double r = 2.2;
        for (int i = 0; i < points.size(); i++) {
            double[] v = points.get(i).x();
            double xv = v[xFeature];
            double yv = v[yFeature];

            double px = pad + xv * ww;
            double py = pad + (1.0 - yv) * hh;

            Color c = Color.rgb(60, 90, 200, 0.85);
            if (assignment != null && assignment.length == points.size() && assignment[i] >= 0) {
                c = PALETTE[assignment[i] % PALETTE.length];
            }
            g.setFill(c);
            g.fillOval(px - r, py - r, 2 * r, 2 * r);
        }

        // centroids
        if (centroids != null) {
            for (int k = 0; k < centroids.length; k++) {
                double[] c = centroids[k];
                double cx = c[xFeature];
                double cy = c[yFeature];

                double px = pad + cx * ww;
                double py = pad + (1.0 - cy) * hh;

                Color col = PALETTE[k % PALETTE.length];
                g.setFill(col);
                g.fillOval(px - 6, py - 6, 12, 12);

                g.setStroke(Color.BLACK);
                g.strokeOval(px - 6, py - 6, 12, 12);
            }
        }
    }
}
