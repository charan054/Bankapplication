package com.example.bankapplication.repository;

import com.example.bankapplication.entity.Bank;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface BankRepository extends JpaRepository<Bank, Long> {
    Bank findByphno(long phno);
    Bank findByacno(long acno);
    boolean existsByPhno(long phno);
    boolean existsByAadharNumber(long aadharNumber);
    // null when the table is empty
    @Query("select max(b.acno) from Bank b")
    Long findMaxAcno();
}
