package com.textdiff.config;

import java.io.IOException;
import java.nio.file.*;

/**
 * 可写数据根（baseDir）与只读打包资源（bundleDir）解析。对等 Python apppaths.py。
 *
 * 便携布局（v0.9.16+）：程序件（bin/lib/conf/legal/release）在包根、用户数据收口包根 {@code data/}
 * （config.ini、configs、store、results、results_split、uploads、customcmp、reportcmp）——
 * 版本升级只替换程序件、data/ 原样平移，路径用处分离。
 */
public record AppPaths(Path baseDir, Path bundleDir) {

    public static AppPaths detect() {
        return detect(Paths.get("").toAbsolutePath());
    }

    /** detect 起点：真实运行用 CWD；测试可传任意目录。 */
    static AppPaths detect(Path start) {
        Path base = resolveBase(start).toAbsolutePath().normalize();
        return new AppPaths(base, base);
    }

    /**
     * 数据根解析：
     * <ol>
     *   <li>{@code -Dtextdiff.base.dir} 显式指定最优先（启动脚本检测到便携布局 data/ 时注入，双保险——
     *       覆盖 Windows 启动器工作目录不定的情况）；</li>
     *   <li>起点（或向上至多 3 级）存在 bin+lib 同级 jpackage 镜像 → 数据收口到该包根的 {@code data/}
     *       （自动建目录；向上兼容启动器把 CWD 置于 bin/ 的形态）；</li>
     *   <li>其余（开发态 / 裸 jar 运行 / 容器）= 起点本身，行为与历史版本一致。</li>
     * </ol>
     */
    private static Path resolveBase(Path start) {
        String prop = System.getProperty("textdiff.base.dir");
        if (prop != null && !prop.isBlank()) return Paths.get(prop);
        Path p = start.toAbsolutePath().normalize();
        if (Files.isDirectory(p.resolve("data"))) return p.resolve("data"); // 已是便携包根（data 预置）
        for (int i = 0; i < 3 && p != null; i++) {
            if (Files.isDirectory(p.resolve("bin")) && Files.isDirectory(p.resolve("lib"))) {
                Path d = p.resolve("data");
                try {
                    Files.createDirectories(d);
                } catch (IOException ignored) {
                    // 建目录失败时仍返回该路径，后续写盘按各自既有错误路径报错
                }
                return d;
            }
            p = p.getParent();
        }
        return start;
    }

    public Path resultsDir()      { return baseDir.resolve("results").normalize(); }
    public Path resultsSplitDir() { return baseDir.resolve("results_split").normalize(); }
    public Path configsDir()      { return baseDir.resolve("configs").normalize(); }
    public Path userConfigIni()   { return baseDir.resolve("config.ini").normalize(); }
}
