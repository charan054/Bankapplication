package com.example.bankapplication.exception;

/**
 * A wrong, expired, used-up or wrong-amount withdrawal code (HTTP 400). Deliberately not InvalidOtpException (401):
 * the customer IS logged in, and a 401 makes the portal think the session ended and sign them out.
 */
public class InvalidWithdrawalCodeException extends RuntimeException {
    public InvalidWithdrawalCodeException(String message) {
        super(message);
    }
}
