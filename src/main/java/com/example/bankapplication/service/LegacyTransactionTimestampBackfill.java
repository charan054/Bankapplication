package com.example.bankapplication.service;

import com.example.bankapplication.repository.BankTransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * BankTransaction had no createdAt column at all until it was added, so pre-existing rows have no real record of
 * when they happened. Rather than inventing false precision about their order, every such row is stamped once
 * with the moment this first runs. Safe to run on every startup: after the first run there are no more rows with
 * a null createdAt, so later runs update nothing.
 */
@Component
public class LegacyTransactionTimestampBackfill implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(LegacyTransactionTimestampBackfill.class);

    private final BankTransactionRepository transactions;
    private final Clock clock;

    public LegacyTransactionTimestampBackfill(BankTransactionRepository transactions, Clock clock) {
        this.transactions = transactions;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        int backfilled = transactions.backfillMissingCreatedAt(clock.instant());
        if (backfilled > 0) {
            log.info("Backfilled createdAt on {} pre-existing transaction(s)", backfilled);
        }
    }
}
