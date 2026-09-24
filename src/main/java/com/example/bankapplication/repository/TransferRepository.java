package com.example.bankapplication.repository;

import com.example.bankapplication.entity.Transfer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TransferRepository extends JpaRepository<Transfer, Long> {
    Optional<Transfer> findByPayerPhnoAndIdempotencyKey(long payerPhno, String idempotencyKey);
}
