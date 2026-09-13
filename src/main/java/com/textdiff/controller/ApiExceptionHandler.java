package com.textdiff.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/** 统一错误响应体 {"detail": ...}（前端 api() 封装读取 detail 字段）。 */
@ControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    @ResponseBody
    public ResponseEntity<Map<String, Object>> detail(ResponseStatusException e) {
        String msg = e.getReason() == null ? e.getMessage() : e.getReason();
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("detail", msg));
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    @ResponseBody
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> badRequest(RuntimeException e) {
        return Map.of("detail", e.getMessage() == null ? e.toString() : e.getMessage());
    }
}
