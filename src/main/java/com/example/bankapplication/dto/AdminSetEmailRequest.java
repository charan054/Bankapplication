package com.example.bankapplication.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** For PUT /bank/admin/set-email: an admin sets or replaces a customer's email on file (e.g. backfilling it for
 *  an account that predates this feature, after verifying who they are out-of-band - a customer who has
 *  forgotten their PIN and never set an email has no self-service way to add one themselves). Requires the
 *  admin key. */
public record AdminSetEmailRequest(
        @NotNull(message = "Phone number is required") Long phno,
        @NotBlank(message = "Email is required")
        @Email(message = "Email must be a valid address") String email) {
}
