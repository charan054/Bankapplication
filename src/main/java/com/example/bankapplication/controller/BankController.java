package com.example.bankapplication.controller;

import com.example.bankapplication.configuration.CustomerAuthInterceptor;
import com.example.bankapplication.dto.AmountRequest;
import com.example.bankapplication.dto.BankDto;
import com.example.bankapplication.dto.LoginRequest;
import com.example.bankapplication.dto.LoginResponse;
import com.example.bankapplication.dto.ForgotPinRequest;
import com.example.bankapplication.dto.PageResponse;
import com.example.bankapplication.dto.RegisterRequest;
import com.example.bankapplication.dto.ResetPinRequest;
import com.example.bankapplication.dto.SetPinRequest;
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
 *   <li>public — /login, /save;</li>
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

    // ---------- public ----------

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return bankService.login(request.phno(), request.pin());
    }

    @PostMapping("/save")
    public Bank save(@Valid @RequestBody RegisterRequest request) {
        return bankService.register(request);
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
        return bankService.withdrawByphno(phno, amount);
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
