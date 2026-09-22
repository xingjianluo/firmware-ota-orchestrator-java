package com.github.xingjianluo.firmwareotaorchestratorjava.controller;

import com.github.xingjianluo.firmwareotaorchestratorjava.exception.RejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(RejectedException.class)
    public ResponseEntity<Map<String, String>> handleRejected(RejectedException ex) {
        HttpStatus status = ex.getReason().name().contains("NOT_FOUND")
                ? HttpStatus.NOT_FOUND : HttpStatus.CONFLICT;
        return ResponseEntity.status(status).body(Map.of(
                "reason", ex.getReason().name(),
                "message", ex.getMessage()));
    }
}
