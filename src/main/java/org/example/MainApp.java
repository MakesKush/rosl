package org.example;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import org.example.core.DataGenerator;
import org.example.core.kmeans.ClusterMetricsCalc;
import org.example.core.kmeans.IterationSnapshot;
import org.example.core.kmeans.KMeansSession;
import org.example.db.Database;
import org.example.db.DatasetRepository;
import org.example.db.MetricsRepository;
import org.example.db.ResultRepository;
import org.example.db.RunRepository;
import org.example.model.DatasetInfo;
import org.example.model.Feature;
import org.example.model.PointVector;
import org.example.model.RunMode;
import org.example.ui.PlotCanvas;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainApp extends Application {

    private static final long UI_THROTTLE_NS = 400_000_000L; // 400ms

    private final DatasetRepository datasetRepo = new DatasetRepository();
    private final RunRepository runRepo = new RunRepository();
    private final ResultRepository resultRepo = new ResultRepository();
    private final MetricsRepository metricsRepo = new MetricsRepository();

    private volatile KMeansSession session = null;
    private volatile long currentDatasetId = -1;
    private volatile Long currentRunId = null;

    // run stats
    private volatile long runStartNano = 0L;
    private volatile double sumIterMs = 0.0;
    private volatile double sumAssignMs = 0.0;
    private volatile double sumUpdateMs = 0.0;
    private volatile int iterCount = 0;

    private volatile long lastUiUpdateNano = 0L;
    private volatile boolean running = false;

    // background worker
    private final ExecutorService bg = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "bg-worker");
        t.setDaemon(true);
        return t;
    });

    private final ListView<DatasetInfo> datasetList = new ListView<>();
    private final Label status = new Label("Ready");
    private volatile List<PointVector> currentPoints = List.of();

    private record RunParams(RunMode mode, int k, int maxIter, double eps, int threads) {}

    @Override
    public void start(Stage stage) {
        Database.init();

        BorderPane root = new BorderPane();
        root.setPadding(new Insets(10));

        // ---------- LEFT ----------
        VBox left = new VBox(10);
        left.setPrefWidth(320);

        Label leftTitle = new Label("Datasets");
        leftTitle.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");

        TextField nField = new TextField("500");
        TextField seedField = new TextField("42");
        TextField sigmaField = new TextField("0.15");

        GridPane form = new GridPane();
        form.setHgap(8);
        form.setVgap(8);
        form.addRow(0, new Label("N (points):"), nField);
        form.addRow(1, new Label("Seed:"), seedField);
        form.addRow(2, new Label("Noise sigma:"), sigmaField);

        Button genBtn = new Button("Generate dataset");
        genBtn.setMaxWidth(Double.MAX_VALUE);

        datasetList.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(DatasetInfo item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) setText(null);
                else {
                    setText(
                            "#" + item.id() + "  " + item.name()
                                    + "\nN=" + item.n() + " D=" + item.d()
                                    + (item.createdAt() != null ? "\n" + item.createdAt() : "")
                    );
                }
            }
        });

        left.getChildren().addAll(leftTitle, form, genBtn, new Separator(), datasetList);
        VBox.setVgrow(datasetList, Priority.ALWAYS);

        // ---------- CENTER ----------
        PlotCanvas plot = new PlotCanvas(700, 650);

        ComboBox<Feature> xAxis = new ComboBox<>();
        xAxis.getItems().setAll(Feature.values());
        xAxis.setValue(Feature.SPORTS);

        ComboBox<Feature> yAxis = new ComboBox<>();
        yAxis.getItems().setAll(Feature.values());
        yAxis.setValue(Feature.GAMES);

        HBox axisBar = new HBox(10, new Label("X:"), xAxis, new Label("Y:"), yAxis);
        axisBar.setPadding(new Insets(8));
        axisBar.setStyle("-fx-background-color: #f4f4f4; -fx-border-color: #ddd;");

        BorderPane center = new BorderPane();
        center.setTop(axisBar);
        center.setCenter(plot);

        // ---------- RIGHT ----------
        VBox right = new VBox(10);
        right.setPadding(new Insets(0, 0, 0, 10));
        right.setPrefWidth(300);

        Label runTitle = new Label("K-Means");
        runTitle.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");

        ComboBox<RunMode> modeBox = new ComboBox<>();
        modeBox.getItems().setAll(RunMode.values());
        modeBox.setValue(RunMode.DEMO);

        TextField kField = new TextField("4");
        TextField maxIterField = new TextField("30");
        TextField epsField = new TextField("0.001");
        TextField threadsField = new TextField("4");

        Button stepBtn = new Button("Step");
        Button runBtn = new Button("Run");
        Button pauseBtn = new Button("Pause");
        Button resetBtn = new Button("Reset");

        stepBtn.setMaxWidth(Double.MAX_VALUE);
        runBtn.setMaxWidth(Double.MAX_VALUE);
        pauseBtn.setMaxWidth(Double.MAX_VALUE);
        resetBtn.setMaxWidth(Double.MAX_VALUE);

        pauseBtn.setDisable(true);

        Label iterLabel = new Label("iter: -");
        Label sseLabel = new Label("sse: -");
        Label timeLabel = new Label("iter ms: -");

        // Charts
        NumberAxis x1 = new NumberAxis();
        NumberAxis y1 = new NumberAxis();
        x1.setLabel("iter");
        y1.setLabel("SSE");
        LineChart<Number, Number> sseChart = new LineChart<>(x1, y1);
        sseChart.setLegendVisible(false);
        sseChart.setCreateSymbols(false);
        sseChart.setAnimated(false);
        sseChart.setMinHeight(180);
        XYChart.Series<Number, Number> sseSeries = new XYChart.Series<>();
        sseChart.getData().add(sseSeries);

        NumberAxis x2 = new NumberAxis();
        NumberAxis y2 = new NumberAxis();
        x2.setLabel("iter");
        y2.setLabel("iter ms");
        LineChart<Number, Number> timeChart = new LineChart<>(x2, y2);
        timeChart.setLegendVisible(false);
        timeChart.setCreateSymbols(false);
        timeChart.setAnimated(false);
        timeChart.setMinHeight(180);
        XYChart.Series<Number, Number> timeSeries = new XYChart.Series<>();
        timeChart.getData().add(timeSeries);

        VBox.setVgrow(sseChart, Priority.ALWAYS);
        VBox.setVgrow(timeChart, Priority.ALWAYS);

        GridPane runForm = new GridPane();
        runForm.setHgap(8);
        runForm.setVgap(8);
        runForm.addRow(0, new Label("Mode:"), modeBox);
        runForm.addRow(1, new Label("K:"), kField);
        runForm.addRow(2, new Label("Max iter:"), maxIterField);
        runForm.addRow(3, new Label("Eps:"), epsField);
        runForm.addRow(4, new Label("Threads:"), threadsField);

        right.getChildren().addAll(
                runTitle,
                runForm,
                new Separator(),
                stepBtn, runBtn, pauseBtn, resetBtn,
                new Separator(),
                iterLabel, sseLabel, timeLabel,
                new Separator(),
                new Label("Progress"),
                sseChart,
                timeChart
        );

        // ---------- BOTTOM ----------
        HBox bottom = new HBox(status);
        bottom.setPadding(new Insets(8, 0, 0, 0));

        root.setLeft(left);
        root.setCenter(center);
        root.setRight(right);
        root.setBottom(bottom);

        // ---------- Actions ----------
        xAxis.valueProperty().addListener((obs, o, n) ->
                plot.setData(currentPoints, xAxis.getValue(), yAxis.getValue())
        );
        yAxis.valueProperty().addListener((obs, o, n) ->
                plot.setData(currentPoints, xAxis.getValue(), yAxis.getValue())
        );

        // Generate dataset
        genBtn.setOnAction(e -> {
            int n;
            long seed;
            double sigma;

            try {
                n = Integer.parseInt(nField.getText().trim());
                seed = Long.parseLong(seedField.getText().trim());
                sigma = Double.parseDouble(sigmaField.getText().trim());
                if (n <= 0) throw new NumberFormatException();
                if (sigma <= 0) throw new NumberFormatException();
            } catch (Exception ex) {
                showError("Bad input", "Check N/Seed/Sigma values.");
                return;
            }

            genBtn.setDisable(true);
            status.setText("Generating dataset...");

            Task<Long> task = new Task<>() {
                @Override
                protected Long call() {
                    var points = DataGenerator.generate(n, seed, 4, sigma);
                    String name = "gen_N" + n + "_seed" + seed;
                    return datasetRepo.createDataset(name, n, seed, sigma, points);
                }
            };

            task.setOnSucceeded(ev -> {
                long id = task.getValue();
                status.setText("Dataset created: id=" + id);
                genBtn.setDisable(false);

                reloadDatasets();

                for (DatasetInfo di : datasetList.getItems()) {
                    if (di.id() == id) {
                        datasetList.getSelectionModel().select(di);
                        break;
                    }
                }
            });

            task.setOnFailed(ev -> {
                genBtn.setDisable(false);
                Throwable ex = task.getException();
                showError("Generation failed", ex != null ? ex.getMessage() : "Unknown error");
                status.setText("Failed");
            });

            bg.submit(task);
        });

        // Dataset selection
        datasetList.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            // если что-то крутилось — стопаем
            running = false;
            pauseBtn.setDisable(true);
            runBtn.setDisable(false);
            stepBtn.setDisable(false);

            closeSession();
            currentRunId = null;

            resetRunStats();
            sseSeries.getData().clear();
            timeSeries.getData().clear();
            plot.clearClustering();
            iterLabel.setText("iter: -");
            sseLabel.setText("sse: -");
            timeLabel.setText("iter ms: -");

            if (selected == null) {
                currentDatasetId = -1;
                currentPoints = List.of();
                plot.setData(currentPoints, xAxis.getValue(), yAxis.getValue());
                return;
            }

            status.setText("Loading points for dataset id=" + selected.id() + " ...");

            Task<List<PointVector>> loadTask = new Task<>() {
                @Override
                protected List<PointVector> call() {
                    return datasetRepo.loadPoints(selected.id());
                }
            };

            loadTask.setOnSucceeded(ev -> {
                currentPoints = loadTask.getValue();
                currentDatasetId = selected.id();
                plot.setData(currentPoints, xAxis.getValue(), yAxis.getValue());
                status.setText("Loaded " + currentPoints.size() + " points (dataset id=" + selected.id() + ")");
            });

            loadTask.setOnFailed(ev -> {
                Throwable ex = loadTask.getException();
                showError("Load failed", ex != null ? ex.getMessage() : "Unknown error");
                status.setText("Failed to load dataset");
            });

            bg.submit(loadTask);
        });

        // Reset
        resetBtn.setOnAction(e -> {
            running = false;

            closeSession();
            currentRunId = null;

            plot.clearClustering();

            resetRunStats();
            sseSeries.getData().clear();
            timeSeries.getData().clear();

            iterLabel.setText("iter: -");
            sseLabel.setText("sse: -");
            timeLabel.setText("iter ms: -");
            status.setText("Reset");

            runBtn.setDisable(false);
            stepBtn.setDisable(false);
            pauseBtn.setDisable(true);
        });

        // Pause (не закрываем session — можно продолжить)
        pauseBtn.setOnAction(e -> {
            running = false;
            pauseBtn.setDisable(true);
            runBtn.setDisable(false);
            stepBtn.setDisable(false);
            status.setText("Paused");
        });

        // Step
        stepBtn.setOnAction(e -> {
            if (running) return;

            if (currentDatasetId < 0 || currentPoints.isEmpty()) {
                showError("No dataset", "Select dataset first.");
                return;
            }

            RunParams p = parseRunParams(modeBox, kField, maxIterField, epsField, threadsField);
            if (p == null) return;

            if (session == null) {
                startNewRun(p, sseSeries, timeSeries);
                plot.clearClustering();
            }

            final long runId = currentRunId;

            Task<IterationSnapshot> t = new Task<>() {
                @Override
                protected IterationSnapshot call() {
                    IterationSnapshot s = session.step();
                    metricsRepo.insertIterMetrics(runId, s.iter(), s.sse(), s.assignMs(), s.updateMs(), s.totalMs());
                    return s;
                }
            };

            t.setOnSucceeded(ev -> {
                IterationSnapshot s = t.getValue();

                iterCount++;
                sumIterMs += s.totalMs();
                sumAssignMs += s.assignMs();
                sumUpdateMs += s.updateMs();

                plot.setClustering(s.assignment(), s.centroids());
                iterLabel.setText("iter: " + s.iter());
                sseLabel.setText(String.format("sse: %.6f", s.sse()));
                timeLabel.setText(String.format("iter ms: %.2f (assign %.2f / update %.2f)",
                        s.totalMs(), s.assignMs(), s.updateMs()));

                sseSeries.getData().add(new XYChart.Data<>(s.iter(), s.sse()));
                timeSeries.getData().add(new XYChart.Data<>(s.iter(), s.totalMs()));

                if (s.stopReason() != null) {
                    status.setText("Finished: " + s.stopReason());
                    finalizeRun(runId, s);

                    closeSession();
                    currentRunId = null;

                    runBtn.setDisable(false);
                    stepBtn.setDisable(false);
                    pauseBtn.setDisable(true);
                }
            });

            t.setOnFailed(ev -> {
                Throwable ex = t.getException();
                showError("Step failed", ex != null ? ex.getMessage() : "Unknown error");
                status.setText("Step failed");

                closeSession();
                currentRunId = null;
            });

            bg.submit(t);
        });

        // Run
        runBtn.setOnAction(e -> {
            if (running) return;

            if (currentDatasetId < 0 || currentPoints.isEmpty()) {
                showError("No dataset", "Select dataset first.");
                return;
            }

            RunParams p = parseRunParams(modeBox, kField, maxIterField, epsField, threadsField);
            if (p == null) return;

            if (session == null) {
                startNewRun(p, sseSeries, timeSeries);
                plot.clearClustering();
            }

            final long runId = currentRunId;
            final RunMode runMode = p.mode();

            running = true;
            runBtn.setDisable(true);
            stepBtn.setDisable(true);
            pauseBtn.setDisable(false);

            Task<Void> runTask = new Task<>() {
                @Override
                protected Void call() {
                    while (running) {
                        IterationSnapshot s = session.step();
                        metricsRepo.insertIterMetrics(runId, s.iter(), s.sse(), s.assignMs(), s.updateMs(), s.totalMs());

                        iterCount++;
                        sumIterMs += s.totalMs();
                        sumAssignMs += s.assignMs();
                        sumUpdateMs += s.updateMs();

                        long now = System.nanoTime();
                        boolean demo = (runMode == RunMode.DEMO);
                        boolean timeToUpdate = demo
                                || lastUiUpdateNano == 0L
                                || (now - lastUiUpdateNano) > UI_THROTTLE_NS;

                        if (timeToUpdate || s.stopReason() != null) {
                            lastUiUpdateNano = now;
                            Platform.runLater(() -> {
                                plot.setClustering(s.assignment(), s.centroids());

                                iterLabel.setText("iter: " + s.iter());
                                sseLabel.setText(String.format("sse: %.6f", s.sse()));
                                timeLabel.setText(String.format("iter ms: %.2f (assign %.2f / update %.2f)",
                                        s.totalMs(), s.assignMs(), s.updateMs()));

                                sseSeries.getData().add(new XYChart.Data<>(s.iter(), s.sse()));
                                timeSeries.getData().add(new XYChart.Data<>(s.iter(), s.totalMs()));
                            });
                        }

                        if (s.stopReason() != null) {
                            Platform.runLater(() -> status.setText("Finished: " + s.stopReason()));
                            Platform.runLater(() -> finalizeRun(runId, s));
                            break;
                        }

                        if (demo) {
                            try { Thread.sleep(120); } catch (InterruptedException ignored) {}
                        }
                    }
                    return null;
                }
            };

            runTask.setOnSucceeded(ev -> {
                running = false;

                runBtn.setDisable(false);
                stepBtn.setDisable(false);
                pauseBtn.setDisable(true);

                closeSession();
                currentRunId = null;
            });

            runTask.setOnFailed(ev -> {
                running = false;

                runBtn.setDisable(false);
                stepBtn.setDisable(false);
                pauseBtn.setDisable(true);

                Throwable ex = runTask.getException();
                showError("Run failed", ex != null ? ex.getMessage() : "Unknown error");
                status.setText("Run failed");

                closeSession();
                currentRunId = null;
            });

            bg.submit(runTask);
        });

        // ---------- initial load ----------
        reloadDatasets();
        if (!datasetList.getItems().isEmpty()) {
            datasetList.getSelectionModel().select(0);
        } else {
            plot.setData(List.of(), xAxis.getValue(), yAxis.getValue());
        }

        stage.setTitle("ROSL");
        stage.setScene(new Scene(root, 1100, 750));
        stage.show();
    }

    private RunParams parseRunParams(ComboBox<RunMode> modeBox,
                                     TextField kField, TextField maxIterField,
                                     TextField epsField, TextField threadsField) {
        try {
            RunMode mode = modeBox.getValue();
            int k = Integer.parseInt(kField.getText().trim());
            int maxIter = Integer.parseInt(maxIterField.getText().trim());
            double eps = Double.parseDouble(epsField.getText().trim());
            int threads = Integer.parseInt(threadsField.getText().trim());

            if (k <= 1) throw new IllegalArgumentException("K must be >= 2");
            if (maxIter <= 0) throw new IllegalArgumentException("MaxIter must be > 0");
            if (eps <= 0) throw new IllegalArgumentException("Eps must be > 0");
            if (threads <= 0) throw new IllegalArgumentException("Threads must be > 0");

            return new RunParams(mode, k, maxIter, eps, threads);
        } catch (Exception ex) {
            showError("Bad run params", "Check Mode/K/MaxIter/Eps/Threads.\n" + ex.getMessage());
            return null;
        }
    }

    private void startNewRun(RunParams p,
                             XYChart.Series<Number, Number> sseSeries,
                             XYChart.Series<Number, Number> timeSeries) {
        resetRunStats();
        runStartNano = System.nanoTime();
        sseSeries.getData().clear();
        timeSeries.getData().clear();

        // создаём session + запись run
        session = new KMeansSession(currentPoints, p.k(), p.maxIter(), p.eps(), 12345L, p.threads());
        currentRunId = runRepo.createRun(currentDatasetId, p.mode(), p.k(), p.threads(), p.maxIter(), p.eps());

        status.setText("Run created: id=" + currentRunId);
    }

    private void finalizeRun(long runId, IterationSnapshot last) {
        // results
        resultRepo.saveCentroids(runId, last.centroids());
        resultRepo.saveAssignments(runId, last.assignment());

        // cluster_metrics
        var cm = ClusterMetricsCalc.compute(currentPoints, last.assignment(), last.centroids());
        metricsRepo.saveClusterMetrics(runId, cm.size(), cm.clusterSse(), cm.avgDist(), cm.maxDist());

        // run_metrics
        long totalMs = runStartNano == 0L ? 0L : Math.round((System.nanoTime() - runStartNano) / 1_000_000.0);
        double avgIter = iterCount == 0 ? 0.0 : (sumIterMs / iterCount);
        double avgAssign = iterCount == 0 ? 0.0 : (sumAssignMs / iterCount);
        double avgUpdate = iterCount == 0 ? 0.0 : (sumUpdateMs / iterCount);

        metricsRepo.insertRunMetrics(
                runId,
                totalMs,
                last.iter(),
                last.sse(),
                avgIter,
                avgAssign,
                avgUpdate
        );

        runRepo.finishRun(runId, last.stopReason() != null ? last.stopReason() : "FINISHED");

        resetRunStats();
    }

    private void resetRunStats() {
        runStartNano = 0L;
        sumIterMs = 0.0;
        sumAssignMs = 0.0;
        sumUpdateMs = 0.0;
        iterCount = 0;
        lastUiUpdateNano = 0L;
    }

    private void reloadDatasets() {
        var items = FXCollections.observableArrayList(datasetRepo.listDatasets());
        datasetList.setItems(items);
    }

    private void showError(String title, String msg) {
        Alert a = new Alert(Alert.AlertType.ERROR);
        a.setTitle(title);
        a.setHeaderText(title);
        a.setContentText(msg);
        a.showAndWait();
    }

    private void closeSession() {
        if (session != null) {
            try { session.close(); } catch (Exception ignored) {}
            session = null;
        }
    }

    @Override
    public void stop() {
        running = false;
        closeSession();
        bg.shutdownNow();
        Platform.exit();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
