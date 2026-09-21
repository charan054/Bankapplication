package com.example.bankapplication.repository;

import com.example.bankapplication.entity.OtpData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface OtpDataRepository extends JpaRepository<OtpData, Long> {
    OtpData findTopByPhnoOrderByIdDesc(long phno);
}
