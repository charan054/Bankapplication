package com.example.bankapplication.service;

import com.example.bankapplication.entity.BankTransaction;
import com.example.bankapplication.exception.WithdrawException;
import com.example.bankapplication.repository.BankTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SpendingSummaryServiceTest {
    private static final long PHNO = 9876543210L;

    private BankTransactionRepository transactions;
    private SpendingSummaryService service;

    @BeforeEach
    void setUp() {
        transactions = mock(BankTransactionRepository.class);
        service = new SpendingSummaryService(transactions, Clock.fixed(Instant.parse("2026-10-24T10:00:00Z"), ZoneOffset.UTC), "Asia/Kolkata");
    }

    private static BankTransaction tx(String action, String amount, String at) {
        BankTransaction t = new BankTransaction();
        t.setAction(action);
        t.setAmount(new BigDecimal(amount));
        t.setCreatedAt(at == null ? null : Instant.parse(at));
        return t;
    }

    @Test
    void sumsMoneyInAndOutPerMonthNewestFirstIncludingEmptyMonths() {
        when(transactions.findByPhno(any(Long.class), any(), any(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(
                tx("Credit", "1000.00", "2026-10-02T05:00:00Z"),
                tx("Debit", "250.50", "2026-10-10T05:00:00Z"),
                tx("Debit", "100.00", "2026-10-11T05:00:00Z"),
                tx("Credit", "40.00", "2026-08-15T05:00:00Z"),
                tx("Debit", "5.00", null))));

        List<SpendingSummaryService.Month> result = service.summary(PHNO, 3);

        assertEquals(List.of("2026-10", "2026-09", "2026-08"), result.stream().map(SpendingSummaryService.Month::month).toList());
        SpendingSummaryService.Month october = result.get(0);
        assertEquals(new BigDecimal("1000.00"), october.moneyIn());
        assertEquals(new BigDecimal("350.50"), october.moneyOut());
        assertEquals(new BigDecimal("649.50"), october.net());
        assertEquals(new BigDecimal("250.50"), october.biggestDebit());
        assertEquals(3, october.entries());
        assertEquals(0, result.get(1).entries());
        assertEquals(new BigDecimal("40.00"), result.get(2).moneyIn());
    }

    @Test
    void aMonthLateInTheEveningInIndiaBelongsToThatMonthNotTheNextUtcOne() {
        // 2026-09-30 20:00 UTC is 01:30 on 1 October in India
        when(transactions.findByPhno(any(Long.class), any(), any(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(
                tx("Credit", "10.00", "2026-09-30T20:00:00Z"))));

        List<SpendingSummaryService.Month> result = service.summary(PHNO, 2);

        assertEquals(new BigDecimal("10.00"), result.get(0).moneyIn());
        assertEquals(BigDecimal.ZERO, result.get(1).moneyIn());
    }

    @Test
    void rejectsAnUnreasonableNumberOfMonths() {
        assertThrows(WithdrawException.class, () -> service.summary(PHNO, 0));
        assertThrows(WithdrawException.class, () -> service.summary(PHNO, 13));
    }
}
