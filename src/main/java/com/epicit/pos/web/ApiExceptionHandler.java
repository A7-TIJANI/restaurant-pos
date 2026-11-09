package com.epicit.pos.web;

import com.epicit.pos.service.BusinessException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Business rule violations become {"error": CODE, "message": "..."} with a meaningful status. */
@RestControllerAdvice(assignableTypes = OrderApiController.class)
public class ApiExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<Map<String, String>> business(BusinessException e) {
        return ResponseEntity.status(e.getStatus()).body(Map.of("error", e.getCode(), "message", e.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, String>> unreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "BAD_REQUEST", "message", "The request could not be understood."));
    }
}
