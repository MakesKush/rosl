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

    public PlotCanvas(double w, double h) {
        super(w, h);

        // перерисовка при изменении размера
        widthProperty().addListener((obs, o, n) -> redraw());
        heightProperty().addListener((obs, o, n) -> redraw());
    }

    public void setData(List<PointVector> points, Feature x, Feature y) {
        this.points = points != null ? points : List.of();
        this.xFeature = x.ordinal();
        this.yFeature = y.ordinal();
        redraw();
    }

    public void redraw() {
        double w = getWidth();
        double h = getHeight();
        GraphicsContext g = getGraphicsContext2D();

        // background
        g.setFill(Color.WHITE);
        g.fillRect(0, 0, w, h);

        // border
        g.setStroke(Color.LIGHTGRAY);
        g.strokeRect(0.5, 0.5, w - 1, h - 1);

        if (points.isEmpty()) {
            g.setFill(Color.GRAY);
            g.fillText("No data selected", 20, 30);
            return;
        }

        // у нас значения 0..1
        double pad = 25;
        double x0 = pad;
        double y0 = pad;
        double ww = Math.max(1, w - 2 * pad);
        double hh = Math.max(1, h - 2 * pad);

        // axes labels
        g.setFill(Color.BLACK);
        g.fillText(Feature.values()[xFeature].column, 10, h - 10);
        g.fillText(Feature.values()[yFeature].column, 10, 20);

        // points
        g.setFill(Color.rgb(60, 90, 200, 0.85));
        double r = 2.2;

        for (PointVector p : points) {
            double[] v = p.x();
            double xv = v[xFeature]; // [0..1]
            double yv = v[yFeature]; // [0..1]

            double px = x0 + xv * ww;
            double py = y0 + (1.0 - yv) * hh;

            g.fillOval(px - r, py - r, 2 * r, 2 * r);
        }
    }
}
