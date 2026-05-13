package com.example.bankapplication.entity;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import jakarta.persistence.*;
import lombok.Data;

import java.util.List;

@Entity
@Table(name="Bank")
@Data
@JsonPropertyOrder({
        "userId",
        "acno",
        "firstName",
        "lastName",
        "aadharnumber",
        "phno",
        "balance"
})
public class Bank {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int userId;
    private long acno;
    private String firstName;
    private String lastName;
    private long aadharNumber;
    private long phno;
    private double balance;
    @OneToMany(cascade = CascadeType.ALL)
    @JoinColumn(name = "user_id", referencedColumnName = "userId")
    private List<BankTransaction>  transactions;
}
