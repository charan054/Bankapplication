package com.example.bankapplication.repository;

import com.example.bankapplication.entity.FixedDeposit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface FixedDepositRepository extends JpaRepository<FixedDeposit, Long> {
    // newest first
    List<FixedDeposit> findByPhnoOrderByIdDesc(long phno);
    List<FixedDeposit> findByPhnoAndStatusAndMaturesAtLessThanEqual(long phno, String status, Instant maturesAt);
}
