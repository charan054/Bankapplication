package com.example.bankapplication.service;

import com.example.bankapplication.entity.BankTransaction;
import com.example.bankapplication.exception.WithdrawException;
import com.example.bankapplication.repository.BankTransactionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * "Where did my money go": one row per month of what came in and what went out, newest month first. The bank records only
 * Credit and Debit (there is no category on a transaction), so the summary is money in, money out, how many entries and the
 * biggest single debit.
 */
@Service
public class SpendingSummaryService {
    static final int MAX_MONTHS = 12;
    static final int DEFAULT_MONTHS = 6;
    private static final int MAX_ROWS = 20000;

    public record Month(String month, BigDecimal moneyIn, BigDecimal moneyOut, BigDecimal net, int entries, BigDecimal biggestDebit) {
    }

    private final BankTransactionRepository transactions;
    private final Clock clock;
    private final ZoneId zone;

    public SpendingSummaryService(BankTransactionRepository transactions, Clock clock,
                                  @Value("${bank.statement.zone:Asia/Kolkata}") String zone) {
        this.transactions = transactions;
        this.clock = clock;
        this.zone = ZoneId.of(zone);
    }

    public List<Month> summary(long phno, Integer months) {
        int count = months == null ? DEFAULT_MONTHS : months;
        if (count < 1 || count > MAX_MONTHS) {
            throw new WithdrawException("Ask for between 1 and " + MAX_MONTHS + " months.");
        }
        YearMonth latest = YearMonth.now(clock.withZone(zone));
        YearMonth earliest = latest.minusMonths(count - 1L);
        Instant from = earliest.atDay(1).atStartOfDay(zone).toInstant();
        Instant to = latest.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().minusMillis(1);
        List<BankTransaction> rows = transactions.findByPhno(phno, from, to, PageRequest.of(0, MAX_ROWS, Sort.by("createdAt").ascending())).getContent();

        List<Month> result = new ArrayList<>();
        for (YearMonth m = latest; !m.isBefore(earliest); m = m.minusMonths(1)) {
            BigDecimal in = BigDecimal.ZERO;
            BigDecimal out = BigDecimal.ZERO;
            BigDecimal biggest = BigDecimal.ZERO;
            int entries = 0;
            for (BankTransaction t : rows) {
                if (t.getCreatedAt() == null || !YearMonth.from(t.getCreatedAt().atZone(zone)).equals(m)) {
                    continue;
                }
                entries++;
                if ("Debit".equalsIgnoreCase(t.getAction())) {
                    out = out.add(t.getAmount());
                    biggest = biggest.max(t.getAmount());
                } else {
                    in = in.add(t.getAmount());
                }
            }
            result.add(new Month(m.toString(), in, out, in.subtract(out), entries, biggest));
        }
        return result;
    }
}
