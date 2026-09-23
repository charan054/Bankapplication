package com.example.bankapplication.configuration;

import com.example.bankapplication.service.SessionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final SessionService sessions;
    private final String adminApiKey;
    private final String serviceApiKey;

    public WebConfig(SessionService sessions,
                     @Value("${bank.admin.api-key}") String adminApiKey,
                     @Value("${bank.service.api-key}") String serviceApiKey) {
        this.sessions = sessions;
        this.adminApiKey = adminApiKey;
        this.serviceApiKey = serviceApiKey;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Public: POST /bank/login, POST /bank/save. No interceptor needed for either.

        // Self-service: the caller acts only on their own account, identified by their own token.
        registry.addInterceptor(new CustomerAuthInterceptor(sessions))
                .addPathPatterns(
                        "/bank/logout",
                        "/bank/me",
                        "/bank/deposit",
                        "/bank/withdraw",
                        "/bank/update-phone",
                        "/bank/my-transactions");

        // Trusted callers: act on any account, named by phone/account number in the request. Used by bank.html
        // (admin key) and by PhonepayService's backend calls (service key).
        registry.addInterceptor(new TrustedCallerInterceptor(adminApiKey, serviceApiKey))
                .addPathPatterns(
                        "/bank/all",
                        "/bank/getByphno/**",
                        "/bank/getByacno/**",
                        "/bank/withdrawByphno",
                        "/bank/withdrawByacno",
                        "/bank/depositByphno",
                        "/bank/depositByacno",
                        "/bank/updatephno",
                        "/bank/deleteuser",
                        "/bank/displayuser",
                        "/bank/transactions",
                        "/bank/transfer",
                        "/bank/admin/**");
    }
}
