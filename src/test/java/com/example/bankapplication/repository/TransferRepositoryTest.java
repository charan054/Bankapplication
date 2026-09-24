package com.example.bankapplication.repository;

import com.example.bankapplication.entity.Transfer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
class TransferRepositoryTest {

    @Autowired
    private TransferRepository repository;

    private Transfer transfer(String key, long payer, long receiver, double amount) {
        Transfer t = new Transfer();
        t.setIdempotencyKey(key);
        t.setPayerPhno(payer);
        t.setReceiverPhno(receiver);
        t.setAmount(BigDecimal.valueOf(amount));
        t.setDebitTransactionId(100000);
        t.setCreditTransactionId(100001);
        t.setCreatedAt(Instant.parse("2026-09-23T10:00:00Z"));
        return t;
    }

    @Test
    void findByPayerPhnoAndIdempotencyKey_findsIt() {
        repository.save(transfer("key-1", 9876543210L, 9123456789L, 250));

        Transfer found = repository.findByPayerPhnoAndIdempotencyKey(9876543210L, "key-1").orElseThrow();

        assertEquals(9876543210L, found.getPayerPhno());
        assertEquals(9123456789L, found.getReceiverPhno());
        assertEquals(0, BigDecimal.valueOf(250).compareTo(found.getAmount()));
    }

    @Test
    void findByPayerPhnoAndIdempotencyKey_unknownKey_isEmpty() {
        assertTrue(repository.findByPayerPhnoAndIdempotencyKey(9876543210L, "nothing").isEmpty());
    }

    // Scoped to the given payer: someone else's transfer using the identical key string must never be found by
    // this lookup, the same way it must never be blocked by it either (see the tests below).
    @Test
    void findByPayerPhnoAndIdempotencyKey_isScopedToTheGivenPayer_notGlobal() {
        repository.save(transfer("same-key", 9876543210L, 9123456789L, 250));
        repository.save(transfer("same-key", 9123456789L, 9876543210L, 999));

        assertEquals(0, BigDecimal.valueOf(250).compareTo(
                repository.findByPayerPhnoAndIdempotencyKey(9876543210L, "same-key").orElseThrow().getAmount()));
        assertEquals(0, BigDecimal.valueOf(999).compareTo(
                repository.findByPayerPhnoAndIdempotencyKey(9123456789L, "same-key").orElseThrow().getAmount()));
    }

    @Test
    void database_rejectsTwoTransfersFromTheSamePayerWithTheSameIdempotencyKey() {
        repository.saveAndFlush(transfer("key-1", 9876543210L, 9123456789L, 250));

        assertThrows(DataIntegrityViolationException.class,
                () -> repository.saveAndFlush(transfer("key-1", 9876543210L, 9000000001L, 999)));
    }

    // The fix this class exists to verify: two DIFFERENT payers reusing the same key string (their own
    // key-generation schemes just happened to collide) is not a conflict - each is its own, unrelated transfer.
    @Test
    void database_allowsTwoDifferentPayersToUseTheSameIdempotencyKey() {
        repository.saveAndFlush(transfer("key-1", 9876543210L, 9123456789L, 250));
        repository.saveAndFlush(transfer("key-1", 9123456789L, 9876543210L, 999));

        assertEquals(2, repository.count());
    }

    @Test
    void differentKeys_areBothAllowed() {
        repository.save(transfer("key-1", 9876543210L, 9123456789L, 250));
        repository.save(transfer("key-2", 9876543210L, 9123456789L, 250));

        assertEquals(2, repository.count());
    }
}
