package com.example.bankapplication.controller;

import com.example.bankapplication.configuration.CustomerAuthInterceptor;
import com.example.bankapplication.configuration.TrustedCallerInterceptor;
import com.example.bankapplication.dto.AmountRequest;
import com.example.bankapplication.dto.BankDto;
import com.example.bankapplication.dto.LoginRequest;
import com.example.bankapplication.dto.LoginResponse;
import com.example.bankapplication.dto.AdminSetEmailRequest;
import com.example.bankapplication.dto.ForgotPinRequest;
import com.example.bankapplication.dto.PageResponse;
import com.example.bankapplication.dto.RegisterRequest;
import com.example.bankapplication.dto.ResetPinRequest;
import com.example.bankapplication.dto.SetPinRequest;
import com.example.bankapplication.dto.SignupRequest;
import com.example.bankapplication.dto.SignupVerifyRequest;
import com.example.bankapplication.dto.TransferRequest;
import com.example.bankapplication.dto.UpdateEmailRequest;
import com.example.bankapplication.dto.UpdatePhoneRequest;
import com.example.bankapplication.entity.Bank;
import com.example.bankapplication.entity.BankTransaction;
import com.example.bankapplication.exception.DepositException;
import com.example.bankapplication.exception.UserNotFoundException;
import com.example.bankapplication.exception.WithdrawException;
import com.example.bankapplication.service.BankService;
import com.example.bankapplication.service.PinResetService;
import com.example.bankapplication.service.SignupService;
import com.example.bankapplication.service.SessionService;
import com.example.bankapplication.service.WithdrawalOtpService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;

/**
 * Three tiers, enforced by the interceptors in configuration/WebConfig:
 * <ul>
 *   <li>public — /login, /register/request + /register/verify (emailed-code signup), forgot-PIN;</li>
 *   <li>self-service (Authorization: Bearer &lt;customer token&gt;) — acts only on the caller's own account,
 *       whose phno comes from {@link CustomerAuthInterceptor#AUTHENTICATED_PHNO}, never from the request;</li>
 *   <li>trusted caller (X-Admin-Key or X-Service-Key) — acts on any account named by phno/acno in the request;
 *       used by bank.html and by PhonepayService's backend calls.</li>
 * </ul>
 */
@RestController
@RequestMapping("/bank")
public class BankController {
    @Autowired
    private BankService bankService;
    @Autowired
    private PinResetService pinResetService;
    @Autowired
    private SignupService signupService;
    @Autowired
    private SessionService sessionService;
    @org.springframework.beans.factory.annotation.Value("${bank.service.api-key}")
    private String serviceApiKey;
    @Autowired
    private WithdrawalOtpService withdrawalOtpService;

    // ---------- public ----------

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request,
                               @RequestHeader(value = "X-Service-Key", required = false) String serviceKey,
                               jakarta.servlet.http.HttpServletRequest http) {
        // PhonepayService logs customers in here with the service key on every PhonePe login: same checks, but no
        // "new login" email (see BankService.loginAsTrustedService) and no per-address rate limit (see WebConfig).
        if (TrustedCallerInterceptor.isServiceKey(serviceKey, serviceApiKey)) {
            return bankService.loginAsTrustedService(request.phno(), request.pin());
        }
        LoginResponse issued = bankService.login(request.phno(), request.pin());
        try {
            sessionService.annotate(issued.token(), http.getHeader("User-Agent"), http.getRemoteAddr());
        } catch (RuntimeException e) {
            // only labels the login in the customer's "Active logins" list; never fail a login over it
        }
        return issued;
    }

    // Direct account creation for the admin console (admin key) and trusted callers (service key): no emailed code.
    // Customers sign up themselves through /register/request + /register/verify below.
    @PostMapping("/save")
    public Bank save(@Valid @RequestBody RegisterRequest request) {
        return bankService.register(request);
    }

    // Self-signup, step 1: validates the details and emails a 6-digit code. No account exists yet. 204 on success;
    // a taken phone number / Aadhar is reported here (same as the old single-step signup did).
    @PostMapping("/register/request")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void registerRequest(@Valid @RequestBody SignupRequest request) {
        signupService.requestSignup(request);
    }

    // Self-signup, step 2: the emailed code creates the account.
    @PostMapping("/register/verify")
    public Bank registerVerify(@Valid @RequestBody SignupVerifyRequest request) {
        return signupService.verify(request.phno(), request.otp());
    }

    // Always 204 regardless of whether the phone number has an account or that account has an email on file -
    // see PinResetService.requestReset for why (same enumeration reasoning as /login's generic error message).
    @PostMapping("/forgotpin/request")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void forgotPinRequest(@Valid @RequestBody ForgotPinRequest request) {
        pinResetService.requestReset(request.phno());
    }

    @PostMapping("/forgotpin/reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void forgotPinReset(@Valid @RequestBody ResetPinRequest request) {
        pinResetService.resetPin(request.phno(), request.otp(), request.newPin());
    }

    // ---------- self-service ----------

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@RequestHeader("Authorization") String authorization) {
        bankService.logout(CustomerAuthInterceptor.bearerToken(authorization));
    }

    // The caller's own live logins (what Active logins shows), with the one making this request marked current.
    @GetMapping("/sessions")
    public List<SessionService.SessionView> mySessions(@RequestAttribute(CustomerAuthInterceptor.AUTHENTICATED_PHNO) long phno,
                                                       @RequestHeader("Authorization") String authorization) {
        return sessionService.listFor(phno, CustomerAuthInterceptor.bearerToken(authorization));
    }

    @DeleteMapping("/sessions/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void endSession(@RequestAttribute(CustomerAuthInterceptor.AUTHENTICATED_PHNO) long phno, @PathVariable long id) {
        if (!sessionService.endOwned(phno, id)) {
            throw new UserNotFoundException("Login not found");
        }
    }

    // Sign out everywhere. keepCurrent=true leaves this browser signed in.
    @PostMapping("/logout-all")
    public java.util.Map<String, Integer> logoutAll(@RequestAttribute(CustomerAuthInterceptor.AUTHENTICATED_PHNO) long phno,
                                                    @RequestHeader("Authorization") String authorization,
                                                    @RequestParam(defaultValue = "false") boolean keepCurrent) {
        int ended = sessionService.endAll(phno, keepCurrent ? CustomerAuthInterceptor.bearerToken(authorization) : null);
        return java.util.Map.of("ended", ended);
    }

    @GetMapping("/me")
    public BankDto me(@RequestAttribute(CustomerAuthInterceptor.AUTHENTICATED_PHNO) long phno) {
        return bankService.displayUserByPhno(phno);
    }

    @PutMapping("/deposit")
    public String deposit(@RequestAttribute(CustomerAuthInterceptor.AUTHENTICATED_PHNO) long phno,
                          @RequestBody AmountRequest request) {
        BigDecimal amount = requirePositiveAmount(request.amount());
        return bankService.depositByphno(phno, amount);
    }

    @PutMapping("/withdraw")
    public String withdraw(@RequestAttribute(CustomerAuthInterceptor.AUTHENTICATED_PHNO) long phno,
                           @RequestBody AmountRequest request) {
        BigDecimal amount = requirePositiveAmount(request.amount());
        Bank exis = bankService.findByphno(phno);
        if (exis == null) {
            throw new UserNotFoundException("User not found");
        }
        requireSufficientFunds(exis, amount);
        // Large withdrawals need the code emailed by POST /bank/withdraw/otp (checked after the cheap checks above, so a
        // withdrawal that would fail anyway does not use the code up).
        withdrawalOtpService.requireValidCode(phno, amount, request.otp());
        return bankService.withdrawByphno(phno, amount);
    }

    // Emails a confirmation code for exactly this amount; the same amount must then be sent to PUT /withdraw with the code.
    @PostMapping("/withdraw/otp")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void withdrawOtp(@RequestAttribute(CustomerAuthInterceptor.AUTHENTICATED_PHNO) long phno,
                            @RequestBody AmountRequest request) {
        withdrawalOtpService.request(phno, requirePositiveAmount(request.amount()));
    }

    @PutMapping("/update-phone")
    public Bank updatePhone(@RequestAttribute(CustomerAuthInterceptor.AUTHENTICATED_PHNO) long phno,
                            @RequestBody UpdatePhoneRequest request) {
        return bankService.updatePhno(phno, request.newPhno());
    }

    @PutMapping("/update-email")
    public Bank updateEmail(@RequestAttribute(CustomerAuthInterceptor.AUTHENTICATED_PHNO) long phno,
                            @Valid @RequestBody UpdateEmailRequest request) {
        return bankService.updateEmail(phno, request.email());
    }

    @GetMapping("/my-transactions")
    public PageResponse<BankTransaction> myTransactions(@RequestAttribute(CustomerAuthInterceptor.AUTHENTICATED_PHNO) long phno,
                          @RequestParam(defaultValue = "0") int page,
                          @RequestParam(defaultValue = "" + BankService.DEFAULT_PAGE_SIZE) int size,
                          @RequestParam(required = false) Instant from,
                          @RequestParam(required = false) Instant to) {
        return bankService.displayMyTransactions(phno, page, size, from, to);
    }

    @DeleteMapping("/me")
    public void deleteMe(@RequestAttribute(CustomerAuthInterceptor.AUTHENTICATED_PHNO) long phno) {
        bankService.deleteByPhno(phno);
    }

    // ---------- trusted caller (admin key or service key) ----------

    @GetMapping("/all")
    public PageResponse<BankDto> getAllUser(@RequestParam(defaultValue = "0") int page,
                            @RequestParam(defaultValue = "" + BankService.DEFAULT_PAGE_SIZE) int size){
        return bankService.findAll(page, size);
    }
    @GetMapping("/getByphno/{phno}")
    public Bank getByPhno(@PathVariable("phno") long phno){
        Bank exis=bankService.findByphno(phno);
        if(exis==null)
        {
            throw new UserNotFoundException("User not found");
        }
        return exis;
    }
    @GetMapping("/getByacno/{acno}")
    public Bank getByAcno(@PathVariable  long acno){
        Bank exis=bankService.findByacno(acno);
        if(exis==null)
        {
            throw new UserNotFoundException("User not found");
        }
        return exis;
    }
    @PutMapping("/withdrawByphno")
    public String withdrawByphno(@RequestParam long phno,@RequestParam BigDecimal balance){
        balance = requirePositiveAmount(balance);
        Bank exis= bankService.findByphno(phno);
        if(exis==null) {
            throw new UserNotFoundException("User not found");
        }
        requireSufficientFunds(exis, balance);
        return bankService.withdrawByphno(phno, balance);
    }
    @PutMapping("/withdrawByacno")
    public String withdrawByacno(@RequestParam long acno,@RequestParam BigDecimal balance){
        balance = requirePositiveAmount(balance);
        Bank exis= bankService.findByacno(acno);
        if(exis==null) {
            throw new UserNotFoundException("User not found");
        }
        requireSufficientFunds(exis, balance);
        return bankService.withdrawByacno(acno, balance);
    }
    @PutMapping("/depositByphno")
    public String depositByphno(@RequestParam long phno,@RequestParam BigDecimal balance){
        balance = requirePositiveAmount(balance);
        return bankService.depositByphno(phno,balance);
    }
    @PutMapping("/depositByacno")
    public String depositByacno(@RequestParam long acno,@RequestParam BigDecimal balance){
        balance = requirePositiveAmount(balance);
        return bankService.depositByacno(acno,balance);
    }
    @PutMapping("/updatephno")
    public Bank updatephno(@RequestParam long phno,@RequestParam long newphno){
     return  bankService.updatePhno(phno, newphno);
    }
    @DeleteMapping("/deleteuser")
    public void deleteUser(@RequestParam long phno){
        bankService.deleteByPhno(phno);
    }
    @GetMapping("/displayuser")
    public BankDto displayUser(@RequestParam long phno){
        return bankService.displayUserByPhno(phno);
    }
    @GetMapping("/transactions")
    public PageResponse<BankTransaction> displayTransactionByPhno(@RequestParam long phno,
                          @RequestParam(defaultValue = "0") int page,
                          @RequestParam(defaultValue = "" + BankService.DEFAULT_PAGE_SIZE) int size,
                          @RequestParam(required = false) Instant from,
                          @RequestParam(required = false) Instant to){
        return bankService.displayTransactionByPhno(phno, page, size, from, to);
    }

    @PostMapping("/transfer")
    public String transfer(@Valid @RequestBody TransferRequest request) {
        BigDecimal amount = requirePositiveAmount(request.amount());
        return bankService.transfer(request.payerPhno(), request.receiverPhno(), amount, request.idempotencyKey());
    }

    @PutMapping("/admin/set-pin")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setPin(@Valid @RequestBody SetPinRequest request) {
        bankService.setPin(request.phno(), request.newPin());
    }

    // Backfills an email for an account that predates this feature, or has none - the one case the self-service
    // PUT /update-email can't cover, since that requires already being logged in.
    @PutMapping("/admin/set-email")
    public Bank adminSetEmail(@Valid @RequestBody AdminSetEmailRequest request) {
        return bankService.updateEmail(request.phno(), request.email());
    }

    // ---------- shared validation ----------

    // DECIMAL(19,2) can hold at most 17 digits before the decimal point; this stays comfortably under that.
    private static final int MAX_INTEGER_DIGITS = 15;

    // Normalizes to 2 decimal places (rounding, never rejecting extra precision) so every amount stored or
    // echoed back is consistently formatted, regardless of how many decimals the caller sent.
    private BigDecimal requirePositiveAmount(BigDecimal amount) {
        if (amount == null) {
            throw new DepositException("Amount too low");
        }
        // Checked on the RAW value, before setScale ever touches it. setScale grows the unscaled value by
        // (targetScale - scale()) digits, so a maliciously huge scale (e.g. "1E+2000000000" in the request
        // JSON) would turn that into a multi-gigabyte allocation. precision() and scale() are cheap to read
        // even for such a value, since neither has to materialize the fully expanded number.
        int integerDigits = amount.precision() - amount.scale();
        if (integerDigits > MAX_INTEGER_DIGITS) {
            throw new DepositException("Amount too large");
        }
        // Positivity is checked AFTER rounding, not before: a sub-cent amount like 0.001 is > 0 but rounds down
        // to 0.00, and must be rejected as too low rather than "succeeding" as a deposit/withdrawal of nothing.
        BigDecimal normalized = amount.setScale(2, RoundingMode.HALF_UP);
        if (normalized.compareTo(BigDecimal.ZERO) <= 0) {
            throw new DepositException("Amount too low");
        }
        return normalized;
    }

    private void requireSufficientFunds(Bank account, BigDecimal amount) {
        if (amount.compareTo(account.getBalance()) > 0) {
            throw new WithdrawException("Insufficient Funds");
        }
    }
}
