package com.textharvester.ui;

import com.textharvester.config.AppConfig;
import com.textharvester.config.ConfigLoader;
import com.textharvester.log.AppLogger;
import com.textharvester.service.ParseService;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.nio.file.Path;

public class MainApp extends Application {
    private final ParseService parseService = new ParseService();

    @Override
    public void start(Stage stage) throws Exception {
        ConfigLoader loader = new ConfigLoader(Path.of("config.yaml"));
        AppConfig config = loader.load();

        ComboBox<String> modeBox = new ComboBox<>();
        modeBox.getItems().addAll(config.getApp().getModes());
        modeBox.setValue(config.getApp().getDefaultMode());

        ListView<String> pagesList = new ListView<>();
        if (config.getApp().getListPageUrls() != null) {
            pagesList.getItems().addAll(config.getApp().getListPageUrls());
        }
        pagesList.setPrefHeight(140);

        Button startButton = new Button("Start");

        TextArea logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setWrapText(true);

        AppLogger.setUiSink(line -> Platform.runLater(() -> {
            logArea.appendText(line + System.lineSeparator());
        }));

        startButton.setOnAction(evt -> {
            String mode = modeBox.getValue();
            AppLogger.info("Selected mode: " + mode);
            if (mode == null || mode.isBlank()) {
                AppLogger.warn("Mode not selected");
                return;
            }
            switch (mode) {
                case "single" -> parseService.runSingle(config.getApp());
                case "list" -> parseService.runList(config.getApp());
                case "build-site-list" -> parseService.buildSiteList(config.getApp());
                default -> AppLogger.warn("Unknown mode: " + mode);
            }
        });

        VBox topBox = new VBox(10,
                labeledRow("Mode:", modeBox),
                labeledRow("Pages:", pagesList),
                startButton
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

    private HBox labeledRow(String label, Control control) {
        Label l = new Label(label);
        l.setMinWidth(80);
        HBox box = new HBox(10, l, control);
        HBox.setHgrow(control, Priority.ALWAYS);
        return box;
    }
}
