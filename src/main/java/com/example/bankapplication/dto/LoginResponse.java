package com.example.bankapplication.dto;

import java.time.Instant;

/** Send the token on every later self-service request as: Authorization: Bearer &lt;token&gt; */
public record LoginResponse(String token, Instant expiresAt, long phno, String name) {
}
