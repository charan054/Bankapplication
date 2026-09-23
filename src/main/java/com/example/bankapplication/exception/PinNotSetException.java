package com.example.bankapplication.exception;

/** The account exists but has no PIN yet (created before login existed, or reset by an admin). */
public class PinNotSetException extends RuntimeException {
    public PinNotSetException(String message) {
        super(message);
    }
}
