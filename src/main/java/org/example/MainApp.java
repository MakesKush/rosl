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

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainApp extends Application {

    private final DatasetRepository datasetRepo = new DatasetRepository();
    private final ExecutorService bg = Executors.newSingleThreadExecutor();

    private final ListView<DatasetInfo> datasetList = new ListView<>();
    private final Label status = new Label("Ready");

    @Override
    public void start(Stage stage) {
        // 1) init DB (создаст ~/.local/share/rosl/appdb.mv.db)
        Database.init();

        // 2) layout
        BorderPane root = new BorderPane();
        root.setPadding(new Insets(10));

        // LEFT: controls + dataset list
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
                    setText("#" + item.id() + "  " + item.name()
                            + "\nN=" + item.n() + " D=" + item.d()
                            + (item.createdAt() != null ? "\n" + item.createdAt() : ""));
                }
            }
        });

        left.getChildren().addAll(leftTitle, form, genBtn, new Separator(), datasetList);
        VBox.setVgrow(datasetList, Priority.ALWAYS);

        // CENTER: пока заглушка (далее будет scatter plot)
        StackPane center = new StackPane(new Label("Plot area (next step)"));
        center.setStyle("-fx-background-color: #f4f4f4; -fx-border-color: #ddd;");
        center.setMinSize(400, 400);

        // BOTTOM: status bar
        HBox bottom = new HBox(status);
        bottom.setPadding(new Insets(8, 0, 0, 0));

        root.setLeft(left);
        root.setCenter(center);
        root.setBottom(bottom);

        // 3) actions
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

            Task<Void> task = new Task<>() {
                @Override
                protected Void call() {
                    var points = DataGenerator.generate(n, seed, 4, sigma);
                    String name = "gen_N" + n + "_seed" + seed;
                    long id = datasetRepo.createDataset(name, n, seed, sigma, points);
                    Platform.runLater(() -> status.setText("Dataset created: id=" + id));
                    return null;
                }
            };

            task.setOnSucceeded(ev -> {
                reloadDatasets();
                genBtn.setDisable(false);
            });
            task.setOnFailed(ev -> {
                genBtn.setDisable(false);
                Throwable ex = task.getException();
                showError("Generation failed", ex != null ? ex.getMessage() : "Unknown error");
                status.setText("Failed");
            });

            bg.submit(task);
        });

        // 4) initial load
        reloadDatasets();

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
    }

    public static void main(String[] args) {
        launch(args);
    }
}
