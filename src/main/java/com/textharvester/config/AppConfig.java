package com.textharvester.config;

import lombok.Data;

import java.util.List;

@Data
public class AppConfig {
    private AppSettings app;

    @Data
    public static class AppSettings {
        private List<ModeOption> modes;
        private String defaultMode;
        private String singlePageUrl;
        private List<String> listPageUrls;
        private String outputDir;
        private int maxItems;
        private String userAgent;
        private int timeoutSeconds;
        private boolean dryRun;
    }

    @Data
    public static class ModeOption {
        private String name;
        private String description;
    }
}
