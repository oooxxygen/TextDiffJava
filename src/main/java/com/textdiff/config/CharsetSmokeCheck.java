package com.textdiff.config;

import java.nio.charset.Charset;
import java.util.*;

/** 启动期校验关键字符集存在（EBCDIC IBM 家族 + UTF-16）。缺失说明运行时裁剪漏了 jdk.charsets。 */
public final class CharsetSmokeCheck {

    static final List<String> REQUIRED = List.of(
        "UTF-8", "UTF-16LE", "UTF-16BE",
        "Cp037", "Cp500", "Cp1047", "Cp935", "Cp937", "Cp939"
    );

    private CharsetSmokeCheck() {}

    public static List<String> missing(List<String> names) {
        List<String> out = new ArrayList<>();
        for (String n : names) {
            try { Charset.forName(n); }
            catch (RuntimeException e) { out.add(n); }
        }
        return out;
    }

    public static void verify() {
        List<String> miss = missing(REQUIRED);
        if (!miss.isEmpty()) {
            throw new IllegalStateException(
                "运行时缺失字符集 " + miss + "；jlink 需 --add-modules jdk.charsets");
        }
    }
}
