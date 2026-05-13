package com.example.bankapplication.repository;

import com.example.bankapplication.entity.Bank;
import com.example.bankapplication.entity.BankTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BankRepository extends JpaRepository<Bank, Long> {
    public Bank findByphno(long phno);
    public  Bank findByacno(long acno);
}
