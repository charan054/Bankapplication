package com.example.bankapplication.exception;

/** Wrong, expired, or already-used PIN-reset code. Deliberately generic, same reasoning as
 *  InvalidCredentialsException - it never says which of those it was. */
public class InvalidOtpException extends RuntimeException {
    public InvalidOtpException(String message) {
        super(message);
    }
}
