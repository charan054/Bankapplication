package com.example.bankapplication.configuration;

import com.example.bankapplication.service.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Clock;
import java.time.Duration;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    // The per-account lockout in BankService only starts once an account is chosen; someone trying many DIFFERENT
    // phone numbers (enumeration) never touches it. This closes that gap, per address. Configurable (with this as
    // the default) so a test suite that legitimately logs in many times from one simulated address, such as
    // BankApplicationIntegrationTest, can raise it instead of tripping over production-sized traffic assumptions.
    public static final int DEFAULT_MAX_LOGIN_ATTEMPTS_PER_ADDRESS = 10;
    static final Duration LOGIN_RATE_WINDOW = Duration.ofMinutes(1);

    private final SessionService sessions;
    private final String adminApiKey;
    private final String serviceApiKey;
    private final Clock clock;
    private final int maxLoginAttemptsPerAddress;

    public WebConfig(SessionService sessions,
                     @Value("${bank.admin.api-key}") String adminApiKey,
                     @Value("${bank.service.api-key}") String serviceApiKey,
                     Clock clock,
                     @Value("${bank.login.rate-limit.max-attempts:" + DEFAULT_MAX_LOGIN_ATTEMPTS_PER_ADDRESS + "}")
                     int maxLoginAttemptsPerAddress) {
        this.sessions = sessions;
        this.adminApiKey = adminApiKey;
        this.serviceApiKey = serviceApiKey;
        this.clock = clock;
        this.maxLoginAttemptsPerAddress = maxLoginAttemptsPerAddress;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Public: POST /bank/login, POST /bank/save. Login is rate-limited per address (see above).
        registry.addInterceptor(new RateLimitInterceptor(
                        new RateLimiter(maxLoginAttemptsPerAddress, LOGIN_RATE_WINDOW, clock),
                        HttpServletRequest::getRemoteAddr,
                        "Too many attempts from this address. Please wait a minute and try again."))
                .addPathPatterns("/bank/login");

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
