package com.example.bankapplication.exception;

/** A caller has exceeded a rate limit (for example, too many /bank/login attempts from one address). */
public class TooManyRequestsException extends RuntimeException {
    public TooManyRequestsException(String message) {
        super(message);
    }
}
