package org.example.ui;

import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.text.DecimalFormat;
import java.util.List;

public final class ResultsWindow {

    private static final DecimalFormat DF6 = new DecimalFormat("0.000000");
    private static final DecimalFormat DF2 = new DecimalFormat("0.00");
    private static final DecimalFormat DF1 = new DecimalFormat("0.0");

    public record RunSummary(
            long runId,
            long datasetId,
            String mode,
            int k,
            int threads,
            int maxIter,
            double eps,
            String stopReason,
            long totalMs,
            int iterations,
            double finalSse,
            double avgIterMs,
            double avgAssignMs,
            double avgUpdateMs
    ) {}

    public static final class ClusterRow {
        private final IntegerProperty cluster = new SimpleIntegerProperty();
        private final IntegerProperty size = new SimpleIntegerProperty();
        private final DoubleProperty sharePct = new SimpleDoubleProperty();
        private final DoubleProperty sse = new SimpleDoubleProperty();
        private final DoubleProperty avgDist = new SimpleDoubleProperty();
        private final DoubleProperty maxDist = new SimpleDoubleProperty();

        public ClusterRow(int cluster, int size, double sharePct, double sse, double avgDist, double maxDist) {
            this.cluster.set(cluster);
            this.size.set(size);
            this.sharePct.set(sharePct);
            this.sse.set(sse);
            this.avgDist.set(avgDist);
            this.maxDist.set(maxDist);
        }

        public int getCluster() { return cluster.get(); }
        public int getSize() { return size.get(); }
        public double getSharePct() { return sharePct.get(); }
        public double getSse() { return sse.get(); }
        public double getAvgDist() { return avgDist.get(); }
        public double getMaxDist() { return maxDist.get(); }
    }

    public static void show(Stage owner, RunSummary s, List<ClusterRow> rows) {
        Stage st = new Stage();
        st.initOwner(owner);
        st.initModality(Modality.WINDOW_MODAL);
        st.setTitle("ROSL — Results (run #" + s.runId() + ")");

        // ----- top: run metrics grid -----
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);

        int r = 0;
        r = addRow(grid, r, "Run ID:", String.valueOf(s.runId()));
        r = addRow(grid, r, "Dataset ID:", String.valueOf(s.datasetId()));
        r = addRow(grid, r, "Mode:", s.mode());
        r = addRow(grid, r, "K / Threads:", s.k() + " / " + s.threads());
        r = addRow(grid, r, "MaxIter / Eps:", s.maxIter() + " / " + DF6.format(s.eps()));
        r = addRow(grid, r, "Stop reason:", s.stopReason() == null ? "-" : s.stopReason());
        r = addRow(grid, r, "Total ms:", String.valueOf(s.totalMs()));
        r = addRow(grid, r, "Iterations:", String.valueOf(s.iterations()));
        r = addRow(grid, r, "Final SSE:", DF6.format(s.finalSse()));
        r = addRow(grid, r, "Avg iter ms:", DF2.format(s.avgIterMs()));
        r = addRow(grid, r, "Avg assign ms:", DF2.format(s.avgAssignMs()));
        r = addRow(grid, r, "Avg update ms:", DF2.format(s.avgUpdateMs()));

        TitledPane runPane = new TitledPane("Run metrics", grid);
        runPane.setCollapsible(false);

        // ----- bottom: cluster table -----
        TableView<ClusterRow> table = new TableView<>();
        table.setItems(FXCollections.observableArrayList(rows));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        TableColumn<ClusterRow, Number> cCluster = new TableColumn<>("cluster");
        cCluster.setCellValueFactory(v -> new SimpleIntegerProperty(v.getValue().getCluster()));

        TableColumn<ClusterRow, Number> cSize = new TableColumn<>("size");
        cSize.setCellValueFactory(v -> new SimpleIntegerProperty(v.getValue().getSize()));

        TableColumn<ClusterRow, String> cShare = new TableColumn<>("share %");
        cShare.setCellValueFactory(v -> new SimpleStringProperty(DF1.format(v.getValue().getSharePct())));

        TableColumn<ClusterRow, String> cSse = new TableColumn<>("sse");
        cSse.setCellValueFactory(v -> new SimpleStringProperty(DF6.format(v.getValue().getSse())));

        TableColumn<ClusterRow, String> cAvg = new TableColumn<>("avgDist");
        cAvg.setCellValueFactory(v -> new SimpleStringProperty(DF6.format(v.getValue().getAvgDist())));

        TableColumn<ClusterRow, String> cMax = new TableColumn<>("maxDist");
        cMax.setCellValueFactory(v -> new SimpleStringProperty(DF6.format(v.getValue().getMaxDist())));

        table.getColumns().addAll(cCluster, cSize, cShare, cSse, cAvg, cMax);

        TitledPane clusterPane = new TitledPane("Cluster metrics", table);
        clusterPane.setCollapsible(false);

        // ----- buttons -----
        Button close = new Button("Close");
        close.setOnAction(e -> st.close());
        HBox buttons = new HBox(10, close);
        buttons.setPadding(new Insets(10, 0, 0, 0));

        VBox root = new VBox(10, runPane, clusterPane, buttons);
        root.setPadding(new Insets(12));

        st.setScene(new Scene(root, 720, 520));
        st.show();
    }

    private static int addRow(GridPane g, int r, String k, String v) {
        Label lk = new Label(k);
        lk.setStyle("-fx-font-weight: bold;");
        Label lv = new Label(v);
        g.addRow(r, lk, lv);
        return r + 1;
    }

    private ResultsWindow() {}
}
