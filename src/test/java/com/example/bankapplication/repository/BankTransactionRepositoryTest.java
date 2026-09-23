package com.example.bankapplication.repository;

import com.example.bankapplication.entity.Bank;
import com.example.bankapplication.entity.BankTransaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
class BankTransactionRepositoryTest {

    @Autowired
    private BankTransactionRepository repository;
    @Autowired
    private BankRepository bankRepository;

    private int userIdA;
    private int userIdB;

    @BeforeEach
    void createTheOwningAccounts() {
        // BankTransaction.userId is a foreign key into Bank, so every transaction needs a real owning account.
        userIdA = bankRepository.save(newBank(1000000000L, 9876543210L, 111111111111L)).getUserId();
        userIdB = bankRepository.save(newBank(1000000001L, 9123456789L, 222222222222L)).getUserId();
    }

    private Bank newBank(long acno, long phno, long aadhar) {
        Bank b = new Bank();
        b.setAcno(acno);
        b.setFirstName("Charan");
        b.setLastName("Kumar");
        b.setPhno(phno);
        b.setAadharNumber(aadhar);
        b.setBalance(BigDecimal.ZERO);
        return b;
    }

    private BankTransaction txn(long transactionId, int userId, long phno, String action, double amount, double balance) {
        BankTransaction t = new BankTransaction();
        t.setTransactionId(transactionId);
        t.setUserId(userId);
        t.setPhno(phno);
        t.setAction(action);
        t.setAmount(BigDecimal.valueOf(amount));
        t.setBalance(BigDecimal.valueOf(balance));
        return t;
    }

    @Test
    void findByPhno_returnsOnlyThatPhonesRows() {
        repository.save(txn(100000, userIdA, 9876543210L, "Credit", 500, 500));
        repository.save(txn(100001, userIdB, 9123456789L, "Credit", 900, 900));

        Page<BankTransaction> page = repository.findByPhno(9876543210L, PageRequest.of(0, 20, Sort.by("id").ascending()));

        assertEquals(1, page.getTotalElements());
        assertEquals(100000, page.getContent().get(0).getTransactionId());
    }

    @Test
    void findByPhno_ordersAndSlicesAccordingToThePageable() {
        for (int i = 0; i < 5; i++) {
            repository.save(txn(100000 + i, userIdA, 9876543210L, "Credit", 100, 100));
        }

        Page<BankTransaction> firstPage = repository.findByPhno(9876543210L, PageRequest.of(0, 2, Sort.by("id").ascending()));
        Page<BankTransaction> secondPage = repository.findByPhno(9876543210L, PageRequest.of(1, 2, Sort.by("id").ascending()));

        assertEquals(5, firstPage.getTotalElements());
        assertEquals(3, firstPage.getTotalPages());
        assertEquals(2, firstPage.getContent().size());
        assertEquals(100000, firstPage.getContent().get(0).getTransactionId());
        assertEquals(100001, firstPage.getContent().get(1).getTransactionId());
        assertEquals(100002, secondPage.getContent().get(0).getTransactionId());
    }

    @Test
    void findByPhno_pageBeyondTheLastOne_isEmptyNotAnError() {
        repository.save(txn(100000, userIdA, 9876543210L, "Credit", 500, 500));

        Page<BankTransaction> page = repository.findByPhno(9876543210L, PageRequest.of(5, 20, Sort.by("id").ascending()));

        assertTrue(page.getContent().isEmpty());
        assertEquals(1, page.getTotalElements());
    }

    @Test
    void findByPhno_unknownPhno_isEmpty() {
        Page<BankTransaction> page = repository.findByPhno(9999999999L, PageRequest.of(0, 20, Sort.by("id").ascending()));

        assertTrue(page.getContent().isEmpty());
        assertEquals(0, page.getTotalElements());
    }

    // ---------- the highest transaction number, used to allocate the next one ----------

    @Test
    void findMaxTransactionId_isNullWhenThereAreNoTransactions() {
        assertNull(repository.findMaxTransactionId());
    }

    @Test
    void findMaxTransactionId_isTheHighestNumber_notTheLastRowInserted() {
        repository.save(txn(100005, userIdA, 9876543210L, "Credit", 10, 10));
        repository.save(txn(100002, userIdA, 9876543210L, "Credit", 10, 20));
        repository.save(txn(100003, userIdA, 9876543210L, "Credit", 10, 30));

        assertEquals(100005L, repository.findMaxTransactionId());
    }
}
