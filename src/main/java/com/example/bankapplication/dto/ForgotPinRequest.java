package com.example.bankapplication.dto;

import jakarta.validation.constraints.NotNull;

public record ForgotPinRequest(@NotNull(message = "Phone number is required") Long phno) {
}
