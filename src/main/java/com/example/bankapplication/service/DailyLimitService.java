package com.example.bankapplication.service;

import com.example.bankapplication.entity.Bank;
import com.example.bankapplication.exception.UserNotFoundException;
import com.example.bankapplication.exception.WithdrawException;
import com.example.bankapplication.repository.BankRepository;
import com.example.bankapplication.repository.BankTransactionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * A cap on how much can leave an account in one day (every Debit counts: withdrawals and PhonePe payments). The customer
 * can lower it any time; RAISING it only takes effect after a delay (default 24 hours), so someone who gets into an
 * account cannot lift the cap and drain it in the same sitting. The owner is emailed about every change.
 */
@Service
public class DailyLimitService {
    public record LimitView(BigDecimal dailyLimit, BigDecimal usedToday, BigDecimal remainingToday,
                            BigDecimal pendingLimit, Instant pendingLimitEffectiveAt, BigDecimal maxAllowed, boolean isDefault) {
    }

    private final BankRepository users;
    private final BankTransactionRepository transactions;
    private final Clock clock;
    private final BigDecimal defaultLimit;
    private final BigDecimal maxLimit;
    private final Duration raiseDelay;
    private final ZoneId zone;
    private SecurityAlertService alerts;

    public DailyLimitService(BankRepository users, BankTransactionRepository transactions, Clock clock,
                             @Value("${bank.daily-limit.default:100000}") BigDecimal defaultLimit,
                             @Value("${bank.daily-limit.max:1000000}") BigDecimal maxLimit,
                             @Value("${bank.daily-limit.raise-delay-hours:24}") long raiseDelayHours,
                             @Value("${bank.daily-limit.zone:Asia/Kolkata}") String zone) {
        this.users = users;
        this.transactions = transactions;
        this.clock = clock;
        this.defaultLimit = defaultLimit;
        this.maxLimit = maxLimit;
        this.raiseDelay = Duration.ofHours(raiseDelayHours);
        this.zone = ZoneId.of(zone);
    }

    @Autowired(required = false)
    void setAlerts(SecurityAlertService alerts) {
        this.alerts = alerts;
    }

    /** The limit in force right now, applying a raise whose waiting time is over. */
    BigDecimal effectiveLimit(Bank user, Instant now) {
        if (user.getPendingDailyLimit() != null && user.getPendingLimitEffectiveAt() != null
                && !user.getPendingLimitEffectiveAt().isAfter(now)) {
            user.setDailyLimit(user.getPendingDailyLimit());
            user.setPendingDailyLimit(null);
            user.setPendingLimitEffectiveAt(null);
            users.save(user);
        }
        return user.getDailyLimit() != null ? user.getDailyLimit() : defaultLimit;
    }

    private BigDecimal usedToday(long phno, Instant now) {
        Instant startOfDay = now.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant();
        return transactions.sumDebitsSince(phno, startOfDay);
    }

    /** Called before every debit: refuses it if it would take today's total past the limit. */
    @Transactional
    public void requireWithinLimit(Bank user, BigDecimal amount) {
        Instant now = clock.instant();
        BigDecimal limit = effectiveLimit(user, now);
        BigDecimal used = usedToday(user.getPhno(), now);
        if (used.add(amount).compareTo(limit) > 0) {
            BigDecimal remaining = limit.subtract(used).max(BigDecimal.ZERO);
            throw new WithdrawException("This would go over your daily limit of INR " + limit.toPlainString()
                    + " (INR " + remaining.toPlainString() + " left today). You can change your limit under Daily Limit.");
        }
    }

    @Transactional
    public LimitView view(long phno) {
        Bank user = users.findByphno(phno);
        if (user == null) {
            throw new UserNotFoundException("User not found");
        }
        Instant now = clock.instant();
        BigDecimal limit = effectiveLimit(user, now);
        BigDecimal used = usedToday(phno, now);
        return new LimitView(limit, used, limit.subtract(used).max(BigDecimal.ZERO), user.getPendingDailyLimit(),
                user.getPendingLimitEffectiveAt(), maxLimit, user.getDailyLimit() == null);
    }

    @Transactional
    public LimitView change(long phno, BigDecimal requested) {
        if (requested == null || requested.signum() <= 0) {
            throw new WithdrawException("The daily limit must be more than zero.");
        }
        BigDecimal wanted = requested.setScale(2, RoundingMode.HALF_UP);
        if (wanted.compareTo(maxLimit) > 0) {
            throw new WithdrawException("The daily limit can be at most INR " + maxLimit.toPlainString() + ".");
        }
        Bank user = users.findByphno(phno);
        if (user == null) {
            throw new UserNotFoundException("User not found");
        }
        Instant now = clock.instant();
        BigDecimal current = effectiveLimit(user, now);
        if (wanted.compareTo(current) <= 0) {
            user.setDailyLimit(wanted);                 // lowering (or keeping) is immediate and cancels any waiting raise
            user.setPendingDailyLimit(null);
            user.setPendingLimitEffectiveAt(null);
        } else {
            user.setPendingDailyLimit(wanted);          // raising waits
            user.setPendingLimitEffectiveAt(now.plus(raiseDelay));
        }
        users.save(user);
        if (alerts != null) {
            alerts.alert(SecurityAlertService.Kind.LIMIT_CHANGED, user, user.getEmail(), now);
        }
        return view(phno);
    }
}
