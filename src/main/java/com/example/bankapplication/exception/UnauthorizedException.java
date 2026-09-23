package com.example.bankapplication.exception;

/** No token, an invalid/expired customer session, or a missing/wrong admin or service key. */
public class UnauthorizedException extends RuntimeException {
    public UnauthorizedException(String message) {
        super(message);
    }
}
