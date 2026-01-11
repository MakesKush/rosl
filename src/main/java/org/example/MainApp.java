package org.example;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import org.example.core.DataGenerator;
import org.example.db.Database;
import org.example.db.DatasetRepository;
import org.example.model.DatasetInfo;
import org.example.model.Feature;
import org.example.model.PointVector;
import org.example.ui.PlotCanvas;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainApp extends Application {

    private final DatasetRepository datasetRepo = new DatasetRepository();

    // Один фоновой поток достаточно (генерация/загрузка), позже можно расширить
    private final ExecutorService bg = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "bg-worker");
        t.setDaemon(true);
        return t;
    });

    private final ListView<DatasetInfo> datasetList = new ListView<>();
    private final Label status = new Label("Ready");

    private volatile List<PointVector> currentPoints = List.of();

    @Override
    public void start(Stage stage) {
        // 1) init DB
        Database.init();

        // 2) Layout
        BorderPane root = new BorderPane();
        root.setPadding(new Insets(10));

        // ---------- LEFT: datasets panel ----------
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
                if (empty || item == null) {
                    setText(null);
                } else {
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

        // ---------- CENTER: plot + axis selectors ----------
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

        // ---------- BOTTOM: status ----------
        HBox bottom = new HBox(status);
        bottom.setPadding(new Insets(8, 0, 0, 0));

        root.setLeft(left);
        root.setCenter(center);
        root.setBottom(bottom);

        // 3) Actions

        // Перерисовать при смене осей
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
                if (n <= 0) throw new NumberFormatException("n <= 0");
                if (sigma <= 0) throw new NumberFormatException("sigma <= 0");
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

                // выбрать созданный датасет (если найден)
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

        // При выборе датасета — загрузить точки и нарисовать
        datasetList.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            if (selected == null) {
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
                var pts = loadTask.getValue();
                currentPoints = pts;
                plot.setData(currentPoints, xAxis.getValue(), yAxis.getValue());
                status.setText("Loaded " + pts.size() + " points (dataset id=" + selected.id() + ")");
            });

            loadTask.setOnFailed(ev -> {
                Throwable ex = loadTask.getException();
                showError("Load failed", ex != null ? ex.getMessage() : "Unknown error");
                status.setText("Failed");
            });

            bg.submit(loadTask);
        });

        // 4) initial load
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

    @Override
    public void stop() {
        bg.shutdownNow();
        Platform.exit();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
