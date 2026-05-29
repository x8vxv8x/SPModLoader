package com.smd.submodloader;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/**
 * 统一的扫描 + 注入逻辑。
 * 所有方法通过字节码注入静态调用，不依赖 Forge/Cleanroom 类。
 */
public class Helper {

    static List<String> dirs = List.of("mods/submods");
    static int depth = 0;
    static List<String> ignore = List.of();

    // 反射缓存：调用父类 URLClassLoader.addURL()，绕过 ActualClassLoader 的 override
    private static final Method ADD_URL;
    static {
        try {
            ADD_URL = URLClassLoader.class.getDeclaredMethod("addURL", URL.class);
            ADD_URL.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    // ─── 配置加载（premain 阶段调用） ───

    static void loadConfig() {
        Path gameDir = Paths.get("").toAbsolutePath().normalize();
        Path configPath = gameDir.resolve("submodloader.properties");
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
            props.setProperty("depth", "0");
            props.setProperty("ignore", "");
            try (var writer = Files.newBufferedWriter(configPath)) {
                props.store(writer, "SubModLoader Configuration");
                System.out.println("[SubModLoader] Default config created: " + configPath);
            } catch (IOException e) {
                System.err.println("[SubModLoader] Config create failed.");
            }
        }

        dirs = parseList(props.getProperty("dirs", "mods/submods"));
        ignore = parseList(props.getProperty("ignore", ""));
        try {
            depth = Integer.parseInt(props.getProperty("depth", "0"));
        } catch (NumberFormatException e) {
            depth = 0;
        }

        System.out.println("[SubModLoader] dirs=" + dirs + " depth=" + depth + " ignore=" + ignore);
    }

    private static List<String> parseList(String value) {
        List<String> result = new ArrayList<>();
        for (String s : value.split(",")) {
            String trimmed = s.trim();
            if (!trimmed.isEmpty()) result.add(trimmed);
        }
        return result;
    }

    // ─── 统一扫描 ───

    static List<File> scanJars(Path base, List<String> subDirs, int maxDepth, List<String> ignoreKeywords) {
        List<File> result = new ArrayList<>();
        for (String sub : subDirs) {
            Path dir = base.resolve(sub).normalize();
            if (!Files.isDirectory(dir)) continue;
            int d = maxDepth < 0 ? Integer.MAX_VALUE : maxDepth;
            try {
                Files.walkFileTree(dir, EnumSet.noneOf(FileVisitOption.class), d,
                        new SimpleFileVisitor<>() {
                            @Override
                            public FileVisitResult preVisitDirectory(Path p, BasicFileAttributes attrs) {
                                String name = p.getFileName().toString().toLowerCase();
                                if (ignoreKeywords.stream().anyMatch(name::contains))
                                    return FileVisitResult.SKIP_SUBTREE;
                                return FileVisitResult.CONTINUE;
                            }

                            @Override
                            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                                if (file.toString().endsWith(".jar"))
                                    result.add(file.toFile());
                                return FileVisitResult.CONTINUE;
                            }
                        });
            } catch (IOException e) {
                System.err.println("[SubModLoader] Scan error: " + dir + " - " + e.getMessage());
            }
        }
        return result;
    }

    // ─── 注入点 1：ActualClassLoader 构造函数调用 ───

    /**
     * 在 ActualClassLoader 构造函数中（super() 之后）调用。
     * 通过反射调用父类 URLClassLoader.addURL()，绕过 ActualClassLoader 的 override，
     * 避免访问尚未初始化的 this.sources 字段。
     */
    public static void injectURLs(Object classLoader) {
        List<File> jars = scanJars(Paths.get("").toAbsolutePath(), dirs, depth, ignore);
        if (jars.isEmpty()) return;
        System.out.println("[SubModLoader] Injecting " + jars.size() + " URLs into classloader...");
        for (File jar : jars) {
            try {
                ADD_URL.invoke(classLoader, jar.toURI().toURL());
                System.out.println("[SubModLoader]   + " + jar.getName());
            } catch (Exception e) {
                System.err.println("[SubModLoader] Failed to inject: " + jar + " - " + e.getMessage());
            }
        }
    }

    // ─── 注入点 2：CoreModManager.discoverCoreMods 调用 ───

    /**
     * 在 CoreModManager.discoverCoreMods() 中 getCandidates() 返回后调用。
     * 向候选列表追加额外目录的 jar，后续循环会自动处理 CoreMod 属性。
     */
    @SuppressWarnings("unused")
    public static void addCandidates(List<File> candidates, File mcDir) {
        List<File> extra = scanJars(mcDir.toPath(), dirs, depth, ignore);
        if (extra.isEmpty()) return;
        int added = 0;
        for (File jar : extra) {
            if (!candidates.contains(jar)) {
                candidates.add(jar);
                added++;
                System.out.println("[SubModLoader] Candidate: " + jar.getName());
            }
        }
        if (added > 0) System.out.println("[SubModLoader] Added " + added + " extra candidates.");
    }
}
