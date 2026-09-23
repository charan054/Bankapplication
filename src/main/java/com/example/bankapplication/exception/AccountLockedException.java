package com.example.bankapplication.exception;

/** Too many wrong PINs in a row. Thrown even if THIS attempt's PIN would have been correct. */
public class AccountLockedException extends RuntimeException {
    public AccountLockedException(String message) {
        super(message);
    }
}
