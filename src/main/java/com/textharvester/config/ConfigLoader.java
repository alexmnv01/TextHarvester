package com.textharvester.config;

import lombok.extern.slf4j.Slf4j;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.LoaderOptions;

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
        Path resolved = resolveConfigPath(configPath);
        if (resolved == null) {
            throw new IOException("Config file not found: " + configPath.toAbsolutePath());
        }
        Yaml yaml = new Yaml(new Constructor(AppConfig.class, new LoaderOptions()));
        try (InputStream in = Files.newInputStream(resolved)) {
            AppConfig config = yaml.load(in);
            if (config == null || config.getApp() == null) {
                throw new IOException("Invalid config structure: missing 'app' section");
            }
            return config;
        }
    }

    private Path resolveConfigPath(Path original) {
        if (Files.exists(original)) {
            return original;
        }
        Path cwd = Path.of(System.getProperty("user.dir"));
        Path javaHome = Path.of(System.getProperty("java.home"));

        Path[] candidates = new Path[] {
                cwd.resolve(original),
                cwd.resolve("config.yaml"),
                cwd.getParent() != null ? cwd.getParent().resolve("config.yaml") : null,
                javaHome.resolve("config.yaml"),
                javaHome.getParent() != null ? javaHome.getParent().resolve("config.yaml") : null
        };

        for (Path p : candidates) {
            if (p != null && Files.exists(p)) {
                return p;
            }
        }
        return null;
    }
}
