package com.example.bankapplication.service;

import com.example.bankapplication.entity.Bank;
import com.example.bankapplication.entity.BankTransaction;
import com.example.bankapplication.exception.WithdrawException;
import com.example.bankapplication.repository.BankRepository;
import com.example.bankapplication.repository.BankTransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Emails a customer their statement for one calendar month (a CSV attachment plus totals in the body). It goes out
 * automatically on the 1st for the month just ended when bank.statement.enabled=true (off by default - it emails every
 * customer), and a customer can ask for any month on demand (POST /bank/statement/email). Only to the address on file.
 */
@Service
public class StatementEmailService {
    private static final Logger log = LoggerFactory.getLogger(StatementEmailService.class);
    static final int MAX_ROWS = 5000;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);

    private final BankRepository users;
    private final BankTransactionRepository transactions;
    private final MailService mailService;
    private final Clock clock;
    private final ZoneId zone;
    private final boolean enabled;

    public StatementEmailService(BankRepository users, BankTransactionRepository transactions, MailService mailService, Clock clock,
                                 @Value("${bank.statement.zone:Asia/Kolkata}") String zone,
                                 @Value("${bank.statement.enabled:false}") boolean enabled) {
        this.users = users;
        this.transactions = transactions;
        this.mailService = mailService;
        this.clock = clock;
        this.zone = ZoneId.of(zone);
        this.enabled = enabled;
    }

    /** Emails one customer their statement for the month; returns false when it could not be sent (no email on file, mail down). */
    public boolean sendFor(Bank user, YearMonth month) {
        if (user.getEmail() == null || user.getEmail().isBlank()) {
            return false;
        }
        Instant from = month.atDay(1).atStartOfDay(zone).toInstant();
        Instant to = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().minusMillis(1);
        List<BankTransaction> rows = transactions.findByPhno(user.getPhno(), from, to,
                PageRequest.of(0, MAX_ROWS, Sort.by("createdAt").ascending())).getContent();

        BigDecimal credits = BigDecimal.ZERO;
        BigDecimal debits = BigDecimal.ZERO;
        StringBuilder csv = new StringBuilder("Transaction ID,Date,Type,Amount,Balance after\n");
        for (BankTransaction t : rows) {
            if ("Debit".equalsIgnoreCase(t.getAction())) {
                debits = debits.add(t.getAmount());
            } else {
                credits = credits.add(t.getAmount());
            }
            csv.append(t.getTransactionId()).append(',')
                    .append(t.getCreatedAt() == null ? "" : STAMP.format(t.getCreatedAt().atZone(zone))).append(',')
                    .append(t.getAction()).append(',')
                    .append(t.getAmount().toPlainString()).append(',')
                    .append(t.getBalance() == null ? "" : t.getBalance().toPlainString()).append('\n');
        }
        String maskedPhone = "XXXXXX" + String.valueOf(user.getPhno()).substring(6);
        String body = "Your statement for " + month + " (account " + maskedPhone + ")\n\n"
                + "Transactions: " + rows.size() + (rows.size() >= MAX_ROWS ? " (first " + MAX_ROWS + " shown)" : "") + "\n"
                + "Money in:  INR " + credits.toPlainString() + "\n"
                + "Money out: INR " + debits.toPlainString() + "\n"
                + "Current balance: INR " + user.getBalance().toPlainString() + "\n\n"
                + "The full list is attached as a CSV file you can open in Excel.";
        return mailService.sendWithAttachment(user.getEmail(), "Your account statement for " + month, body,
                "statement-" + month + ".csv", csv.toString().getBytes(StandardCharsets.UTF_8), "text/csv");
    }

    /** On-demand: the caller asks for a month that has already started (never a future one). */
    public void sendOnRequest(long phno, YearMonth month) {
        Bank user = users.findByphno(phno);
        if (user == null || user.getEmail() == null || user.getEmail().isBlank()) {
            throw new WithdrawException("Add an email address to your account first - the statement is sent there.");
        }
        if (month.isAfter(YearMonth.now(clock.withZone(zone)))) {
            throw new WithdrawException("That month has not started yet.");
        }
        if (!sendFor(user, month)) {
            throw new WithdrawException("The statement could not be emailed right now. Please try again later.");
        }
    }

    /** The 1st of each month at 06:00: last month's statement to everyone with an email. */
    @Scheduled(cron = "${bank.statement.cron:0 0 6 1 * *}", zone = "${bank.statement.zone:Asia/Kolkata}")
    public void sendMonthlyStatements() {
        if (!enabled) {
            return;
        }
        YearMonth last = YearMonth.now(clock.withZone(zone)).minusMonths(1);
        int sent = 0;
        int skipped = 0;
        for (Bank user : users.findAll()) {
            try {
                if (sendFor(user, last)) {
                    sent++;
                } else {
                    skipped++;
                }
            } catch (RuntimeException e) {
                skipped++;
                log.warn("Monthly statement for account {} failed: {}", user.getUserId(), e.getMessage());
            }
        }
        log.info("Monthly statements for {}: {} sent, {} skipped (no email or send failed)", last, sent, skipped);
    }
}
