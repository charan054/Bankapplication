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
    void findByIdempotencyKey_findsIt() {
        repository.save(transfer("key-1", 9876543210L, 9123456789L, 250));

        Transfer found = repository.findByIdempotencyKey("key-1").orElseThrow();

        assertEquals(9876543210L, found.getPayerPhno());
        assertEquals(9123456789L, found.getReceiverPhno());
        assertEquals(0, BigDecimal.valueOf(250).compareTo(found.getAmount()));
    }

    @Test
    void findByIdempotencyKey_unknownKey_isEmpty() {
        assertTrue(repository.findByIdempotencyKey("nothing").isEmpty());
    }

    @Test
    void database_rejectsTwoTransfersWithTheSameIdempotencyKey() {
        repository.saveAndFlush(transfer("key-1", 9876543210L, 9123456789L, 250));

        assertThrows(DataIntegrityViolationException.class,
                () -> repository.saveAndFlush(transfer("key-1", 9123456789L, 9876543210L, 999)));
    }

    @Test
    void differentKeys_areBothAllowed() {
        repository.save(transfer("key-1", 9876543210L, 9123456789L, 250));
        repository.save(transfer("key-2", 9876543210L, 9123456789L, 250));

        assertEquals(2, repository.count());
    }
}
