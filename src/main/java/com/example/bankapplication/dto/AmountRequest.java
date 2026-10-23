package com.example.bankapplication.dto;

import java.math.BigDecimal;

/** otp is only used by PUT /bank/withdraw, for withdrawals large enough to need an emailed code. */
public record AmountRequest(BigDecimal amount, String otp) {
    public AmountRequest(BigDecimal amount) {
        this(amount, null);
    }
}
