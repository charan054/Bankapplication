package com.example.bankapplication.service;

import com.example.bankapplication.repository.BankTransactionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LegacyTransactionTimestampBackfillTest {

    private static final Instant NOW = Instant.parse("2026-09-23T10:00:00Z");

    private static class FixedClock extends Clock {
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return NOW; }
    }

    @Mock
    private BankTransactionRepository transactions;

    @Test
    void run_stampsEveryRowMissingACreatedAt_withTheCurrentMoment() {
        when(transactions.backfillMissingCreatedAt(NOW)).thenReturn(3);

        new LegacyTransactionTimestampBackfill(transactions, new FixedClock()).run(null);

        verify(transactions).backfillMissingCreatedAt(eq(NOW));
    }

    @Test
    void run_nothingToBackfill_stillCallsThrough_withoutError() {
        when(transactions.backfillMissingCreatedAt(NOW)).thenReturn(0);

        new LegacyTransactionTimestampBackfill(transactions, new FixedClock()).run(null);

        verify(transactions).backfillMissingCreatedAt(eq(NOW));
    }
}
