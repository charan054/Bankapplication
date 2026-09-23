package com.example.bankapplication.repository;

import com.example.bankapplication.entity.Bank;
import com.example.bankapplication.entity.BankTransaction;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// replace = ANY swaps the MySQL datasource in application.properties for an in-memory H2 database,
// so these tests can never touch your real "charan" schema.
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
class BankRepositoryTest {

    @Autowired
    private BankRepository bankRepository;
    @Autowired
    private BankTransactionRepository bankTransactionRepository;
    @Autowired
    private EntityManager entityManager;

    private Bank newBank(long acno, long phno, long aadhar, double balance) {
        Bank b = new Bank();
        b.setAcno(acno);
        b.setFirstName("Charan");
        b.setLastName("Kumar");
        b.setPhno(phno);
        b.setAadharNumber(aadhar);
        b.setBalance(java.math.BigDecimal.valueOf(balance));
        return b;
    }

    private BankTransaction newTxn(long transactionId, long userId, long phno, String action, double amount, double balance) {
        BankTransaction t = new BankTransaction();
        t.setTransactionId(transactionId);
        t.setUserId(userId);
        t.setPhno(phno);
        t.setAction(action);
        t.setAmount(java.math.BigDecimal.valueOf(amount));
        t.setBalance(java.math.BigDecimal.valueOf(balance));
        return t;
    }

    // BigDecimal.equals() is scale-sensitive ("500" != "500.00" even though they're numerically equal);
    // compareTo() is not, so it is the right comparison for a value that was never explicitly scaled.
    private void assertMoney(double expected, BigDecimal actual) {
        assertEquals(0, BigDecimal.valueOf(expected).compareTo(actual), () -> expected + " != " + actual);
    }

    /** Push pending SQL to the DB and empty the cache so the next read really hits the database. */
    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    // ---------- derived finders ----------

    @Test
    void findByphno_returnsMatchingUser() {
        bankRepository.save(newBank(1000000000L, 9876543210L, 111111111111L, 500));
        flushAndClear();

        Bank found = bankRepository.findByphno(9876543210L);

        assertNotNull(found);
        assertEquals(1000000000L, found.getAcno());
        assertMoney(500, found.getBalance());
    }

    @Test
    void findByphno_unknownNumber_returnsNull() {
        assertNull(bankRepository.findByphno(9999999999L));
    }

    @Test
    void findByacno_returnsMatchingUser() {
        bankRepository.save(newBank(1000000001L, 9123456789L, 222222222222L, 0));
        flushAndClear();

        Bank found = bankRepository.findByacno(1000000001L);

        assertNotNull(found);
        assertEquals(9123456789L, found.getPhno());
    }

    @Test
    void findByacno_unknownAccount_returnsNull() {
        assertNull(bankRepository.findByacno(1234L));
    }

    // ---------- existence checks and the max account number, used by register()/updatePhno() ----------

    @Test
    void existsByPhno_trueOnlyForARegisteredNumber() {
        bankRepository.save(newBank(1000000000L, 9876543210L, 111111111111L, 0));

        assertTrue(bankRepository.existsByPhno(9876543210L));
        assertFalse(bankRepository.existsByPhno(9999999999L));
    }

    @Test
    void existsByAadharNumber_trueOnlyForARegisteredAadhar() {
        bankRepository.save(newBank(1000000000L, 9876543210L, 111111111111L, 0));

        assertTrue(bankRepository.existsByAadharNumber(111111111111L));
        assertFalse(bankRepository.existsByAadharNumber(999999999999L));
    }

    @Test
    void findMaxAcno_isNullWhenThereAreNoUsers() {
        assertNull(bankRepository.findMaxAcno());
    }

    @Test
    void findMaxAcno_isTheHighestAccountNumber_notTheLastRowInserted() {
        bankRepository.save(newBank(1000000005L, 9876543210L, 111111111111L, 0));
        bankRepository.save(newBank(1000000002L, 9123456789L, 222222222222L, 0));

        assertEquals(1000000005L, bankRepository.findMaxAcno());
    }

    // ---------- persistence details ----------

    @Test
    void save_generatesUserIdAutomatically() {
        Bank saved = bankRepository.save(newBank(1000000000L, 9876543210L, 111111111111L, 0));

        assertTrue(saved.getUserId() > 0);
    }

    @Test
    void save_persistsAllFieldsIncludingLargeAadhar() {
        bankRepository.save(newBank(1000000000L, 9876543210L, 987654321098L, 1234.5));
        flushAndClear();

        Bank found = bankRepository.findByphno(9876543210L);

        assertEquals("Charan", found.getFirstName());
        assertEquals("Kumar", found.getLastName());
        assertEquals(987654321098L, found.getAadharNumber());   // 12 digits: needs a long, not an int
        assertMoney(1234.5, found.getBalance());
    }

    // The service builds new account numbers from the LAST row of findAll(), so it depends on this order.
    @Test
    void findAll_returnsRowsInInsertionOrder() {
        bankRepository.save(newBank(1000000000L, 9000000001L, 111111111111L, 0));
        bankRepository.save(newBank(1000000001L, 9000000002L, 222222222222L, 0));
        bankRepository.save(newBank(1000000002L, 9000000003L, 333333333333L, 0));
        flushAndClear();

        List<Bank> all = bankRepository.findAll();

        assertEquals(3, all.size());
        assertEquals(1000000002L, all.get(all.size() - 1).getAcno());
    }

    // ---------- unique constraints (the last line of defence against duplicate sign-ups) ----------

    @Test
    void database_rejectsTwoUsersWithTheSamePhoneNumber() {
        bankRepository.saveAndFlush(newBank(1000000000L, 9876543210L, 111111111111L, 0));

        assertThrows(DataIntegrityViolationException.class,
                () -> bankRepository.saveAndFlush(newBank(1000000001L, 9876543210L, 222222222222L, 0)));
    }

    @Test
    void database_rejectsTwoUsersWithTheSameAadharNumber() {
        bankRepository.saveAndFlush(newBank(1000000000L, 9876543210L, 111111111111L, 0));

        assertThrows(DataIntegrityViolationException.class,
                () -> bankRepository.saveAndFlush(newBank(1000000001L, 9123456789L, 111111111111L, 0)));
    }

    @Test
    void database_rejectsTwoUsersWithTheSameAccountNumber() {
        bankRepository.saveAndFlush(newBank(1000000000L, 9876543210L, 111111111111L, 0));

        assertThrows(DataIntegrityViolationException.class,
                () -> bankRepository.saveAndFlush(newBank(1000000000L, 9123456789L, 222222222222L, 0)));
    }

    @Test
    void database_rejectsTwoTransactionsWithTheSameTransactionId() {
        Bank bank = bankRepository.saveAndFlush(newBank(1000000000L, 9876543210L, 111111111111L, 0));
        bankTransactionRepository.saveAndFlush(newTxn(100000, bank.getUserId(), 9876543210L, "Credit", 100, 100));

        assertThrows(DataIntegrityViolationException.class,
                () -> bankTransactionRepository.saveAndFlush(newTxn(100000, bank.getUserId(), 9876543210L, "Credit", 50, 150)));
    }

    // ---------- optimistic locking (@Version): stops two simultaneous updates from silently overwriting each other ----------

    @Test
    void updatingAStaleCopy_isRejected_insteadOfOverwritingNewerData() {
        bankRepository.saveAndFlush(newBank(1000000000L, 9876543210L, 111111111111L, 1000));
        flushAndClear();

        // Request A and request B both read the account while its balance is 1000 ...
        Bank staleCopyOfB = bankRepository.findByphno(9876543210L);
        entityManager.detach(staleCopyOfB);
        Bank copyOfA = bankRepository.findByphno(9876543210L);

        // ... A commits a deposit first ...
        copyOfA.setBalance(java.math.BigDecimal.valueOf(1500));
        bankRepository.saveAndFlush(copyOfA);

        // ... so B, still holding the old balance, must NOT be allowed to overwrite it.
        staleCopyOfB.setBalance(java.math.BigDecimal.valueOf(1200));
        assertThrows(ObjectOptimisticLockingFailureException.class, () -> bankRepository.saveAndFlush(staleCopyOfB));
    }

    @Test
    void everyUpdate_incrementsTheVersion() {
        Bank saved = bankRepository.saveAndFlush(newBank(1000000000L, 9876543210L, 111111111111L, 0));
        assertEquals(0, saved.getVersion());

        saved.setBalance(java.math.BigDecimal.valueOf(100));
        bankRepository.saveAndFlush(saved);
        flushAndClear();

        assertEquals(1, bankRepository.findByphno(9876543210L).getVersion());
    }

    @Test
    void database_allowsUsersWhoDifferOnBothPhoneAndAadhar() {
        bankRepository.saveAndFlush(newBank(1000000000L, 9876543210L, 111111111111L, 0));
        bankRepository.saveAndFlush(newBank(1000000001L, 9123456789L, 222222222222L, 0));

        assertEquals(2, bankRepository.count());
    }

    // ---------- Bank <-> BankTransaction: no ORM relationship, deliberately (see Bank.java) ----------

    @Test
    void deletingUser_doesNotDeleteTheirTransactions() {
        // The ledger is a financial record: closing an account must not erase what it ever did.
        Bank bank = bankRepository.save(newBank(1000000000L, 9876543210L, 111111111111L, 0));
        bankTransactionRepository.save(newTxn(100000, bank.getUserId(), 9876543210L, "Credit", 500, 500));
        flushAndClear();

        bankRepository.delete(bankRepository.findByphno(9876543210L));
        flushAndClear();

        assertNull(bankRepository.findByphno(9876543210L));
        assertEquals(1, bankTransactionRepository.count());
    }
}
