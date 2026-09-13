package com.textdiff.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import java.io.IOException;
import java.io.UncheckedIOException;

/** JSON 序列化统一入口：记录/结果文件/REST 共用，snake_case 对齐前端契约。 */
public final class Json {
    public static final ObjectMapper MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private Json() {}

    public static String write(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static <T> T read(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
