package com.example.bankapplication.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record ResetPinRequest(
        @NotNull(message = "Phone number is required") Long phno,
        @NotBlank(message = "Code is required")
        @Pattern(regexp = "\\d{6}", message = "Code must be 6 digits") String otp,
        @NotBlank(message = "New PIN is required")
        @Pattern(regexp = "\\d{4,6}", message = "PIN must be 4 to 6 digits") String newPin) {
}
