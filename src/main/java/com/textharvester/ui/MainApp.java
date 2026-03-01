package com.textharvester.ui;

import com.textharvester.config.AppConfig;
import com.textharvester.config.ConfigLoader;
import com.textharvester.log.AppLogger;
import com.textharvester.service.ParseService;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class MainApp extends Application {
    private final ParseService parseService = new ParseService();
    private Task<Void> currentTask;

    @Override
    public void start(Stage stage) throws Exception {
        ConfigLoader loader = new ConfigLoader(Path.of("config.yaml"));
        AppConfig config = loader.load();

        ComboBox<String> modeBox = new ComboBox<>();
        modeBox.getItems().addAll(config.getApp().getModes());
        modeBox.setValue(config.getApp().getDefaultMode());

        ListView<String> pagesList = new ListView<>();
        modeBox.valueProperty().addListener((obs, oldMode, newMode) -> updatePagesList(pagesList, config, newMode));
        updatePagesList(pagesList, config, modeBox.getValue());
        pagesList.setPrefHeight(140);

        Button startButton = new Button("Start");
        Button stopButton = new Button("Stop");
        stopButton.setDisable(true);

        Label statusLabel = new Label("Idle");
        ProgressIndicator progress = new ProgressIndicator();
        progress.setMaxSize(18, 18);
        progress.setVisible(false);

        Label currentLabel = new Label("Current: -");
        Label countLabel = new Label("Processed: 0 | Saved: 0");

        TextArea logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setWrapText(true);

        AtomicBoolean cancelled = new AtomicBoolean(false);
        AtomicInteger processed = new AtomicInteger(0);
        AtomicInteger saved = new AtomicInteger(0);
        AtomicInteger listItemsCount = new AtomicInteger(0);
        AtomicReference<String> currentUrl = new AtomicReference<>("");

        AppLogger.setUiSink(line -> Platform.runLater(() -> {
            logArea.appendText(line + System.lineSeparator());
            countLabel.setText("Processed: " + processed.get() + " | Saved: " + saved.get());
            String url = currentUrl.get();
            currentLabel.setText(url == null || url.isBlank() ? "Current: -" : "Current: " + url);
        }));


        AppLogger.setStatusSupplier(() -> {
            String url = currentUrl.get();
            if (url == null || url.isBlank()) {
                return "";
            }
            return url;
        });

        startButton.setOnAction(evt -> {
            String mode = modeBox.getValue();
            AppLogger.info("Selected mode: " + mode);
            if (mode == null || mode.isBlank()) {
                AppLogger.warn("Mode not selected");
                return;
            }
            if (currentTask != null && currentTask.isRunning()) {
                AppLogger.warn("Task already running");
                return;
            }
            if (mode.equals("single") || mode.equals("build-site-list")) {
                if (config.getApp().getSinglePageUrl() == null || config.getApp().getSinglePageUrl().isBlank()) {
                    AppLogger.warn("singlePageUrl is empty in config");
                    return;
                }
            }
            if (mode.equals("list")) {
                if (config.getApp().getListPageUrls() == null || config.getApp().getListPageUrls().isEmpty()) {
                    AppLogger.warn("listPageUrls is empty in config");
                    return;
                }
            }

            cancelled.set(false);
            processed.set(0);
            saved.set(0);
            listItemsCount.set(0);
            currentUrl.set("");
            startButton.setDisable(true);
            stopButton.setDisable(false);
            progress.setVisible(true);
            statusLabel.setText("Running: " + mode);
            countLabel.setText("Processed: 0 | Saved: 0");
            currentLabel.setText("Current: -");

            currentTask = new Task<>() {
                @Override
                protected Void call() {
                    switch (mode) {
                        case "single" -> parseService.runSingle(config.getApp(), cancelled::get, processed, saved, currentUrl);
                        case "list" -> parseService.runList(config.getApp(), cancelled::get, processed, saved, currentUrl);
                        case "build-site-list" -> parseService.buildSiteList(config.getApp(), cancelled::get, listItemsCount);
                        default -> AppLogger.warn("Unknown mode: " + mode);
                    }
                    return null;
                }
            };

            currentTask.setOnSucceeded(e -> {
                finishTask(statusLabel, progress, startButton, stopButton, "Finished");
                if (mode.equals("build-site-list")) {
                    AppLogger.info("Процесс формирования списка завершен");
                    AppLogger.info("Был создан список из " + listItemsCount.get() + "-элементов");
                } else {
                    AppLogger.info("Процесс парсинга завершен");
                    AppLogger.info("Было создано файлов: " + saved.get());
                }
            });
            currentTask.setOnFailed(e -> finishTask(statusLabel, progress, startButton, stopButton, "Failed"));
            currentTask.setOnCancelled(e -> finishTask(statusLabel, progress, startButton, stopButton, "Cancelled"));

            new Thread(currentTask, "parser-worker").start();
        });

        stopButton.setOnAction(evt -> {
            cancelled.set(true);
            if (currentTask != null) {
                currentTask.cancel();
            }
            AppLogger.warn("Stop requested by user");
        });

        HBox controls = new HBox(10, startButton, stopButton, progress, statusLabel);
        VBox statusBox = new VBox(6, currentLabel, countLabel);

        VBox topBox = new VBox(10,
                labeledRow("Mode:", modeBox),
                labeledRow("Pages:", pagesList),
                controls,
                statusBox
        );
        topBox.setPadding(new Insets(10));

        BorderPane root = new BorderPane();
        root.setTop(topBox);
        root.setCenter(logArea);
        BorderPane.setMargin(logArea, new Insets(10));

        Scene scene = new Scene(root, 900, 600);
        stage.setTitle("TextHarvester Parser");
        stage.setScene(scene);
        stage.show();

        AppLogger.info("Config loaded from config.yaml");
    }

    private void finishTask(Label statusLabel, ProgressIndicator progress, Button startButton, Button stopButton, String status) {
        Platform.runLater(() -> {
            statusLabel.setText(status);
            progress.setVisible(false);
            startButton.setDisable(false);
            stopButton.setDisable(true);
        });
    }

    private HBox labeledRow(String label, Control control) {
        Label l = new Label(label);
        l.setMinWidth(80);
        HBox box = new HBox(10, l, control);
        HBox.setHgrow(control, Priority.ALWAYS);
        return box;
    }

    private void updatePagesList(ListView<String> pagesList, AppConfig config, String mode) {
        pagesList.getItems().clear();
        if (mode == null || mode.isBlank()) {
            return;
        }

        if (mode.equals("single") || mode.equals("build-site-list")) {
            String singlePageUrl = config.getApp().getSinglePageUrl();
            if (singlePageUrl != null && !singlePageUrl.isBlank()) {
                pagesList.getItems().add(singlePageUrl);
            }
            return;
        }

        if (mode.equals("list") && config.getApp().getListPageUrls() != null) {
            pagesList.getItems().addAll(config.getApp().getListPageUrls());
        }
    }
}
