package com.example.bankapplication.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@JsonPropertyOrder({
        "userId",
        "acno",
        "name",
        "aadharNumber",
        "phno",
        "balance"
})
public class BankDto {
    private int userId;
    private long acno;
    private String name;
    private long aadharNumber;
    private long phno;
    private BigDecimal balance;
    public BankDto(int userId,long acno,String name,long aadharNumber,long phno,BigDecimal balance)
    {
        this.userId = userId;
        this.acno = acno;
        this.name = name;
        this.aadharNumber = aadharNumber;
        this.phno = phno;
        this.balance = balance;
    }
}
