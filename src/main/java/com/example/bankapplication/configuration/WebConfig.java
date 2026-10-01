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
    // Deposit/withdraw had no throttle at all: a stolen or leaked session token could otherwise script unlimited
    // calls with nothing slowing it down, unlike PhonepayService's equivalent sendmoney limit. Configurable, same
    // reasoning as the login limit above.
    public static final int DEFAULT_MAX_MONEY_ATTEMPTS_PER_ACCOUNT = 20;
    static final Duration LOGIN_RATE_WINDOW = Duration.ofMinutes(1);
    static final Duration MONEY_RATE_WINDOW = Duration.ofMinutes(1);
    // A forgot-PIN request sends an email - without a limit, someone could script unlimited emails to any
    // account (or just hammer SMTP) the same way login's per-address limit stops unlimited PIN guesses.
    public static final int DEFAULT_MAX_FORGOT_PIN_ATTEMPTS_PER_ADDRESS = 5;
    static final Duration FORGOT_PIN_RATE_WINDOW = Duration.ofMinutes(1);

    private final SessionService sessions;
    private final String adminApiKey;
    private final String serviceApiKey;
    private final Clock clock;
    private final int maxLoginAttemptsPerAddress;
    private final int maxMoneyAttemptsPerAccount;
    private final int maxForgotPinAttemptsPerAddress;

    public WebConfig(SessionService sessions,
                     @Value("${bank.admin.api-key}") String adminApiKey,
                     @Value("${bank.service.api-key}") String serviceApiKey,
                     Clock clock,
                     @Value("${bank.login.rate-limit.max-attempts:" + DEFAULT_MAX_LOGIN_ATTEMPTS_PER_ADDRESS + "}")
                     int maxLoginAttemptsPerAddress,
                     @Value("${bank.money.rate-limit.max-attempts:" + DEFAULT_MAX_MONEY_ATTEMPTS_PER_ACCOUNT + "}")
                     int maxMoneyAttemptsPerAccount,
                     @Value("${bank.forgot-pin.rate-limit.max-attempts:" + DEFAULT_MAX_FORGOT_PIN_ATTEMPTS_PER_ADDRESS + "}")
                     int maxForgotPinAttemptsPerAddress) {
        this.sessions = sessions;
        this.adminApiKey = adminApiKey;
        this.serviceApiKey = serviceApiKey;
        this.clock = clock;
        this.maxLoginAttemptsPerAddress = maxLoginAttemptsPerAddress;
        this.maxMoneyAttemptsPerAccount = maxMoneyAttemptsPerAccount;
        this.maxForgotPinAttemptsPerAddress = maxForgotPinAttemptsPerAddress;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Public: POST /bank/login, POST /bank/save. Login is rate-limited per address (see above).
        registry.addInterceptor(new RateLimitInterceptor(
                        new RateLimiter(maxLoginAttemptsPerAddress, LOGIN_RATE_WINDOW, clock),
                        HttpServletRequest::getRemoteAddr,
                        "Too many attempts from this address. Please wait a minute and try again."))
                .addPathPatterns("/bank/login");

        // Public: POST /bank/forgotpin/request, /bank/forgotpin/reset. Request is rate-limited per address
        // (it sends an email); reset has its own per-code attempt limit instead (see PinResetService).
        registry.addInterceptor(new RateLimitInterceptor(
                        new RateLimiter(maxForgotPinAttemptsPerAddress, FORGOT_PIN_RATE_WINDOW, clock),
                        HttpServletRequest::getRemoteAddr,
                        "Too many attempts from this address. Please wait a minute and try again."))
                .addPathPatterns("/bank/forgotpin/request");

        // Self-service: the caller acts only on their own account, identified by their own token.
        registry.addInterceptor(new CustomerAuthInterceptor(sessions))
                .addPathPatterns(
                        "/bank/logout",
                        "/bank/me",
                        "/bank/deposit",
                        "/bank/withdraw",
                        "/bank/update-phone",
                        "/bank/update-email",
                        "/bank/my-transactions");

        // Rate-limited per authenticated account (registered AFTER CustomerAuthInterceptor, so the phno attribute
        // it sets is already there): a stolen or leaked session token must not be able to script unlimited
        // deposit/withdraw calls with nothing slowing it down.
        registry.addInterceptor(new RateLimitInterceptor(
                        new RateLimiter(maxMoneyAttemptsPerAccount, MONEY_RATE_WINDOW, clock),
                        request -> String.valueOf(request.getAttribute(CustomerAuthInterceptor.AUTHENTICATED_PHNO)),
                        "Too many deposit/withdraw requests. Please wait a minute and try again."))
                .addPathPatterns("/bank/deposit", "/bank/withdraw");

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
