package com.example.bankapplication.service;

import com.example.bankapplication.dto.BankDto;
import com.example.bankapplication.dto.LoginResponse;
import com.example.bankapplication.dto.PageResponse;
import com.example.bankapplication.dto.RegisterRequest;
import com.example.bankapplication.entity.Bank;
import com.example.bankapplication.entity.BankTransaction;
import com.example.bankapplication.entity.Transfer;
import com.example.bankapplication.exception.AccountLockedException;
import com.example.bankapplication.exception.InvalidCredentialsException;
import com.example.bankapplication.exception.InvalidRequestException;
import com.example.bankapplication.exception.MobileNumberException;
import com.example.bankapplication.exception.PinNotSetException;
import com.example.bankapplication.exception.UserExistException;
import com.example.bankapplication.exception.UserNotFoundException;
import com.example.bankapplication.exception.WithdrawException;
import com.example.bankapplication.kafka.BankKafkaProducer;
import com.example.bankapplication.repository.BankRepository;
import com.example.bankapplication.repository.BankTransactionRepository;
import com.example.bankapplication.repository.TransferRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BankServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-23T10:00:00Z");
    private static final String STORED_HASH = "bcrypt-hash-of-1234";

    @Mock
    private BankRepository userRepository;
    @Mock
    private BankTransactionRepository bankTransactionRepository;
    @Mock
    private TransferRepository transferRepository;
    @Mock
    private BankKafkaProducer bankKafkaProducer;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private SessionService sessionService;

    @InjectMocks
    private BankService bankService;

    /** A clock the test can move, to expire a lockout without waiting. */
    private static class MovableClock extends Clock {
        Instant now = NOW;
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private final MovableClock clock = new MovableClock();

    @org.junit.jupiter.api.BeforeEach
    void wireClock() {
        // BankService's @Autowired Clock field is set here so tests can move time (MockitoExtension's field
        // injection has already run by @BeforeEach time, so this simply replaces the mock Clock with a fake one).
        org.springframework.test.util.ReflectionTestUtils.setField(bankService, "clock", clock);
    }

    // ---------- helpers ----------

    private Bank bank(long acno, long phno, long aadhar, double balance) {
        Bank b = new Bank();
        b.setUserId(1);
        b.setAcno(acno);
        b.setFirstName("Charan");
        b.setLastName("Kumar");
        b.setPhno(phno);
        b.setAadharNumber(aadhar);
        b.setBalance(BigDecimal.valueOf(balance));
        return b;
    }

    // BigDecimal.valueOf(double) parses via Double.toString, so money(500) prints "500.0" - exactly what the
    // Inr messages below already expect, since these unit tests call BankService directly (bypassing the
    // controller's requirePositiveAmount, which is what normalizes a real request to 2 decimal places).
    private static BigDecimal money(double v) {
        return BigDecimal.valueOf(v);
    }

    // BigDecimal.equals() is scale-sensitive ("100" != "100.00" even though numerically equal); compareTo() is not.
    private static void assertMoney(double expected, BigDecimal actual) {
        assertEquals(0, BigDecimal.valueOf(expected).compareTo(actual), () -> expected + " != " + actual);
    }

    private Bank bankWithPin(long acno, long phno, long aadhar, double balance) {
        Bank b = bank(acno, phno, aadhar, balance);
        b.setPinHash(STORED_HASH);
        return b;
    }

    private BankTransaction txnWithId(long transactionId) {
        BankTransaction t = new BankTransaction();
        t.setTransactionId(transactionId);
        return t;
    }

    private RegisterRequest registerRequest(long phno, long aadhar) {
        return new RegisterRequest("Charan", "Kumar", aadhar, phno, "1234");
    }

    // ---------- register(): account creation ----------

    @Test
    void register_firstUser_getsAccountNumber1000000000() {
        when(userRepository.findMaxAcno()).thenReturn(null);   // no users yet
        when(userRepository.save(any(Bank.class))).thenAnswer(inv -> inv.getArgument(0));
        when(passwordEncoder.encode("1234")).thenReturn(STORED_HASH);

        Bank saved = bankService.register(registerRequest(9876543210L, 123456789012L));

        assertEquals(1000000000L, saved.getAcno());
        assertEquals(STORED_HASH, saved.getPinHash());
    }

    @Test
    void register_hashesThePin_neverStoresItInPlainText() {
        when(userRepository.save(any(Bank.class))).thenAnswer(inv -> inv.getArgument(0));
        when(passwordEncoder.encode("1234")).thenReturn(STORED_HASH);

        Bank saved = bankService.register(registerRequest(9876543210L, 123456789012L));

        verify(passwordEncoder).encode("1234");
        assertEquals(STORED_HASH, saved.getPinHash());
    }

    @Test
    void register_nextUser_getsLastAccountNumberPlusOne() {
        when(userRepository.findMaxAcno()).thenReturn(1000000005L);
        when(userRepository.save(any(Bank.class))).thenAnswer(inv -> inv.getArgument(0));
        when(passwordEncoder.encode(any())).thenReturn(STORED_HASH);

        Bank saved = bankService.register(registerRequest(9876543210L, 123456789012L));

        assertEquals(1000000006L, saved.getAcno());
    }

    @ParameterizedTest
    @ValueSource(longs = {
            987654321L,      // 9 digits  -> too short
            98765432101L,    // 11 digits -> too long
            5876543210L,     // 10 digits but starts with 5 (must be 6-9)
            1234567890L      // 10 digits but starts with 1
    })
    void register_invalidMobileNumber_throwsAndDoesNotSave(long badPhone) {
        MobileNumberException ex = assertThrows(MobileNumberException.class,
                () -> bankService.register(registerRequest(badPhone, 123456789012L)));

        assertEquals("Invalid mobile number", ex.getMessage());
        verifyNoInteractions(userRepository);
    }

    @ParameterizedTest
    @ValueSource(longs = {
            12345678901L,     // 11 digits
            1234567890123L    // 13 digits
    })
    void register_invalidAadhar_throwsAndDoesNotSave(long badAadhar) {
        MobileNumberException ex = assertThrows(MobileNumberException.class,
                () -> bankService.register(registerRequest(9876543210L, badAadhar)));

        assertEquals("Invalid AADHAR NUMBER", ex.getMessage());
        verifyNoInteractions(userRepository);
    }

    @Test
    void register_duplicateMobileNumber_throwsUserExist() {
        when(userRepository.existsByPhno(9876543210L)).thenReturn(true);

        UserExistException ex = assertThrows(UserExistException.class,
                () -> bankService.register(registerRequest(9876543210L, 222222222222L)));

        assertEquals("mobile number already exist", ex.getMessage());
        verify(userRepository, never()).save(any());
    }

    @Test
    void register_duplicateAadhar_throwsUserExist() {
        when(userRepository.existsByAadharNumber(123456789012L)).thenReturn(true);

        UserExistException ex = assertThrows(UserExistException.class,
                () -> bankService.register(registerRequest(9876543210L, 123456789012L)));

        assertEquals("AADHAR NUMBER already exist", ex.getMessage());
        verify(userRepository, never()).save(any());
    }

    // ---------- login ----------

    @Test
    void login_correctPin_startsASession() {
        Bank user = bankWithPin(1000000000L, 9876543210L, 123456789012L, 500);
        when(userRepository.findByphno(9876543210L)).thenReturn(user);
        when(passwordEncoder.matches("1234", STORED_HASH)).thenReturn(true);
        when(sessionService.start(9876543210L)).thenReturn(new SessionService.IssuedSession("tok", NOW.plusSeconds(1800)));

        LoginResponse response = bankService.login(9876543210L, "1234");

        assertEquals("tok", response.token());
        assertEquals(9876543210L, response.phno());
        assertEquals("KUMAR CHARAN", response.name());
    }

    @Test
    void login_correctPin_resetsAnyPriorFailedAttempts() {
        Bank user = bankWithPin(1000000000L, 9876543210L, 123456789012L, 500);
        user.setFailedLoginAttempts(3);
        when(userRepository.findByphno(9876543210L)).thenReturn(user);
        when(passwordEncoder.matches("1234", STORED_HASH)).thenReturn(true);
        when(sessionService.start(9876543210L)).thenReturn(new SessionService.IssuedSession("tok", NOW));

        bankService.login(9876543210L, "1234");

        assertEquals(0, user.getFailedLoginAttempts());
        verify(userRepository).saveAndFlush(user);
    }

    @Test
    void login_unknownPhoneNumber_isGenericAndStartsNoSession() {
        when(userRepository.findByphno(9999999999L)).thenReturn(null);

        InvalidCredentialsException ex = assertThrows(InvalidCredentialsException.class,
                () -> bankService.login(9999999999L, "1234"));

        assertEquals("Invalid phone number or PIN", ex.getMessage());
        verifyNoInteractions(sessionService);
    }

    @Test
    void login_wrongPin_isTheSameGenericMessageAsAnUnknownNumber() {
        Bank user = bankWithPin(1000000000L, 9876543210L, 123456789012L, 500);
        when(userRepository.findByphno(9876543210L)).thenReturn(user);
        when(passwordEncoder.matches("0000", STORED_HASH)).thenReturn(false);

        InvalidCredentialsException ex = assertThrows(InvalidCredentialsException.class,
                () -> bankService.login(9876543210L, "0000"));

        assertEquals("Invalid phone number or PIN", ex.getMessage());
        verifyNoInteractions(sessionService);
    }

    @Test
    void login_accountWithNoPinSet_isRejectedDistinctly() {
        Bank user = bank(1000000000L, 9876543210L, 123456789012L, 500);   // no PIN set
        when(userRepository.findByphno(9876543210L)).thenReturn(user);

        assertThrows(PinNotSetException.class, () -> bankService.login(9876543210L, "1234"));

        verifyNoInteractions(sessionService, passwordEncoder);
    }

    @Test
    void login_wrongPinRepeatedly_locksTheAccountAfterFiveAttempts() {
        Bank user = bankWithPin(1000000000L, 9876543210L, 123456789012L, 500);
        when(userRepository.findByphno(9876543210L)).thenReturn(user);
        when(passwordEncoder.matches("0000", STORED_HASH)).thenReturn(false);

        for (int i = 1; i <= 5; i++) {
            assertThrows(InvalidCredentialsException.class, () -> bankService.login(9876543210L, "0000"));
        }

        assertEquals(5, user.getFailedLoginAttempts());
        assertEquals(NOW.plus(Duration.ofMinutes(15)), user.getLockedUntil());
    }

    @Test
    void login_whileLocked_isRejectedEvenWithTheCorrectPin() {
        Bank user = bankWithPin(1000000000L, 9876543210L, 123456789012L, 500);
        user.setLockedUntil(NOW.plus(Duration.ofMinutes(10)));
        when(userRepository.findByphno(9876543210L)).thenReturn(user);

        assertThrows(AccountLockedException.class, () -> bankService.login(9876543210L, "1234"));

        verifyNoInteractions(passwordEncoder, sessionService);
    }

    @Test
    void login_afterTheLockoutExpires_worksAgain() {
        Bank user = bankWithPin(1000000000L, 9876543210L, 123456789012L, 500);
        user.setLockedUntil(NOW.minusSeconds(1));   // lock already expired
        when(userRepository.findByphno(9876543210L)).thenReturn(user);
        when(passwordEncoder.matches("1234", STORED_HASH)).thenReturn(true);
        when(sessionService.start(9876543210L)).thenReturn(new SessionService.IssuedSession("tok", NOW));

        LoginResponse response = bankService.login(9876543210L, "1234");

        assertEquals("tok", response.token());
    }

    // ---------- login: two attempts racing on the same account's @Version ----------

    @Test
    void login_wrongPin_concurrentUpdateLosesTheRace_retriesAndStillThrowsInvalidCredentials() {
        // Another request against the SAME account (right or wrong PIN) committed first. Losing this race must
        // not surface as a raw 409 for what the caller experiences as just a wrong PIN.
        Bank user = bankWithPin(1000000000L, 9876543210L, 123456789012L, 500);
        when(userRepository.findByphno(9876543210L)).thenReturn(user);
        when(passwordEncoder.matches("0000", STORED_HASH)).thenReturn(false);
        doThrow(new ObjectOptimisticLockingFailureException(Bank.class, 1000000000L))
                .doReturn(user)
                .when(userRepository).saveAndFlush(user);

        InvalidCredentialsException ex = assertThrows(InvalidCredentialsException.class,
                () -> bankService.login(9876543210L, "0000"));

        assertEquals("Invalid phone number or PIN", ex.getMessage());
        verify(userRepository, times(2)).saveAndFlush(user);
    }

    @Test
    void login_wrongPin_concurrentUpdateKeepsLosing_givesUpAfterMaxRetries() {
        Bank user = bankWithPin(1000000000L, 9876543210L, 123456789012L, 500);
        when(userRepository.findByphno(9876543210L)).thenReturn(user);
        when(passwordEncoder.matches("0000", STORED_HASH)).thenReturn(false);
        doThrow(new ObjectOptimisticLockingFailureException(Bank.class, 1000000000L))
                .when(userRepository).saveAndFlush(user);

        assertThrows(ObjectOptimisticLockingFailureException.class, () -> bankService.login(9876543210L, "0000"));

        verify(userRepository, times(3)).saveAndFlush(user);
    }

    @Test
    void login_correctPin_concurrentUpdateLosesTheRaceOnce_retriesAndStillSucceeds() {
        Bank user = bankWithPin(1000000000L, 9876543210L, 123456789012L, 500);
        when(userRepository.findByphno(9876543210L)).thenReturn(user);
        when(passwordEncoder.matches("1234", STORED_HASH)).thenReturn(true);
        when(sessionService.start(9876543210L)).thenReturn(new SessionService.IssuedSession("tok", NOW));
        doThrow(new ObjectOptimisticLockingFailureException(Bank.class, 1000000000L))
                .doReturn(user)
                .when(userRepository).saveAndFlush(user);

        LoginResponse response = bankService.login(9876543210L, "1234");

        assertEquals("tok", response.token());
        verify(userRepository, times(2)).saveAndFlush(user);
        verify(userRepository, times(2)).findByphno(9876543210L);
    }

    @Test
    void logout_endsTheSession() {
        bankService.logout("tok");

        verify(sessionService).end("tok");
    }

    // ---------- admin: set/reset PIN ----------

    @Test
    void setPin_hashesAndStoresTheNewPin_andClearsAnyLockout() {
        Bank user = bankWithPin(1000000000L, 9876543210L, 123456789012L, 500);
        user.setFailedLoginAttempts(4);
        user.setLockedUntil(NOW.plus(Duration.ofMinutes(5)));
        when(userRepository.findByphno(9876543210L)).thenReturn(user);
        when(passwordEncoder.encode("5678")).thenReturn("new-hash");

        bankService.setPin(9876543210L, "5678");

        assertEquals("new-hash", user.getPinHash());
        assertEquals(0, user.getFailedLoginAttempts());
        assertEquals(null, user.getLockedUntil());
        verify(userRepository).save(user);
    }

    @Test
    void setPin_invalidatesAnySessionOpenedUnderTheOldPin() {
        Bank user = bankWithPin(1000000000L, 9876543210L, 123456789012L, 500);
        when(userRepository.findByphno(9876543210L)).thenReturn(user);

        bankService.setPin(9876543210L, "5678");

        verify(sessionService).invalidateAllFor(9876543210L);
    }

    @Test
    void setPin_unknownUser_throwsUserNotFound() {
        when(userRepository.findByphno(9999999999L)).thenReturn(null);

        assertThrows(UserNotFoundException.class, () -> bankService.setPin(9999999999L, "5678"));
    }

    // ---------- deposit ----------

    @Test
    void depositByphno_increasesBalance_recordsCreditTransaction_andNotifiesKafka() {
        Bank user = bank(1000000000L, 9876543210L, 123456789012L, 1000);
        when(userRepository.findByphno(9876543210L)).thenReturn(user);
        when(bankTransactionRepository.findMaxTransactionId()).thenReturn(null);   // no transactions yet

        String result = bankService.depositByphno(9876543210L, money(500));

        assertEquals("Deposit Successful Amount Inr : 500.0", result);
        assertMoney(1500, user.getBalance());

        ArgumentCaptor<BankTransaction> captor = ArgumentCaptor.forClass(BankTransaction.class);
        verify(bankTransactionRepository).save(captor.capture());
        BankTransaction txn = captor.getValue();
        assertEquals("Credit", txn.getAction());
        assertMoney(500, txn.getAmount());
        assertMoney(1500, txn.getBalance());
        assertEquals(100000L, txn.getTransactionId());   // first ever transaction
        assertEquals(NOW, txn.getCreatedAt());

        verify(bankKafkaProducer).sendMessage(contains("Amount deposited successfully"));
        verify(userRepository).save(user);
    }

    @Test
    void depositByphno_notification_masksThePhoneNumber() {
        Bank user = bank(1000000000L, 9876543210L, 123456789012L, 1000);
        when(userRepository.findByphno(9876543210L)).thenReturn(user);

        bankService.depositByphno(9876543210L, money(500));

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(bankKafkaProducer).sendMessage(message.capture());
        assertTrue(message.getValue().contains("XXXXXX3210"));
        assertFalse(message.getValue().contains("9876543210"));
    }

    // A broken notification channel must never turn a completed payment into an error.
    @Test
    void depositByphno_kafkaFailure_doesNotFailTheDeposit() {
        Bank user = bank(1000000000L, 9876543210L, 123456789012L, 1000);
        when(userRepository.findByphno(9876543210L)).thenReturn(user);
        doThrow(new RuntimeException("kafka down")).when(bankKafkaProducer).sendMessage(any());

        String result = bankService.depositByphno(9876543210L, money(500));

        assertEquals("Deposit Successful Amount Inr : 500.0", result);
        assertMoney(1500, user.getBalance());
    }

    @Test
    void depositByacno_increasesBalance_andUsesNextTransactionId() {
        Bank user = bank(1000000000L, 9876543210L, 123456789012L, 200);
        when(userRepository.findByacno(1000000000L)).thenReturn(user);
        when(bankTransactionRepository.findMaxTransactionId()).thenReturn(100007L);

        bankService.depositByacno(1000000000L, money(50));

        assertMoney(250, user.getBalance());
        ArgumentCaptor<BankTransaction> captor = ArgumentCaptor.forClass(BankTransaction.class);
        verify(bankTransactionRepository).save(captor.capture());
        assertEquals(100008L, captor.getValue().getTransactionId());   // last id + 1
        assertEquals("Credit", captor.getValue().getAction());
        assertEquals(NOW, captor.getValue().getCreatedAt());
        assertEquals(9876543210L, captor.getValue().getPhno());
        verify(userRepository, times(1)).findByacno(1000000000L);   // not re-fetched just to read its phno back
    }

    @Test
    void depositByphno_unknownUser_throwsUserNotFound_andSendsNoNotification() {
        when(userRepository.findByphno(9999999999L)).thenReturn(null);

        assertThrows(UserNotFoundException.class, () -> bankService.depositByphno(9999999999L, money(100)));

        verifyNoInteractions(bankKafkaProducer);
        verify(userRepository, never()).save(any());
    }

    // ---------- withdraw ----------

    @Test
    void withdrawByphno_decreasesBalance_recordsDebitTransaction_andNotifiesKafka() {
        Bank user = bank(1000000000L, 9876543210L, 123456789012L, 1000);
        when(userRepository.findByphno(9876543210L)).thenReturn(user);

        String result = bankService.withdrawByphno(9876543210L, money(400));

        assertEquals("Withdraw Successful Amount Inr : 400.0", result);
        assertMoney(600, user.getBalance());

        ArgumentCaptor<BankTransaction> captor = ArgumentCaptor.forClass(BankTransaction.class);
        verify(bankTransactionRepository).save(captor.capture());
        assertEquals("Debit", captor.getValue().getAction());
        assertMoney(400, captor.getValue().getAmount());
        assertMoney(600, captor.getValue().getBalance());
        assertEquals(NOW, captor.getValue().getCreatedAt());

        verify(bankKafkaProducer).sendMessage(contains("Amount Withdraw successfully"));
        verify(userRepository).save(user);
    }

    @Test
    void withdrawByphno_notification_masksThePhoneNumber() {
        Bank user = bank(1000000000L, 9876543210L, 123456789012L, 1000);
        when(userRepository.findByphno(9876543210L)).thenReturn(user);

        bankService.withdrawByphno(9876543210L, money(400));

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(bankKafkaProducer).sendMessage(message.capture());
        assertTrue(message.getValue().contains("XXXXXX3210"));
        assertFalse(message.getValue().contains("9876543210"));
    }

    @Test
    void withdrawByacno_decreasesBalance_whenGivenPositiveAmount() {
        Bank user = bank(1000000000L, 9876543210L, 123456789012L, 1000);
        when(userRepository.findByacno(1000000000L)).thenReturn(user);

        bankService.withdrawByacno(1000000000L, money(300));

        assertMoney(700, user.getBalance());
        ArgumentCaptor<BankTransaction> captor = ArgumentCaptor.forClass(BankTransaction.class);
        verify(bankTransactionRepository).save(captor.capture());
        assertEquals(NOW, captor.getValue().getCreatedAt());
        assertEquals(9876543210L, captor.getValue().getPhno());
        verify(userRepository, times(1)).findByacno(1000000000L);   // not re-fetched just to read its phno back
    }

    // The balance check must live INSIDE the service transaction; a check made earlier can be stale by the time we subtract.
    @Test
    void withdrawByphno_moreThanBalance_throwsAndChangesNothing() {
        Bank user = bank(1000000000L, 9876543210L, 123456789012L, 100);
        when(userRepository.findByphno(9876543210L)).thenReturn(user);

        WithdrawException ex = assertThrows(WithdrawException.class, () -> bankService.withdrawByphno(9876543210L, money(100.01)));

        assertEquals("Insufficient Funds", ex.getMessage());
        assertMoney(100, user.getBalance());
        verifyNoInteractions(bankTransactionRepository);
        verifyNoInteractions(bankKafkaProducer);
    }

    @Test
    void withdrawByacno_moreThanBalance_throwsAndChangesNothing() {
        Bank user = bank(1000000000L, 9876543210L, 123456789012L, 100);
        when(userRepository.findByacno(1000000000L)).thenReturn(user);

        assertThrows(WithdrawException.class, () -> bankService.withdrawByacno(1000000000L, money(500)));

        assertMoney(100, user.getBalance());
        verifyNoInteractions(bankTransactionRepository);
        verifyNoInteractions(bankKafkaProducer);
    }

    @Test
    void withdrawByphno_exactBalance_leavesZero() {
        Bank user = bank(1000000000L, 9876543210L, 123456789012L, 250);
        when(userRepository.findByphno(9876543210L)).thenReturn(user);

        bankService.withdrawByphno(9876543210L, money(250));

        assertMoney(0, user.getBalance());
    }

    @Test
    void withdrawByphno_unknownUser_throwsUserNotFound() {
        when(userRepository.findByphno(9999999999L)).thenReturn(null);

        assertThrows(UserNotFoundException.class, () -> bankService.withdrawByphno(9999999999L, money(100)));

        verifyNoInteractions(bankKafkaProducer);
        verifyNoInteractions(bankTransactionRepository);
    }

    // ---------- transfer ----------

    @Test
    void transfer_movesMoneyBetweenBothAccounts_inOneGo() {
        Bank payer = bank(1000000000L, 9876543210L, 111111111111L, 1000);
        Bank receiver = bank(1000000001L, 9123456789L, 222222222222L, 200);
        when(userRepository.findByphno(9876543210L)).thenReturn(payer);
        when(userRepository.findByphno(9123456789L)).thenReturn(receiver);
        when(transferRepository.findByPayerPhnoAndIdempotencyKey(9876543210L, "key-1")).thenReturn(Optional.empty());

        String result = bankService.transfer(9876543210L, 9123456789L, money(250), "key-1");

        assertEquals("Transfer Successful Amount Inr : 250.0", result);
        assertMoney(750, payer.getBalance());
        assertMoney(450, receiver.getBalance());
    }

    @Test
    void transfer_recordsADebitAndACreditWithDifferentTransactionIds() {
        Bank payer = bank(1000000000L, 9876543210L, 111111111111L, 1000);
        Bank receiver = bank(1000000001L, 9123456789L, 222222222222L, 0);
        when(userRepository.findByphno(9876543210L)).thenReturn(payer);
        when(userRepository.findByphno(9123456789L)).thenReturn(receiver);
        when(transferRepository.findByPayerPhnoAndIdempotencyKey(9876543210L, "key-1")).thenReturn(Optional.empty());
        when(bankTransactionRepository.findMaxTransactionId()).thenReturn(100005L);

        bankService.transfer(9876543210L, 9123456789L, money(250), "key-1");

        ArgumentCaptor<BankTransaction> captor = ArgumentCaptor.forClass(BankTransaction.class);
        verify(bankTransactionRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        List<BankTransaction> saved = captor.getAllValues();
        assertEquals("Debit", saved.get(0).getAction());
        assertEquals(100006L, saved.get(0).getTransactionId());
        assertEquals(9876543210L, saved.get(0).getPhno());
        assertEquals(NOW, saved.get(0).getCreatedAt());
        assertEquals("Credit", saved.get(1).getAction());
        assertEquals(100007L, saved.get(1).getTransactionId());
        assertEquals(9123456789L, saved.get(1).getPhno());
        assertEquals(NOW, saved.get(1).getCreatedAt());

        ArgumentCaptor<Transfer> transferCaptor = ArgumentCaptor.forClass(Transfer.class);
        verify(transferRepository).save(transferCaptor.capture());
        assertEquals(100006L, transferCaptor.getValue().getDebitTransactionId());
        assertEquals(100007L, transferCaptor.getValue().getCreditTransactionId());
    }

    // The whole point of the idempotency key: a retry (after a timeout, say) must never move the money twice.
    @Test
    void transfer_sameIdempotencyKeyAgain_withMatchingDetails_doesNotMoveAnyMoney_returnsTheOriginalResult() {
        Transfer previous = new Transfer();
        previous.setIdempotencyKey("key-1");
        previous.setPayerPhno(9876543210L);
        previous.setReceiverPhno(9123456789L);
        previous.setAmount(money(250));
        when(transferRepository.findByPayerPhnoAndIdempotencyKey(9876543210L, "key-1")).thenReturn(Optional.of(previous));

        String result = bankService.transfer(9876543210L, 9123456789L, money(250), "key-1");

        assertEquals("Transfer Successful Amount Inr : 250.0", result);
        verifyNoInteractions(userRepository, bankTransactionRepository, bankKafkaProducer);
    }

    // A reused key with a DIFFERENT payer, receiver, or amount is not a retry - it must never be waved through as
    // if it were, since that would report success for money that was never moved as the caller just asked.
    @Test
    void transfer_sameIdempotencyKeyAgain_withDifferentDetails_throwsInvalidRequest() {
        Transfer previous = new Transfer();
        previous.setIdempotencyKey("key-1");
        previous.setPayerPhno(9876543210L);
        previous.setReceiverPhno(9123456789L);
        previous.setAmount(money(250));
        when(transferRepository.findByPayerPhnoAndIdempotencyKey(9876543210L, "key-1")).thenReturn(Optional.of(previous));

        assertThrows(InvalidRequestException.class,
                () -> bankService.transfer(9876543210L, 9123456789L, money(999), "key-1"));
        verifyNoInteractions(userRepository, bankTransactionRepository, bankKafkaProducer);
    }

    // The key is scoped per payer: a different customer reusing the same key string (their own generation
    // scheme just happened to produce it) is a brand new, unrelated transfer - not a collision with someone
    // else's request, and not something that should ever get blocked or silently matched against it.
    @Test
    void transfer_sameIdempotencyKey_differentPayer_isTreatedAsAWhollyUnrelatedTransfer() {
        Bank payer = bank(1000000002L, 9000000001L, 333333333333L, 1000);
        Bank receiver = bank(1000000001L, 9123456789L, 222222222222L, 0);
        when(userRepository.findByphno(9000000001L)).thenReturn(payer);
        when(userRepository.findByphno(9123456789L)).thenReturn(receiver);
        // someone else's transfer already used "key-1", but that lookup is scoped to THAT payer, not this one
        when(transferRepository.findByPayerPhnoAndIdempotencyKey(9000000001L, "key-1")).thenReturn(Optional.empty());

        String result = bankService.transfer(9000000001L, 9123456789L, money(250), "key-1");

        assertEquals("Transfer Successful Amount Inr : 250.0", result);
        assertMoney(750, payer.getBalance());
    }

    @Test
    void transfer_toTheSameAccount_isRejectedBeforeTouchingTheDatabase() {
        InvalidRequestException ex = assertThrows(InvalidRequestException.class,
                () -> bankService.transfer(9876543210L, 9876543210L, money(10), "key-1"));

        assertEquals("Cannot transfer to the same account", ex.getMessage());
        verifyNoInteractions(userRepository, bankTransactionRepository);
    }

    @Test
    void transfer_unknownPayer_throwsAndChangesNothing() {
        when(userRepository.findByphno(9876543210L)).thenReturn(null);

        UserNotFoundException ex = assertThrows(UserNotFoundException.class,
                () -> bankService.transfer(9876543210L, 9123456789L, money(10), "key-1"));

        assertEquals("Payer not found", ex.getMessage());
        verifyNoInteractions(bankTransactionRepository);
    }

    @Test
    void transfer_unknownReceiver_throwsAndChangesNothing() {
        Bank payer = bank(1000000000L, 9876543210L, 111111111111L, 1000);
        when(userRepository.findByphno(9876543210L)).thenReturn(payer);
        when(userRepository.findByphno(9123456789L)).thenReturn(null);

        UserNotFoundException ex = assertThrows(UserNotFoundException.class,
                () -> bankService.transfer(9876543210L, 9123456789L, money(10), "key-1"));

        assertEquals("Receiver not found", ex.getMessage());
        assertMoney(1000, payer.getBalance());   // the payer was never touched
        verifyNoInteractions(bankTransactionRepository);
    }

    @Test
    void transfer_insufficientFunds_throwsAndChangesNothing() {
        Bank payer = bank(1000000000L, 9876543210L, 111111111111L, 100);
        Bank receiver = bank(1000000001L, 9123456789L, 222222222222L, 0);
        when(userRepository.findByphno(9876543210L)).thenReturn(payer);
        when(userRepository.findByphno(9123456789L)).thenReturn(receiver);

        WithdrawException ex = assertThrows(WithdrawException.class,
                () -> bankService.transfer(9876543210L, 9123456789L, money(500), "key-1"));

        assertEquals("Insufficient Funds", ex.getMessage());
        assertMoney(100, payer.getBalance());
        assertMoney(0, receiver.getBalance());
        verifyNoInteractions(bankTransactionRepository);
    }

    // ---------- update phone number ----------

    @Test
    void updatePhno_success_changesNumber_andNotifiesKafka() {
        Bank user = bank(1000000000L, 9876543210L, 123456789012L, 0);
        when(userRepository.findByphno(9876543210L)).thenReturn(user);
        when(userRepository.save(user)).thenReturn(user);

        Bank updated = bankService.updatePhno(9876543210L, 9123456789L);

        assertEquals(9123456789L, updated.getPhno());
        verify(bankKafkaProducer).sendMessage(contains("Mobile number updated"));
    }

    // These messages go to a Kafka topic (and, per BankKafkaConsumer/Producer, to the logs); the full 10-digit
    // number must never appear in either, only the last 4 digits, the same as PhonepayService already does.
    @Test
    void updatePhno_notification_masksBothPhoneNumbers() {
        Bank user = bank(1000000000L, 9876543210L, 123456789012L, 0);
        when(userRepository.findByphno(9876543210L)).thenReturn(user);
        when(userRepository.save(user)).thenReturn(user);

        bankService.updatePhno(9876543210L, 9123456789L);

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(bankKafkaProducer).sendMessage(message.capture());
        assertTrue(message.getValue().contains("XXXXXX3210"));
        assertTrue(message.getValue().contains("XXXXXX6789"));
        assertFalse(message.getValue().contains("9876543210"), "the old number must not appear in full");
        assertFalse(message.getValue().contains("9123456789"), "the new number must not appear in full");
    }

    @Test
    void transfer_notification_masksBothPhoneNumbers() {
        Bank payer = bank(1000000000L, 9876543210L, 111111111111L, 1000);
        Bank receiver = bank(1000000001L, 9123456789L, 222222222222L, 0);
        when(userRepository.findByphno(9876543210L)).thenReturn(payer);
        when(userRepository.findByphno(9123456789L)).thenReturn(receiver);
        when(transferRepository.findByPayerPhnoAndIdempotencyKey(9876543210L, "key-1")).thenReturn(Optional.empty());

        bankService.transfer(9876543210L, 9123456789L, money(250), "key-1");

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(bankKafkaProducer).sendMessage(message.capture());
        assertTrue(message.getValue().contains("XXXXXX3210"));
        assertTrue(message.getValue().contains("XXXXXX6789"));
        assertFalse(message.getValue().contains("9876543210"));
        assertFalse(message.getValue().contains("9123456789"));
    }

    @Test
    void updatePhno_invalidatesAnySessionOpenedUnderTheOldNumber() {
        Bank user = bank(1000000000L, 9876543210L, 123456789012L, 0);
        when(userRepository.findByphno(9876543210L)).thenReturn(user);
        when(userRepository.save(user)).thenReturn(user);

        bankService.updatePhno(9876543210L, 9123456789L);

        verify(sessionService).invalidateAllFor(9876543210L);
        verify(sessionService, never()).invalidateAllFor(9123456789L);
    }

    @Test
    void updatePhno_newNumberAlreadyTaken_throwsUserExist() {
        when(userRepository.existsByPhno(9123456789L)).thenReturn(true);

        assertThrows(UserExistException.class, () -> bankService.updatePhno(9876543210L, 9123456789L));

        verify(userRepository, never()).save(any());
    }

    @ParameterizedTest
    @ValueSource(longs = {
            987654321L,      // 9 digits  -> too short
            98765432101L,    // 11 digits -> too long
            5876543210L,     // 10 digits but starts with 5 (must be 6-9)
            0L                // an admin call could otherwise silently set this
    })
    void updatePhno_invalidNewNumber_throwsAndChangesNothing(long badNewPhno) {
        MobileNumberException ex = assertThrows(MobileNumberException.class,
                () -> bankService.updatePhno(9876543210L, badNewPhno));

        assertEquals("Invalid mobile number", ex.getMessage());
        verifyNoInteractions(userRepository);
    }

    // ---------- delete ----------

    @Test
    void deleteByPhno_deletesTheAccountThatWasLookedUp() {
        Bank user = bank(1000000000L, 9876543210L, 123456789012L, 0);
        when(userRepository.findByphno(9876543210L)).thenReturn(user);

        bankService.deleteByPhno(9876543210L);

        verify(userRepository).delete(user);
        verify(userRepository, times(1)).findByphno(9876543210L);   // not re-fetched just to pass it to delete()
    }

    @Test
    void deleteByPhno_invalidatesAnySessionStillOpenForThatNumber() {
        Bank user = bank(1000000000L, 9876543210L, 123456789012L, 0);
        when(userRepository.findByphno(9876543210L)).thenReturn(user);

        bankService.deleteByPhno(9876543210L);

        verify(sessionService).invalidateAllFor(9876543210L);
    }

    @Test
    void deleteByPhno_unknownUser_throwsUserNotFound() {
        when(userRepository.findByphno(9999999999L)).thenReturn(null);

        assertThrows(UserNotFoundException.class, () -> bankService.deleteByPhno(9999999999L));

        verify(userRepository, never()).delete(any());
        verify(sessionService, never()).invalidateAllFor(anyLong());
    }

    // ---------- display ----------

    @Test
    void displayUserByPhno_returnsDtoWithUppercaseLastNameFirstName() {
        Bank user = bank(1000000000L, 9876543210L, 123456789012L, 750);
        when(userRepository.findByphno(9876543210L)).thenReturn(user);

        var dto = bankService.displayUserByPhno(9876543210L);

        assertEquals("KUMAR CHARAN", dto.getName());
        assertMoney(750, dto.getBalance());
        assertEquals(1000000000L, dto.getAcno());
    }

    @Test
    void displayUserByPhno_unknownUser_throwsUserNotFound() {
        when(userRepository.findByphno(9999999999L)).thenReturn(null);

        assertThrows(UserNotFoundException.class, () -> bankService.displayUserByPhno(9999999999L));
    }

    @Test
    void displayTransactionByPhno_returnsUsersTransactions() {
        Bank user = bank(1000000000L, 9876543210L, 123456789012L, 0);
        when(userRepository.findByphno(9876543210L)).thenReturn(user);
        when(bankTransactionRepository.findByPhno(eq(9876543210L), isNull(), isNull(), any()))
                .thenReturn(new PageImpl<>(List.of(txnWithId(100000))));

        assertEquals(1, bankService.displayTransactionByPhno(9876543210L, 0, 20, null, null).content().size());
    }

    @Test
    void displayTransactionByPhno_passesFromAndToThrough() {
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = Instant.parse("2026-01-31T00:00:00Z");
        when(userRepository.findByphno(9876543210L)).thenReturn(bank(1000000000L, 9876543210L, 123456789012L, 0));
        when(bankTransactionRepository.findByPhno(eq(9876543210L), eq(from), eq(to), any()))
                .thenReturn(new PageImpl<>(List.of(txnWithId(100000))));

        assertEquals(1, bankService.displayTransactionByPhno(9876543210L, 0, 20, from, to).content().size());
    }

    @Test
    void displayTransactionByPhno_fromAfterTo_throwsInvalidRequest() {
        Instant from = Instant.parse("2026-01-31T00:00:00Z");
        Instant to = Instant.parse("2026-01-01T00:00:00Z");

        assertThrows(InvalidRequestException.class, () -> bankService.displayTransactionByPhno(9876543210L, 0, 20, from, to));
        verifyNoInteractions(userRepository, bankTransactionRepository);
    }

    @Test
    void displayTransactionByPhno_unknownUser_throwsUserNotFound() {
        when(userRepository.findByphno(9999999999L)).thenReturn(null);

        assertThrows(UserNotFoundException.class, () -> bankService.displayTransactionByPhno(9999999999L, 0, 20, null, null));
    }

    @Test
    void displayTransactionByPhno_negativePage_throwsInvalidRequest() {
        when(userRepository.findByphno(9876543210L)).thenReturn(bank(1000000000L, 9876543210L, 123456789012L, 0));

        assertThrows(InvalidRequestException.class, () -> bankService.displayTransactionByPhno(9876543210L, -1, 20, null, null));
    }

    @Test
    void displayTransactionByPhno_sizeTooLarge_throwsInvalidRequest() {
        when(userRepository.findByphno(9876543210L)).thenReturn(bank(1000000000L, 9876543210L, 123456789012L, 0));

        assertThrows(InvalidRequestException.class,
                () -> bankService.displayTransactionByPhno(9876543210L, 0, BankService.MAX_PAGE_SIZE + 1, null, null));
    }

    // ---------- self-service: my-transactions (scoped by account, not by the phone number value) ----------

    @Test
    void displayMyTransactions_looksUpByTheAccountsUserId_notThePhoneNumber() {
        Bank user = bank(1000000000L, 9876543210L, 123456789012L, 0);   // userId = 1, see bank()
        when(userRepository.findByphno(9876543210L)).thenReturn(user);
        when(bankTransactionRepository.findByUserId(eq(1L), isNull(), isNull(), any()))
                .thenReturn(new PageImpl<>(List.of(txnWithId(100000))));

        assertEquals(1, bankService.displayMyTransactions(9876543210L, 0, 20, null, null).content().size());
    }

    @Test
    void displayMyTransactions_doesNotFallBackToThePhnoBasedQuery() {
        Bank user = bank(1000000000L, 9876543210L, 123456789012L, 0);
        when(userRepository.findByphno(9876543210L)).thenReturn(user);
        when(bankTransactionRepository.findByUserId(eq(1L), isNull(), isNull(), any()))
                .thenReturn(new PageImpl<>(List.of()));

        bankService.displayMyTransactions(9876543210L, 0, 20, null, null);

        verify(bankTransactionRepository, never()).findByPhno(anyLong(), any(), any(), any());
    }

    @Test
    void displayMyTransactions_passesFromAndToThrough() {
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = Instant.parse("2026-01-31T00:00:00Z");
        when(userRepository.findByphno(9876543210L)).thenReturn(bank(1000000000L, 9876543210L, 123456789012L, 0));
        when(bankTransactionRepository.findByUserId(eq(1L), eq(from), eq(to), any()))
                .thenReturn(new PageImpl<>(List.of(txnWithId(100000))));

        assertEquals(1, bankService.displayMyTransactions(9876543210L, 0, 20, from, to).content().size());
    }

    @Test
    void displayMyTransactions_fromAfterTo_throwsInvalidRequest() {
        Instant from = Instant.parse("2026-01-31T00:00:00Z");
        Instant to = Instant.parse("2026-01-01T00:00:00Z");

        assertThrows(InvalidRequestException.class, () -> bankService.displayMyTransactions(9876543210L, 0, 20, from, to));
        verifyNoInteractions(userRepository, bankTransactionRepository);
    }

    @Test
    void displayMyTransactions_unknownUser_throwsUserNotFound() {
        when(userRepository.findByphno(9999999999L)).thenReturn(null);

        assertThrows(UserNotFoundException.class, () -> bankService.displayMyTransactions(9999999999L, 0, 20, null, null));
    }

    // A phone number can be reassigned after an account is deleted (see deleteByPhno_invalidatesAnySessionStillOpenForThatNumber);
    // the point of scoping this query by userId is that the new owner's own listing never surfaces rows written
    // under the old owner's userId, even though they currently share the same phno value.
    @Test
    void displayMyTransactions_isScopedToTheCurrentOwnersUserId_evenIfAnOlderAccountUsedTheSamePhno() {
        Bank newOwner = bank(1000000005L, 9876543210L, 999999999999L, 0);
        newOwner.setUserId(42);   // a different account than whoever had this number before
        when(userRepository.findByphno(9876543210L)).thenReturn(newOwner);
        when(bankTransactionRepository.findByUserId(eq(42L), isNull(), isNull(), any()))
                .thenReturn(new PageImpl<>(List.of()));

        assertEquals(0, bankService.displayMyTransactions(9876543210L, 0, 20, null, null).content().size());

        verify(bankTransactionRepository).findByUserId(eq(42L), isNull(), isNull(), any());
        verify(bankTransactionRepository, never()).findByPhno(anyLong(), any(), any(), any());
    }

    @Test
    void findAll_returnsAPageOfUsers() {
        Bank user = bank(1000000000L, 9876543210L, 123456789012L, 250.0);
        when(userRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(user), PageRequest.of(0, 20), 1));

        PageResponse<BankDto> result = bankService.findAll(0, 20);

        assertEquals(1, result.content().size());
        assertEquals(1, result.totalElements());
        assertEquals(0, result.page());
    }

    @Test
    void findAll_invalidSize_throwsInvalidRequest() {
        assertThrows(InvalidRequestException.class, () -> bankService.findAll(0, 0));
    }
}
