package com.example.bankapplication.repository;

import com.example.bankapplication.entity.BankTransaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface BankTransactionRepository extends JpaRepository<BankTransaction, Long> {
    Page<BankTransaction> findByPhno(long phno, Pageable pageable);
    // null when the table is empty
    @Query("select max(t.transactionId) from BankTransaction t")
    Long findMaxTransactionId();
}
