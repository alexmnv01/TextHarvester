package com.textharvester.config;

import lombok.extern.slf4j.Slf4j;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
public class ConfigLoader {
    private final Path configPath;

    public ConfigLoader(Path configPath) {
        this.configPath = configPath;
    }

    public AppConfig load() throws IOException {
        if (!Files.exists(configPath)) {
            throw new IOException("Config file not found: " + configPath.toAbsolutePath());
        }
        Yaml yaml = new Yaml(new Constructor(AppConfig.class));
        try (InputStream in = Files.newInputStream(configPath)) {
            AppConfig config = yaml.load(in);
            if (config == null || config.getApp() == null) {
                throw new IOException("Invalid config structure: missing 'app' section");
            }
            return config;
        }
    }
}
