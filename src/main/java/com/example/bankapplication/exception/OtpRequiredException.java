package com.example.bankapplication.exception;

/** A withdrawal big enough to need an emailed confirmation code was attempted without one (HTTP 403). */
public class OtpRequiredException extends RuntimeException {
    public OtpRequiredException(String message) {
        super(message);
    }
}
