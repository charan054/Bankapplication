package com.example.bankapplication.service;

import com.example.bankapplication.entity.Bank;
import com.example.bankapplication.entity.BankTransaction;
import com.example.bankapplication.entity.FixedDeposit;
import com.example.bankapplication.exception.InvalidRequestException;
import com.example.bankapplication.exception.UserNotFoundException;
import com.example.bankapplication.exception.WithdrawException;
import com.example.bankapplication.repository.BankRepository;
import com.example.bankapplication.repository.BankTransactionRepository;
import com.example.bankapplication.repository.FixedDepositRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * Fixed deposits: the customer locks money for 3, 6 or 12 months at the rate for that term. The money moves between the
 * customer's own savings balance and their deposit inside ONE database transaction, so it can never be in both places or
 * in neither. There is no scheduler in this system, so a deposit that has reached its end date is paid into the savings
 * balance the next time the customer looks at their deposits (or tries to break it) - same lazy approach as the other
 * time-based rules here. Ledger rows use their own actions ("FD Opened" / "FD Closed") so that moving money into a
 * deposit is not counted against the daily withdrawal limit.
 */
@Service
public class FixedDepositService {
    public static final String OPENED = "FD Opened";
    public static final String CLOSED = "FD Closed";
    // term in months -> annual rate in percent
    static final Map<Integer, BigDecimal> RATES = Map.of(
            3, new BigDecimal("6.00"),
            6, new BigDecimal("6.50"),
            12, new BigDecimal("7.00"));

    private final FixedDepositRepository deposits;
    private final BankRepository users;
    private final BankTransactionRepository ledger;
    private final Clock clock;
    private final ZoneId zone;
    private final BigDecimal minimum;

    public FixedDepositService(FixedDepositRepository deposits, BankRepository users, BankTransactionRepository ledger, Clock clock,
                               @Value("${bank.fixed-deposit.minimum:1000}") BigDecimal minimum,
                               @Value("${bank.fixed-deposit.zone:Asia/Kolkata}") String zone) {
        this.deposits = deposits;
        this.users = users;
        this.ledger = ledger;
        this.clock = clock;
        this.minimum = minimum;
        this.zone = ZoneId.of(zone);
    }

    public record RateView(int termMonths, BigDecimal ratePercent) {
    }

    public List<RateView> rates() {
        return RATES.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(e -> new RateView(e.getKey(), e.getValue())).toList();
    }

    /** Interest paid at the end of the term: simple interest, rounded to the paisa. */
    static BigDecimal interest(BigDecimal principal, BigDecimal ratePercent, int termMonths) {
        return principal.multiply(ratePercent).multiply(BigDecimal.valueOf(termMonths))
                .divide(BigDecimal.valueOf(1200), 2, RoundingMode.HALF_UP);
    }

    @Transactional
    public FixedDeposit open(long phno, BigDecimal amount, int termMonths) {
        BigDecimal rate = RATES.get(termMonths);
        if (rate == null) {
            throw new InvalidRequestException("Choose a term of 3, 6 or 12 months");
        }
        if (amount == null || amount.compareTo(minimum) < 0) {
            throw new InvalidRequestException("The smallest fixed deposit is INR " + minimum.toPlainString());
        }
        BigDecimal principal = amount.setScale(2, RoundingMode.HALF_UP);
        Bank account = account(phno);
        if (principal.compareTo(account.getBalance()) > 0) {
            throw new WithdrawException("Insufficient Funds");
        }
        account.setBalance(account.getBalance().subtract(principal));
        users.save(account);
        record(account, principal, OPENED);

        FixedDeposit fd = new FixedDeposit();
        fd.setPhno(phno);
        fd.setPrincipal(principal);
        fd.setRatePercent(rate);
        fd.setTermMonths(termMonths);
        fd.setOpenedAt(clock.instant());
        fd.setMaturesAt(clock.instant().atZone(zone).plusMonths(termMonths).toInstant());
        return deposits.save(fd);
    }

    /** The customer's deposits, newest first, after paying out any that have reached their end date. */
    @Transactional
    public List<FixedDeposit> list(long phno) {
        payMatured(phno);
        return deposits.findByPhnoOrderByIdDesc(phno);
    }

    /** Closes an active deposit early: the principal comes back, the interest does not. */
    @Transactional
    public FixedDeposit breakEarly(long phno, long id) {
        payMatured(phno);   // a deposit already past its end date is paid in full, not penalised
        FixedDeposit fd = deposits.findById(id).filter(d -> d.getPhno() == phno)
                .orElseThrow(() -> new InvalidRequestException("Fixed deposit not found"));
        if (!FixedDeposit.ACTIVE.equals(fd.getStatus())) {
            throw new InvalidRequestException("This fixed deposit is already closed");
        }
        close(fd, fd.getPrincipal(), FixedDeposit.BROKEN);
        return fd;
    }

    private void payMatured(long phno) {
        for (FixedDeposit fd : deposits.findByPhnoAndStatusAndMaturesAtLessThanEqual(phno, FixedDeposit.ACTIVE, clock.instant())) {
            close(fd, fd.getPrincipal().add(interest(fd.getPrincipal(), fd.getRatePercent(), fd.getTermMonths())), FixedDeposit.MATURED);
        }
    }

    private void close(FixedDeposit fd, BigDecimal payout, String status) {
        Bank account = account(fd.getPhno());
        account.setBalance(account.getBalance().add(payout));
        users.save(account);
        record(account, payout, CLOSED);
        fd.setStatus(status);
        fd.setPayout(payout);
        fd.setClosedAt(clock.instant());
        deposits.save(fd);
    }

    private Bank account(long phno) {
        Bank account = users.findByphno(phno);
        if (account == null) {
            throw new UserNotFoundException("User not found");
        }
        return account;
    }

    private void record(Bank account, BigDecimal amount, String action) {
        BankTransaction t = new BankTransaction();
        t.setPhno(account.getPhno());
        t.setUserId(account.getUserId());
        t.setAmount(amount);
        t.setAction(action);
        t.setBalance(account.getBalance());
        Long highest = ledger.findMaxTransactionId();
        t.setTransactionId(highest == null ? 100000 : highest + 1);
        t.setCreatedAt(clock.instant());
        ledger.save(t);
    }
}
