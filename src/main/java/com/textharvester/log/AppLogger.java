package com.textharvester.log;

import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

@Slf4j
public class AppLogger {
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static volatile Consumer<String> uiSink;
    private static volatile Supplier<String> statusSupplier;

    private AppLogger() {
    }

    public static void setUiSink(Consumer<String> sink) {
        uiSink = sink;
    }

    public static void setStatusSupplier(Supplier<String> supplier) {
        statusSupplier = supplier;
    }

    public static void info(String message) {
        log.info(message);
        pushToUi("INFO", message);
    }

    public static void warn(String message) {
        log.warn(message);
        pushToUi("WARN", message);
    }

    public static void error(String message, Throwable t) {
        log.error(message, t);
        pushToUi("ERROR", message + " - " + t.getMessage());
    }

    private static void pushToUi(String level, String message) {
        Consumer<String> sink = uiSink;
        if (Objects.nonNull(sink)) {
            String status = "";
            Supplier<String> supplier = statusSupplier;
            if (supplier != null) {
                String current = supplier.get();
                if (current != null && !current.isBlank()) {
                    status = " [" + current + "]";
                }
            }
            String line = String.format("%s [%s]%s %s", LocalDateTime.now().format(TS), level, status, message);
            sink.accept(line);
        }
    }
}
