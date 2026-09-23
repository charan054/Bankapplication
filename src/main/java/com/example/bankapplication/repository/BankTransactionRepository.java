package com.example.bankapplication.repository;

import com.example.bankapplication.entity.BankTransaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;

@Repository
public interface BankTransactionRepository extends JpaRepository<BankTransaction, Long> {
    // from/to are optional: a null bound is skipped, so this serves both the filtered and unfiltered case.
    @Query("SELECT t FROM BankTransaction t WHERE t.phno = :phno "
            + "AND (:from IS NULL OR t.createdAt >= :from) "
            + "AND (:to IS NULL OR t.createdAt <= :to)")
    Page<BankTransaction> findByPhno(@Param("phno") long phno, @Param("from") Instant from, @Param("to") Instant to, Pageable pageable);

    // null when the table is empty
    @Query("select max(t.transactionId) from BankTransaction t")
    Long findMaxTransactionId();

    // One-time backfill for rows written before createdAt existed: only ever matches until they're all filled in,
    // so calling this again on every startup is harmless (see LegacyTransactionTimestampBackfill). A bulk UPDATE
    // bypasses the persistence context, so flushAutomatically/clearAutomatically keep it consistent with any
    // pending or already-loaded entities in the same transaction.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE BankTransaction t SET t.createdAt = :guessedAt WHERE t.createdAt IS NULL")
    int backfillMissingCreatedAt(@Param("guessedAt") Instant guessedAt);
}
