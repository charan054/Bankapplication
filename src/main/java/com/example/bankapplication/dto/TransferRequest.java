package com.example.bankapplication.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * idempotencyKey is chosen by the CALLER (PhonepayService uses its own transaction id) and must be unique per
 * logical transfer. Retrying this exact request with the same key is always safe: it returns the original
 * result instead of moving the money again, whether or not the first attempt's response ever arrived.
 */
public record TransferRequest(
        @NotNull(message = "Payer phone number is required") Long payerPhno,
        @NotNull(message = "Receiver phone number is required") Long receiverPhno,
        @NotNull(message = "Amount is required")
        @DecimalMin(value = "0.01", message = "Amount too low") BigDecimal amount,
        @NotBlank(message = "Idempotency key is required") String idempotencyKey) {
}
