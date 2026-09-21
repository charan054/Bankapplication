package com.example.bankapplication.exception;

import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
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
    // Two requests touched the same data at the same time: either the optimistic lock (@Version) refused a stale
    // update, or a unique constraint (phno, aadhar, acno, transactionId) stopped a duplicate that both requests
    // passed the service's checks for. Retrying re-runs those checks on the committed data and gives the real answer.
    @ExceptionHandler({ConcurrencyFailureException.class, DataIntegrityViolationException.class})
    public ResponseEntity<String> handleConcurrentModification(DataAccessException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body("Another request changed the same data at the same time. Please retry.");
    }
}

