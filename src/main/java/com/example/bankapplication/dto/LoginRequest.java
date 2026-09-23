package com.example.bankapplication.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record LoginRequest(@NotNull(message = "Phone number is required") Long phno,
                           @NotBlank(message = "PIN is required") String pin) {
}
