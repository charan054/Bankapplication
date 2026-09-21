package com.example.bankapplication.service;

import com.example.bankapplication.entity.Bank;
import com.example.bankapplication.entity.BankTransaction;
import com.example.bankapplication.exception.MobileNumberException;
import com.example.bankapplication.exception.UserExistException;
import com.example.bankapplication.exception.UserNotFoundException;
import com.example.bankapplication.exception.WithdrawException;
import com.example.bankapplication.kafka.BankKafkaProducer;
import com.example.bankapplication.repository.BankRepository;
import com.example.bankapplication.repository.BankTransactionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BankServiceTest {

    @Mock
    private BankRepository userRepository;
    @Mock
    private BankTransactionRepository bankTransactionRepository;
    @Mock
    private BankKafkaProducer bankKafkaProducer;

    @InjectMocks
    private BankService bankService;

    // ---------- helpers ----------

    private Bank bank(long acno, long phno, long aadhar, double balance) {
        Bank b = new Bank();
        b.setUserId(1);
        b.setAcno(acno);
        b.setFirstName("Charan");
        b.setLastName("Kumar");
        b.setPhno(phno);
        b.setAadharNumber(aadhar);
        b.setBalance(balance);
        return b;
    }

    private BankTransaction txnWithId(long transactionId) {
        BankTransaction t = new BankTransaction();
        t.setTransactionId(transactionId);
        return t;
    }

    // ---------- save(): account creation ----------

    @Test
    void save_firstUser_getsAccountNumber1000000000() {
        Bank newUser = bank(0, 9876543210L, 123456789012L, 0);
        when(userRepository.findAll()).thenReturn(List.of());
        when(userRepository.save(any(Bank.class))).thenAnswer(inv -> inv.getArgument(0));

        Bank saved = bankService.save(newUser);

        assertEquals(1000000000L, saved.getAcno());
    }

    @Test
    void save_nextUser_getsLastAccountNumberPlusOne() {
        Bank existing = bank(1000000005L, 9000000001L, 111111111111L, 0);
        Bank newUser = bank(0, 9876543210L, 123456789012L, 0);
        when(userRepository.findAll()).thenReturn(List.of(existing));
        when(userRepository.save(any(Bank.class))).thenAnswer(inv -> inv.getArgument(0));

        Bank saved = bankService.save(newUser);

        assertEquals(1000000006L, saved.getAcno());
    }

    @ParameterizedTest
    @ValueSource(longs = {
            987654321L,      // 9 digits  -> too short
            98765432101L,    // 11 digits -> too long
            5876543210L,     // 10 digits but starts with 5 (must be 6-9)
            1234567890L      // 10 digits but starts with 1
    })
    void save_invalidMobileNumber_throwsAndDoesNotSave(long badPhone) {
        Bank newUser = bank(0, badPhone, 123456789012L, 0);

        MobileNumberException ex = assertThrows(MobileNumberException.class, () -> bankService.save(newUser));

        assertEquals("Invalid mobile number", ex.getMessage());
        verifyNoInteractions(userRepository);
    }

    @ParameterizedTest
    @ValueSource(longs = {
            12345678901L,     // 11 digits
            1234567890123L    // 13 digits
    })
    void save_invalidAadhar_throwsAndDoesNotSave(long badAadhar) {
        Bank newUser = bank(0, 9876543210L, badAadhar, 0);

        MobileNumberException ex = assertThrows(MobileNumberException.class, () -> bankService.save(newUser));

        assertEquals("Invalid AADHAR NUMBER", ex.getMessage());
        verifyNoInteractions(userRepository);
    }

    @Test
    void save_duplicateMobileNumber_throwsUserExist() {
        Bank existing = bank(1000000000L, 9876543210L, 111111111111L, 0);
        Bank newUser = bank(0, 9876543210L, 222222222222L, 0);
        when(userRepository.findAll()).thenReturn(List.of(existing));

        UserExistException ex = assertThrows(UserExistException.class, () -> bankService.save(newUser));

        assertEquals("mobile number already exist", ex.getMessage());
        verify(userRepository, never()).save(any());
    }

    @Test
    void save_duplicateAadhar_throwsUserExist() {
        Bank existing = bank(1000000000L, 9000000001L, 123456789012L, 0);
        Bank newUser = bank(0, 9876543210L, 123456789012L, 0);
        when(userRepository.findAll()).thenReturn(List.of(existing));

        UserExistException ex = assertThrows(UserExistException.class, () -> bankService.save(newUser));

        assertEquals("AADHAR NUMBER already exist", ex.getMessage());
        verify(userRepository, never()).save(any());
    }

    // ---------- deposit ----------

    @Test
    void depositByphno_increasesBalance_recordsCreditTransaction_andNotifiesKafka() {
        Bank user = bank(1000000000L, 9876543210L, 123456789012L, 1000);
        when(userRepository.findByphno(9876543210L)).thenReturn(user);
        when(bankTransactionRepository.findAll()).thenReturn(List.of());

        String result = bankService.depositByphno(9876543210L, 500);

        assertEquals("Deposit Successful Amount Inr : 500.0", result);
        assertEquals(1500, user.getBalance());

        ArgumentCaptor<BankTransaction> captor = ArgumentCaptor.forClass(BankTransaction.class);
        verify(bankTransactionRepository).save(captor.capture());
        BankTransaction txn = captor.getValue();
        assertEquals("Credit", txn.getAction());
        assertEquals(500, txn.getAmount());
        assertEquals(1500, txn.getBalance());
        assertEquals(100000L, txn.getTransactionId());   // first ever transaction

        verify(bankKafkaProducer).sendMessage(contains("Amount deposited successfully"));
        verify(userRepository).save(user);
    }

    // A broken notification channel must never turn a completed payment into an error.
    @Test
    void depositByphno_kafkaFailure_doesNotFailTheDeposit() {
        Bank user = bank(1000000000L, 9876543210L, 123456789012L, 1000);
        when(userRepository.findByphno(9876543210L)).thenReturn(user);
        when(bankTransactionRepository.findAll()).thenReturn(List.of());
        doThrow(new RuntimeException("kafka down")).when(bankKafkaProducer).sendMessage(any());

        String result = bankService.depositByphno(9876543210L, 500);

        assertEquals("Deposit Successful Amount Inr : 500.0", result);
        assertEquals(1500, user.getBalance());
    }

    @Test
    void depositByacno_increasesBalance_andUsesNextTransactionId() {
        Bank user = bank(1000000000L, 9876543210L, 123456789012L, 200);
        when(userRepository.findByacno(1000000000L)).thenReturn(user);
        when(bankTransactionRepository.findAll()).thenReturn(List.of(txnWithId(100000), txnWithId(100007)));

        bankService.depositByacno(1000000000L, 50);

        assertEquals(250, user.getBalance());
        ArgumentCaptor<BankTransaction> captor = ArgumentCaptor.forClass(BankTransaction.class);
        verify(bankTransactionRepository).save(captor.capture());
        assertEquals(100008L, captor.getValue().getTransactionId());   // last id + 1
        assertEquals("Credit", captor.getValue().getAction());
    }

    @Test
    void depositByphno_unknownUser_throwsUserNotFound_andSendsNoNotification() {
        when(userRepository.findByphno(9999999999L)).thenReturn(null);

        assertThrows(UserNotFoundException.class, () -> bankService.depositByphno(9999999999L, 100));

        verifyNoInteractions(bankKafkaProducer);
        verify(userRepository, never()).save(any());
    }

    // ---------- withdraw ----------

    @Test
    void withdrawByphno_decreasesBalance_recordsDebitTransaction_andNotifiesKafka() {
        Bank user = bank(1000000000L, 9876543210L, 123456789012L, 1000);
        when(userRepository.findByphno(9876543210L)).thenReturn(user);
        when(bankTransactionRepository.findAll()).thenReturn(List.of());

        String result = bankService.withdrawByphno(9876543210L, 400);

        assertEquals("Withdraw Successful Amount Inr : 400.0", result);
        assertEquals(600, user.getBalance());

        ArgumentCaptor<BankTransaction> captor = ArgumentCaptor.forClass(BankTransaction.class);
        verify(bankTransactionRepository).save(captor.capture());
        assertEquals("Debit", captor.getValue().getAction());
        assertEquals(400, captor.getValue().getAmount());
        assertEquals(600, captor.getValue().getBalance());

        verify(bankKafkaProducer).sendMessage(contains("Amount Withdraw successfully"));
        verify(userRepository).save(user);
    }

    @Test
    void withdrawByacno_decreasesBalance_whenGivenPositiveAmount() {
        Bank user = bank(1000000000L, 9876543210L, 123456789012L, 1000);
        when(userRepository.findByacno(1000000000L)).thenReturn(user);
        when(bankTransactionRepository.findAll()).thenReturn(List.of());

        bankService.withdrawByacno(1000000000L, 300);

        assertEquals(700, user.getBalance());
    }

    // The balance check must live INSIDE the service transaction; a check made earlier can be stale by the time we subtract.
    @Test
    void withdrawByphno_moreThanBalance_throwsAndChangesNothing() {
        Bank user = bank(1000000000L, 9876543210L, 123456789012L, 100);
        when(userRepository.findByphno(9876543210L)).thenReturn(user);

        WithdrawException ex = assertThrows(WithdrawException.class, () -> bankService.withdrawByphno(9876543210L, 100.01));

        assertEquals("Insufficient Funds", ex.getMessage());
        assertEquals(100, user.getBalance());
        verifyNoInteractions(bankTransactionRepository);
        verifyNoInteractions(bankKafkaProducer);
    }

    @Test
    void withdrawByacno_moreThanBalance_throwsAndChangesNothing() {
        Bank user = bank(1000000000L, 9876543210L, 123456789012L, 100);
        when(userRepository.findByacno(1000000000L)).thenReturn(user);

        assertThrows(WithdrawException.class, () -> bankService.withdrawByacno(1000000000L, 500));

        assertEquals(100, user.getBalance());
        verifyNoInteractions(bankTransactionRepository);
        verifyNoInteractions(bankKafkaProducer);
    }

    @Test
    void withdrawByphno_exactBalance_leavesZero() {
        Bank user = bank(1000000000L, 9876543210L, 123456789012L, 250);
        when(userRepository.findByphno(9876543210L)).thenReturn(user);
        when(bankTransactionRepository.findAll()).thenReturn(List.of());

        bankService.withdrawByphno(9876543210L, 250);

        assertEquals(0, user.getBalance());
    }

    @Test
    void withdrawByphno_unknownUser_throwsUserNotFound() {
        when(userRepository.findByphno(9999999999L)).thenReturn(null);

        assertThrows(UserNotFoundException.class, () -> bankService.withdrawByphno(9999999999L, 100));

        verifyNoInteractions(bankKafkaProducer);
        verifyNoInteractions(bankTransactionRepository);
    }

    // ---------- update phone number ----------

    @Test
    void updatePhno_success_changesNumber_andNotifiesKafka() {
        Bank user = bank(1000000000L, 9876543210L, 123456789012L, 0);
        when(userRepository.findAll()).thenReturn(List.of(user));
        when(userRepository.findByphno(9876543210L)).thenReturn(user);
        when(userRepository.save(user)).thenReturn(user);

        Bank updated = bankService.updatePhno(9876543210L, 9123456789L);

        assertEquals(9123456789L, updated.getPhno());
        verify(bankKafkaProducer).sendMessage(contains("Mobile number updated"));
    }

    @Test
    void updatePhno_newNumberAlreadyTaken_throwsUserExist() {
        Bank a = bank(1000000000L, 9876543210L, 111111111111L, 0);
        Bank b = bank(1000000001L, 9123456789L, 222222222222L, 0);
        when(userRepository.findAll()).thenReturn(List.of(a, b));

        assertThrows(UserExistException.class, () -> bankService.updatePhno(9876543210L, 9123456789L));

        verify(userRepository, never()).save(any());
    }

    // ---------- delete ----------

    @Test
    void deleteByPhno_unknownUser_throwsUserNotFound() {
        when(userRepository.findByphno(9999999999L)).thenReturn(null);

        assertThrows(UserNotFoundException.class, () -> bankService.deleteByPhno(9999999999L));

        verify(userRepository, never()).delete(any());
    }

    // ---------- display ----------

    @Test
    void displayUserByPhno_returnsDtoWithUppercaseLastNameFirstName() {
        Bank user = bank(1000000000L, 9876543210L, 123456789012L, 750);
        when(userRepository.findByphno(9876543210L)).thenReturn(user);

        var dto = bankService.displayUserByPhno(9876543210L);

        assertEquals("KUMAR CHARAN", dto.getName());
        assertEquals(750, dto.getBalance());
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
        user.setTransactions(List.of(txnWithId(100000)));
        when(userRepository.findByphno(9876543210L)).thenReturn(user);

        assertEquals(1, bankService.displayTransactionByPhno(9876543210L).size());
    }

    @Test
    void displayTransactionByPhno_unknownUser_throwsUserNotFound() {
        when(userRepository.findByphno(9999999999L)).thenReturn(null);

        assertThrows(UserNotFoundException.class, () -> bankService.displayTransactionByPhno(9999999999L));
    }
}
