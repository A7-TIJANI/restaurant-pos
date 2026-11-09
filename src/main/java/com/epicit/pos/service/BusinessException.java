package com.epicit.pos.service;

import org.springframework.http.HttpStatus;

/** A rule of the business was violated (out of stock, order locked, ...). Mapped to a JSON error by the API layer. */
public class BusinessException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public BusinessException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
