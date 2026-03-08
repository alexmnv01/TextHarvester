package com.textharvester.config;

import lombok.extern.slf4j.Slf4j;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Representer;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

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

    public void save(AppConfig config) throws IOException {
        if (config == null || config.getApp() == null) {
            throw new IOException("Invalid config: missing 'app' section");
        }
        Path target = resolveConfigPath(configPath);
        if (target == null) {
            target = configPath;
        }
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setIndent(2);

        Representer representer = new Representer(options);
        representer.addClassTag(AppConfig.class, Tag.MAP);
        representer.addClassTag(AppConfig.AppSettings.class, Tag.MAP);

        Yaml yaml = new Yaml(representer, options);
        try (BufferedWriter writer = Files.newBufferedWriter(
                target,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        )) {
            yaml.dump(config, writer);
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
