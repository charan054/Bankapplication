package com.example.bankapplication.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** For PUT /bank/admin/set-pin: an admin sets or resets a customer's PIN (e.g. bootstrapping an account
 *  created before login existed, or helping someone who forgot their PIN). Requires the admin key. */
public record SetPinRequest(
        @NotNull(message = "Phone number is required") Long phno,
        @NotBlank(message = "PIN is required")
        @Pattern(regexp = "\\d{4,6}", message = "PIN must be 4 to 6 digits") String newPin) {
}
