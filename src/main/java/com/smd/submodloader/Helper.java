package com.smd.submodloader;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

public class Helper {

    private static final Path GAME_DIR = Paths.get("").toAbsolutePath().normalize();

    private static List<String> dirs = List.of("mods/submods");

    static void loadConfig() {
        Path configPath = GAME_DIR.resolve("submodloader.properties");
        Properties props = new Properties();

        if (Files.exists(configPath)) {
            try (var reader = Files.newBufferedReader(configPath)) {
                props.load(reader);
                System.out.println("[SubModLoader] Config loaded: " + configPath);
            } catch (IOException e) {
                System.err.println("[SubModLoader] Config read failed, using defaults.");
            }
        } else {
            props.setProperty("dirs", "mods/submods");
            try (var writer = Files.newBufferedWriter(configPath)) {
                props.store(writer, "SubModLoader Configuration");
                System.out.println("[SubModLoader] Default config created: " + configPath);
            } catch (IOException e) {
                System.err.println("[SubModLoader] Config create failed.");
            }
        }

        dirs = parseList(props.getProperty("dirs", "mods/submods"));
        System.out.println("[SubModLoader] dirs=" + dirs);
    }

    @SuppressWarnings("unused")
    public static String[] expandLegacyModDirs(String[] originalDirs) {
        if (originalDirs == null || dirs.isEmpty()) {
            return originalDirs;
        }

        Set<String> expanded = new LinkedHashSet<>();
        for (String dir : originalDirs) {
            if (dir != null && !dir.isBlank()) {
                expanded.add(normalizeDir(dir));
            }
        }

        int before = expanded.size();
        for (String dir : dirs) {
            if (dir != null && !dir.isBlank()) {
                expanded.add(normalizeDir(dir));
            }
        }

        String[] result = expanded.toArray(String[]::new);
        if (result.length != before) {
            System.out.println("[SubModLoader] Legacy mod directories expanded: "
                    + String.join(", ", result));
        }
        return result;
    }

    private static List<String> parseList(String value) {
        List<String> result = new ArrayList<>();
        for (String s : value.split(",")) {
            String trimmed = s.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    private static String normalizeDir(String dir) {
        return dir.replace('/', File.separatorChar).replace('\\', File.separatorChar);
    }
}
