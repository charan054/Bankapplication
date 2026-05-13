package com.example.bankapplication.repository;

import com.example.bankapplication.entity.BankTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BankTransactionRepository extends JpaRepository<BankTransaction,Long> {
    public List<BankTransactionRepository> findByPhno(long phno);
    public  List<BankTransactionRepository> findByuserId(long userid);
}
