package com.example.bankapplication.exception;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

@ControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<String> handleUserNotFound(UserNotFoundException ex) {
        return ResponseEntity.badRequest().body(ex.getMessage());
    }
    @ExceptionHandler(WithdrawException.class)
    public ResponseEntity<String> handleWithdrawException(WithdrawException ex) {
        return ResponseEntity.badRequest().body(ex.getMessage());
    }
    @ExceptionHandler(UserExistException.class)
    public ResponseEntity<String> handleUserExistException(UserExistException ex) {
        return ResponseEntity.badRequest().body(ex.getMessage());
    }
    @ExceptionHandler(DepositException.class)
    public ResponseEntity<String> handleDepositException(DepositException ex)
    {
        return ResponseEntity.badRequest().body(ex.getMessage());
    }
    @ExceptionHandler(MobileNumberException.class)
    public ResponseEntity<String> handleMobileNumberException(MobileNumberException ex) {
        return ResponseEntity.badRequest().body(ex.getMessage());
    }
}

