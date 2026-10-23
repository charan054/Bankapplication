package com.example.bankapplication.dto;

import java.math.BigDecimal;

public record LimitRequest(BigDecimal dailyLimit) {
}
