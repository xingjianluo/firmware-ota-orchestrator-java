package com.github.xingjianluo.firmwareotaorchestratorjava.api;

import com.github.xingjianluo.firmwareotaorchestratorjava.domain.RejectReason;
import com.github.xingjianluo.firmwareotaorchestratorjava.error.OperationRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    public record ErrorBody(RejectReason reason, String message) {}

    @ExceptionHandler(OperationRejectedException.class)
    public ResponseEntity<ErrorBody> handleRejected(OperationRejectedException ex) {
        HttpStatus status = switch (ex.getReason()) {
            case UNKNOWN_DEVICE, UNKNOWN_TASK -> HttpStatus.NOT_FOUND;
            default -> HttpStatus.CONFLICT;
        };
        return ResponseEntity.status(status).body(new ErrorBody(ex.getReason(), ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
    }
}
