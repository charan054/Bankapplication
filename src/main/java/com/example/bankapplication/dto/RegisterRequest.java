package com.example.bankapplication.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record RegisterRequest(
        @NotBlank(message = "First name is required") String firstName,
        @NotBlank(message = "Last name is required") String lastName,
        @NotNull(message = "Aadhar number is required") Long aadharNumber,
        @NotNull(message = "Phone number is required") Long phno,
        @NotBlank(message = "PIN is required")
        @Pattern(regexp = "\\d{4,6}", message = "PIN must be 4 to 6 digits") String pin) {
}
