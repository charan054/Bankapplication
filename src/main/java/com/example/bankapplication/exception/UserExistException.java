package com.example.bankapplication.exception;

public class UserExistException extends RuntimeException{
    public UserExistException(String message){
        super(message);
    }
}
