package org.example.ui;

import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
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

        // ---- run metrics grid
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

        // ---- cluster table
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

        // ---- buttons
        Button exportBtn = new Button("Export CSV");
        Button openFolderBtn = new Button("Open folder");
        Button closeBtn = new Button("Close");

        openFolderBtn.setDisable(true);

        exportBtn.setOnAction(e -> {
            try {
                Path dir = exportDir();
                Files.createDirectories(dir);

                ExportPaths out = exportCsv(dir, s, rows);
                openFolderBtn.setDisable(false);

                info("Export done",
                        "Saved:\n" +
                                out.runMetricsFile.getFileName() + "\n" +
                                out.clusterMetricsFile.getFileName() + "\n\n" +
                                "Folder:\n" + dir);
            } catch (Exception ex) {
                error("Export failed", ex.getMessage() == null ? ex.toString() : ex.getMessage());
            }
        });

        openFolderBtn.setOnAction(e -> {
            try {
                Path dir = exportDir();
                openFolder(dir);
            } catch (Exception ex) {
                error("Open folder failed", ex.getMessage() == null ? ex.toString() : ex.getMessage());
            }
        });

        closeBtn.setOnAction(e -> st.close());

        HBox buttons = new HBox(10, exportBtn, openFolderBtn, closeBtn);
        buttons.setPadding(new Insets(10, 0, 0, 0));

        VBox root = new VBox(10, runPane, clusterPane, buttons);
        root.setPadding(new Insets(12));

        st.setScene(new Scene(root, 760, 540));
        st.show();
    }

//экспорт

    private static Path exportDir() {
        // ~/.local/share/rosl/exports
        String home = System.getProperty("user.home");
        return Paths.get(home, ".local", "share", "rosl", "exports");
    }

    private static final class ExportPaths {
        final Path runMetricsFile;
        final Path clusterMetricsFile;

        private ExportPaths(Path runMetricsFile, Path clusterMetricsFile) {
            this.runMetricsFile = runMetricsFile;
            this.clusterMetricsFile = clusterMetricsFile;
        }
    }

    private static ExportPaths exportCsv(Path dir, RunSummary s, List<ClusterRow> rows) throws IOException {
        Path runFile = dir.resolve("run_" + s.runId() + "_run_metrics.csv");
        Path clusterFile = dir.resolve("run_" + s.runId() + "_cluster_metrics.csv");

        // run_metrics.csv (1 row)
        try (BufferedWriter w = Files.newBufferedWriter(runFile, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {

            w.write(String.join(",",
                    "run_id","dataset_id","mode","k","threads","max_iter","eps","stop_reason",
                    "total_ms","iterations","final_sse",
                    "avg_iter_ms","avg_assign_ms","avg_update_ms"
            ));
            w.newLine();

            w.write(String.join(",",
                    csv(s.runId()),
                    csv(s.datasetId()),
                    csv(s.mode()),
                    csv(s.k()),
                    csv(s.threads()),
                    csv(s.maxIter()),
                    csv(DF6.format(s.eps())),
                    csv(s.stopReason() == null ? "" : s.stopReason()),
                    csv(s.totalMs()),
                    csv(s.iterations()),
                    csv(DF6.format(s.finalSse())),
                    csv(DF2.format(s.avgIterMs())),
                    csv(DF2.format(s.avgAssignMs())),
                    csv(DF2.format(s.avgUpdateMs()))
            ));
            w.newLine();
        }

        // cluster_metrics.csv (table)
        try (BufferedWriter w = Files.newBufferedWriter(clusterFile, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {

            w.write(String.join(",", "cluster","size","share_pct","sse","avgDist","maxDist"));
            w.newLine();

            for (ClusterRow r : rows) {
                w.write(String.join(",",
                        csv(r.getCluster()),
                        csv(r.getSize()),
                        csv(DF1.format(r.getSharePct())),
                        csv(DF6.format(r.getSse())),
                        csv(DF6.format(r.getAvgDist())),
                        csv(DF6.format(r.getMaxDist()))
                ));
                w.newLine();
            }
        }

        return new ExportPaths(runFile, clusterFile);
    }

    private static String csv(Object v) {
        String s = String.valueOf(v);
        boolean needQuotes = s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r");
        if (!needQuotes) return s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    private static void openFolder(Path dir) throws IOException, InterruptedException {
        new ProcessBuilder("xdg-open", dir.toAbsolutePath().toString()).start();
    }

    private static int addRow(GridPane g, int r, String k, String v) {
        Label lk = new Label(k);
        lk.setStyle("-fx-font-weight: bold;");
        Label lv = new Label(v);
        g.addRow(r, lk, lv);
        return r + 1;
    }

    private static void info(String title, String msg) {
        Alert a = new Alert(Alert.AlertType.INFORMATION);
        a.setTitle(title);
        a.setHeaderText(title);
        a.setContentText(msg);
        a.showAndWait();
    }

    private static void error(String title, String msg) {
        Alert a = new Alert(Alert.AlertType.ERROR);
        a.setTitle(title);
        a.setHeaderText(title);
        a.setContentText(msg);
        a.showAndWait();
    }

    private ResultsWindow() {}
}
