package com.example.bankapplication.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** Self-signup step 2: the phone number used in step 1 and the 6-digit code that was emailed. */
public record SignupVerifyRequest(
        @NotNull(message = "Phone number is required") Long phno,
        @NotBlank(message = "Code is required")
        @Pattern(regexp = "[0-9]{6}", message = "The code is 6 digits") String otp) {
}
