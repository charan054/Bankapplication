package com.example.bankapplication.service;

import com.example.bankapplication.repository.OtpDataRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
public class OtpDataService {
    @Autowired
    OtpDataRepository otpDataRepository;
    @Autowired
    RestTemplate restTemplate;
    // Set the OTP_API_KEY environment variable; the key must never be committed to source control.
    @Value("${otp.api-key:}")
    private String apiKey;

}
