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
import org.example.ui.ResultsWindow;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainApp extends Application {

    private static final long UI_THROTTLE_NS = 400_000_000L; // 400ms
    private static final int BENCH_SAMPLE_LIMIT = 30_000;

    private final DatasetRepository datasetRepo = new DatasetRepository();
    private final RunRepository runRepo = new RunRepository();
    private final ResultRepository resultRepo = new ResultRepository();
    private final MetricsRepository metricsRepo = new MetricsRepository();

    private volatile KMeansSession session = null;
    private volatile long currentDatasetId = -1;
    private volatile long currentRunId = -1; // no Long/null

    // run stats
    private volatile long runStartNano = 0L;
    private volatile double sumIterMs = 0.0;
    private volatile double sumAssignMs = 0.0;
    private volatile double sumUpdateMs = 0.0;
    private volatile int iterCount = 0;

    private volatile long lastUiUpdateNano = 0L;
    private volatile boolean running = false;

    // UI owner
    private Stage primaryStage;
    private volatile RunParams lastRunParams = null;

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
        this.primaryStage = stage;

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

        Button delBtn = new Button("Delete selected");
        delBtn.setMaxWidth(Double.MAX_VALUE);
        delBtn.setDisable(true);

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

        left.getChildren().addAll(leftTitle, form, genBtn, delBtn, new Separator(), datasetList);
        VBox.setVgrow(datasetList, Priority.ALWAYS);

        // ---------- CENTER ----------
        PlotCanvas plot = new PlotCanvas(700, 650);

        ComboBox<Feature> xAxis = new ComboBox<>();
        xAxis.getItems().setAll(Feature.values());
        xAxis.setValue(Feature.LIKES_SPORTS);

        ComboBox<Feature> yAxis = new ComboBox<>();
        yAxis.getItems().setAll(Feature.values());
        yAxis.setValue(Feature.LIKES_GAMES);

        javafx.util.StringConverter<Feature> conv = new javafx.util.StringConverter<>() {
            @Override public String toString(Feature f) { return (f == null) ? "" : f.column; }
            @Override public Feature fromString(String s) { return null; }
        };
        xAxis.setConverter(conv);
        yAxis.setConverter(conv);

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
        Label drawLabel = new Label("draw: -");

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
                iterLabel, sseLabel, timeLabel, drawLabel,
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

        // ---------- Sampling mode wiring ----------
        applySampleMode(plot, modeBox.getValue());
        updateDrawLabel(plot, drawLabel);

        modeBox.valueProperty().addListener((obs, old, mode) -> {
            applySampleMode(plot, mode);
            updateDrawLabel(plot, drawLabel);
        });

        // ---------- Actions ----------
        xAxis.valueProperty().addListener((obs, o, n) -> {
            plot.setData(currentPoints, xAxis.getValue(), yAxis.getValue());
            updateDrawLabel(plot, drawLabel);
        });

        yAxis.valueProperty().addListener((obs, o, n) -> {
            plot.setData(currentPoints, xAxis.getValue(), yAxis.getValue());
            updateDrawLabel(plot, drawLabel);
        });

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
                Throwable rootCause = ex;
                while (rootCause != null && rootCause.getCause() != null) rootCause = rootCause.getCause();
                if (ex != null) ex.printStackTrace();

                showError("Generation failed", rootCause != null ? rootCause.toString() : "Unknown error");
                status.setText("Failed");
            });

            bg.submit(task);
        });

        // Delete selected dataset
        delBtn.setOnAction(e -> {
            DatasetInfo selected = datasetList.getSelectionModel().getSelectedItem();
            if (selected == null) return;

            running = false;
            pauseBtn.setDisable(true);
            runBtn.setDisable(false);
            stepBtn.setDisable(false);

            closeSession();
            currentRunId = -1;
            lastRunParams = null;

            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.setTitle("Delete dataset");
            confirm.setHeaderText("Delete dataset #" + selected.id() + "?");
            confirm.setContentText("This will delete points, runs, metrics and results for this dataset.");
            var res = confirm.showAndWait();
            if (res.isEmpty() || res.get() != ButtonType.OK) return;

            delBtn.setDisable(true);
            status.setText("Deleting dataset id=" + selected.id() + " ...");

            Task<Boolean> t = new Task<>() {
                @Override
                protected Boolean call() {
                    return datasetRepo.deleteDataset(selected.id());
                }
            };

            t.setOnSucceeded(ev -> {
                delBtn.setDisable(false);

                boolean ok = Boolean.TRUE.equals(t.getValue());
                status.setText(ok ? "Deleted dataset #" + selected.id() : "Dataset not found");

                reloadDatasets();

                datasetList.getSelectionModel().clearSelection();
                currentDatasetId = -1;
                currentPoints = List.of();

                plot.clearClustering();
                plot.setData(List.of(), xAxis.getValue(), yAxis.getValue());
                updateDrawLabel(plot, drawLabel);
            });

            t.setOnFailed(ev -> {
                delBtn.setDisable(false);
                Throwable ex = t.getException();
                showError("Delete failed", ex != null ? ex.getMessage() : "Unknown error");
                status.setText("Delete failed");
            });

            bg.submit(t);
        });

        // Dataset selection
        datasetList.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            delBtn.setDisable(selected == null);

            running = false;
            pauseBtn.setDisable(true);
            runBtn.setDisable(false);
            stepBtn.setDisable(false);

            closeSession();
            currentRunId = -1;
            lastRunParams = null;

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
                updateDrawLabel(plot, drawLabel);
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

                plot.setSampleSeed(selected.id());
                applySampleMode(plot, modeBox.getValue());

                plot.setData(currentPoints, xAxis.getValue(), yAxis.getValue());
                updateDrawLabel(plot, drawLabel);

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
            currentRunId = -1;
            lastRunParams = null;

            plot.clearClustering();

            resetRunStats();
            sseSeries.getData().clear();
            timeSeries.getData().clear();

            iterLabel.setText("iter: -");
            sseLabel.setText("sse: -");
            timeLabel.setText("iter ms: -");

            updateDrawLabel(plot, drawLabel);
            status.setText("Reset");

            runBtn.setDisable(false);
            stepBtn.setDisable(false);
            pauseBtn.setDisable(true);
        });

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
                try {
                    startNewRun(p, sseSeries, timeSeries);
                    plot.clearClustering();
                } catch (Exception ex) {
                    showError("Run init failed", ex.getMessage() == null ? ex.toString() : ex.getMessage());
                    closeSession();
                    currentRunId = -1;
                    lastRunParams = null;
                    return;
                }
            }

            final long runId = currentRunId;
            if (runId < 0) {
                showError("Run not created", "currentRunId is invalid");
                return;
            }

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

                    // Step handler runs on FX thread -> safe
                    finalizeRun(runId, s, lastRunParams, currentDatasetId, currentPoints);

                    closeSession();
                    currentRunId = -1;
                    lastRunParams = null;

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
                currentRunId = -1;
                lastRunParams = null;
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
                try {
                    startNewRun(p, sseSeries, timeSeries);
                    plot.clearClustering();
                } catch (Exception ex) {
                    showError("Run init failed", ex.getMessage() == null ? ex.toString() : ex.getMessage());
                    closeSession();
                    currentRunId = -1;
                    lastRunParams = null;
                    return;
                }
            }

            final long runId = currentRunId;
            if (runId < 0) {
                showError("Run not created", "currentRunId is invalid");
                return;
            }

            final RunMode runMode = p.mode();

            // ---- SNAPSHOTS (fix ResultsWindow missing in DEMO/Run) ----
            final RunParams pSnapshot = (lastRunParams != null) ? lastRunParams : p;
            final long datasetIdSnapshot = currentDatasetId;
            final List<PointVector> pointsSnapshot = currentPoints;

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
                            Platform.runLater(() -> {
                                status.setText("Finished: " + s.stopReason());
                                finalizeRun(runId, s, pSnapshot, datasetIdSnapshot, pointsSnapshot);
                            });
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
                currentRunId = -1;
                lastRunParams = null;
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
                currentRunId = -1;
                lastRunParams = null;
            });

            bg.submit(runTask);
        });

        // ---------- initial load ----------
        reloadDatasets();
        if (!datasetList.getItems().isEmpty()) {
            datasetList.getSelectionModel().select(0);
        } else {
            plot.setData(List.of(), xAxis.getValue(), yAxis.getValue());
            updateDrawLabel(plot, drawLabel);
        }

        stage.setTitle("ROSL");
        stage.setScene(new Scene(root, 1100, 750));
        stage.show();
    }

    private void applySampleMode(PlotCanvas plot, RunMode mode) {
        if (mode == RunMode.BENCHMARK) plot.setSampleLimit(BENCH_SAMPLE_LIMIT);
        else plot.setSampleLimit(0);
    }

    private void updateDrawLabel(PlotCanvas plot, Label drawLabel) {
        int total = plot.getTotalCount();
        if (total <= 0) {
            drawLabel.setText("draw: -");
        } else {
            drawLabel.setText("draw: " + plot.getDrawCount() + "/" + total);
        }
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

        session = new KMeansSession(currentPoints, p.k(), p.maxIter(), p.eps(), 12345L, p.threads());

        long rid = runRepo.createRun(currentDatasetId, p.mode(), p.k(), p.threads(), p.maxIter(), p.eps());
        if (rid <= 0) throw new IllegalStateException("RunRepository.createRun returned invalid id: " + rid);

        currentRunId = rid;
        lastRunParams = p;

        status.setText("Run created: id=" + currentRunId);
    }

    // Wrapper (kept for compatibility)
    private void finalizeRun(long runId, IterationSnapshot last) {
        finalizeRun(runId, last, lastRunParams, currentDatasetId, currentPoints);
    }

    // Snapshot-based finalize (fix ResultsWindow disappearing)
    private void finalizeRun(long runId, IterationSnapshot last,
                             RunParams p, long datasetId, List<PointVector> points) {

        // 1) Сначала считаем метрики (это чисто в памяти)
        var cm = ClusterMetricsCalc.compute(points, last.assignment(), last.centroids());

        // 2) Сначала показываем окно (чтобы оно НЕ зависело от БД)
        try {
            if (p != null && primaryStage != null) {
                int[] sz = cm.size();
                double[] csse = cm.clusterSse();
                double[] avg = cm.avgDist();
                double[] mx = cm.maxDist();

                var rows = new ArrayList<ResultsWindow.ClusterRow>(sz.length);
                int totalN = points.size();
                for (int i = 0; i < sz.length; i++) {
                    double share = totalN == 0 ? 0.0 : (100.0 * sz[i] / totalN);
                    rows.add(new ResultsWindow.ClusterRow(i, sz[i], share, csse[i], avg[i], mx[i]));
                }

                long totalMs = runStartNano == 0L ? 0L : Math.round((System.nanoTime() - runStartNano) / 1_000_000.0);
                double avgIter = iterCount == 0 ? 0.0 : (sumIterMs / iterCount);
                double avgAssign = iterCount == 0 ? 0.0 : (sumAssignMs / iterCount);
                double avgUpdate = iterCount == 0 ? 0.0 : (sumUpdateMs / iterCount);

                var summary = new ResultsWindow.RunSummary(
                        runId,
                        datasetId,
                        p.mode().name(),
                        p.k(),
                        p.threads(),
                        p.maxIter(),
                        p.eps(),
                        last.stopReason(),
                        totalMs,
                        last.iter(),
                        last.sse(),
                        avgIter,
                        avgAssign,
                        avgUpdate
                );

                // мы и так обычно на FX потоке, но на всякий:
                if (Platform.isFxApplicationThread()) {
                    ResultsWindow.show(primaryStage, summary, rows);
                } else {
                    Platform.runLater(() -> ResultsWindow.show(primaryStage, summary, rows));
                }
            }
        } catch (Exception ex) {
            ex.printStackTrace();
            // окно не смогли показать — не валим всё
        }

        // 3) А уже потом — БД (и всё в try/catch, чтобы не убить поток)
        try {
            resultRepo.saveCentroids(runId, last.centroids());
            resultRepo.saveAssignments(runId, last.assignment());
        } catch (Exception ex) {
            ex.printStackTrace();
        }

        try {
            metricsRepo.saveClusterMetrics(runId, cm.size(), cm.clusterSse(), cm.avgDist(), cm.maxDist());
        } catch (Exception ex) {
            ex.printStackTrace();
        }

        try {
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
        } catch (Exception ex) {
            ex.printStackTrace();
        }

        try {
            runRepo.finishRun(runId, last.stopReason() != null ? last.stopReason() : "FINISHED");
        } catch (Exception ex) {
            ex.printStackTrace();
        }

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
