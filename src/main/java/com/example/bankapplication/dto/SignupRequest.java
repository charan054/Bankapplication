package com.example.bankapplication.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** Self-signup step 1. Same fields as RegisterRequest, but the email is required: the confirmation code is sent there. */
public record SignupRequest(
        @NotBlank(message = "First name is required") String firstName,
        @NotBlank(message = "Last name is required") String lastName,
        @NotNull(message = "Aadhar number is required") Long aadharNumber,
        @NotNull(message = "Phone number is required") Long phno,
        @NotBlank(message = "PIN is required")
        @Pattern(regexp = "[0-9]{4,6}", message = "PIN must be 4 to 6 digits") String pin,
        @NotBlank(message = "Email is required to confirm your account")
        @Email(message = "Email must be a valid address") String email) {
}
