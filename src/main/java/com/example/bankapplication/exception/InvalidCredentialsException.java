package com.example.bankapplication.exception;

/** Wrong phone number or PIN at login. Deliberately generic, so a login attempt cannot be used to discover
 *  whether a given phone number has an account at all. */
public class InvalidCredentialsException extends RuntimeException {
    public InvalidCredentialsException(String message) {
        super(message);
    }
}
