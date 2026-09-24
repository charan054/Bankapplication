package com.example.bankapplication.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

// Only a 4-6 digit PIN can ever have been set (see RegisterRequest/SetPinRequest), so anything else is
// rejected here rather than reaching BCrypt - an unbounded PIN string would otherwise trip BCrypt's 72-byte
// input limit and throw IllegalArgumentException, surfacing as a bare 500 instead of a clean 400.
public record LoginRequest(@NotNull(message = "Phone number is required") Long phno,
                           @NotBlank(message = "PIN is required")
                           @Pattern(regexp = "\\d{4,6}", message = "PIN must be 4 to 6 digits") String pin) {
}
