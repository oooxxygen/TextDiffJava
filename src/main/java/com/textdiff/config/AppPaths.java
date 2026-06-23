package com.textdiff.config;

import java.nio.file.*;

/** 可写目录（baseDir，运行位置同级）与只读打包资源（bundleDir）解析。对等 Python apppaths.py。 */
public record AppPaths(Path baseDir, Path bundleDir) {

    public static AppPaths detect() {
        Path base = resolveBase();
        Path bundle = base; // 开发态与 bundle 同根；jpackage 下由启动器区分（M5+ 细化）
        return new AppPaths(base.toAbsolutePath().normalize(), bundle.toAbsolutePath().normalize());
    }

    private static Path resolveBase() {
        String prop = System.getProperty("textdiff.base.dir");
        if (prop != null && !prop.isBlank()) return Paths.get(prop);
        return Paths.get("").toAbsolutePath(); // 当前工作目录（运行位置）
    }

    public Path resultsDir()      { return baseDir.resolve("results").normalize(); }
    public Path resultsSplitDir() { return baseDir.resolve("results_split").normalize(); }
    public Path configsDir()      { return baseDir.resolve("configs").normalize(); }
    public Path userConfigIni()   { return baseDir.resolve("config.ini").normalize(); }
}
