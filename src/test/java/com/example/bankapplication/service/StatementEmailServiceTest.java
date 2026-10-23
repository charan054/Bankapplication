package com.example.bankapplication.service;

import com.example.bankapplication.entity.Bank;
import com.example.bankapplication.entity.BankTransaction;
import com.example.bankapplication.exception.WithdrawException;
import com.example.bankapplication.repository.BankRepository;
import com.example.bankapplication.repository.BankTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StatementEmailServiceTest {
    private static final long PHNO = 9876543210L;
    private static final Instant NOW = Instant.parse("2026-10-24T10:00:00Z");

    private BankRepository users;
    private BankTransactionRepository transactions;
    private MailService mail;
    private StatementEmailService service;
    private Bank bank;

    @BeforeEach
    void setUp() {
        users = mock(BankRepository.class);
        transactions = mock(BankTransactionRepository.class);
        mail = mock(MailService.class);
        when(mail.sendWithAttachment(anyString(), anyString(), anyString(), anyString(), any(), anyString())).thenReturn(true);
        service = new StatementEmailService(users, transactions, mail, Clock.fixed(NOW, ZoneOffset.UTC), "Asia/Kolkata", true);
        bank = new Bank();
        bank.setPhno(PHNO);
        bank.setEmail("me@example.com");
        bank.setBalance(new BigDecimal("1234.50"));
        when(users.findByphno(PHNO)).thenReturn(bank);
    }

    private static BankTransaction tx(long id, String action, String amount, String balance, String at) {
        BankTransaction t = new BankTransaction();
        t.setTransactionId(id);
        t.setAction(action);
        t.setAmount(new BigDecimal(amount));
        t.setBalance(new BigDecimal(balance));
        t.setCreatedAt(Instant.parse(at));
        return t;
    }

    @Test
    void theMonthsStatementIsAnAttachedCsvWithTotalsInTheBody() {
        when(transactions.findByPhno(eq(PHNO), any(), any(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(
                tx(100001, "Credit", "1000.00", "1000.00", "2026-09-02T05:00:00Z"),
                tx(100002, "Debit", "250.50", "749.50", "2026-09-10T05:00:00Z"))));

        assertTrue(service.sendFor(bank, YearMonth.of(2026, 9)));

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<byte[]> file = ArgumentCaptor.forClass(byte[].class);
        verify(mail).sendWithAttachment(eq("me@example.com"), eq("Your account statement for 2026-09"), body.capture(),
                eq("statement-2026-09.csv"), file.capture(), eq("text/csv"));
        assertTrue(body.getValue().contains("Money in:  INR 1000.00"));
        assertTrue(body.getValue().contains("Money out: INR 250.50"));
        assertTrue(body.getValue().contains("XXXXXX3210"));
        assertFalse(body.getValue().contains("9876543210"));
        String csv = new String(file.getValue(), StandardCharsets.UTF_8);
        assertTrue(csv.startsWith("Transaction ID,Date,Type,Amount,Balance after\n"));
        assertTrue(csv.contains("100002,2026-09-10 10:30:00,Debit,250.50,749.50"));   // shown in Indian time
    }

    @Test
    void theMonthIsCutInIndianTimeNotUtc() {
        when(transactions.findByPhno(eq(PHNO), any(), any(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
        service.sendFor(bank, YearMonth.of(2026, 9));

        ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> to = ArgumentCaptor.forClass(Instant.class);
        verify(transactions).findByPhno(eq(PHNO), from.capture(), to.capture(), any(Pageable.class));
        assertEquals(Instant.parse("2026-08-31T18:30:00Z"), from.getValue());
        assertEquals(Instant.parse("2026-09-30T18:29:59.999Z"), to.getValue());
    }

    @Test
    void noEmailOnFileMeansNothingIsSent() {
        bank.setEmail(" ");

        assertFalse(service.sendFor(bank, YearMonth.of(2026, 9)));
        verify(mail, never()).sendWithAttachment(anyString(), anyString(), anyString(), anyString(), any(), anyString());
    }

    @Test
    void onRequest_aFutureMonthIsRefusedAndAMissingEmailIsExplained() {
        assertThrows(WithdrawException.class, () -> service.sendOnRequest(PHNO, YearMonth.of(2027, 1)));

        bank.setEmail(null);
        WithdrawException e = assertThrows(WithdrawException.class, () -> service.sendOnRequest(PHNO, YearMonth.of(2026, 9)));
        assertTrue(e.getMessage().contains("email"));
    }

    @Test
    void onRequest_aMailFailureIsReportedNotSwallowed() {
        when(mail.sendWithAttachment(anyString(), anyString(), anyString(), anyString(), any(), anyString())).thenReturn(false);
        when(transactions.findByPhno(eq(PHNO), any(), any(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        assertThrows(WithdrawException.class, () -> service.sendOnRequest(PHNO, YearMonth.of(2026, 9)));
    }

    @Test
    void theMonthlyJobDoesNothingWhileDisabledAndMailsEveryoneWithAnEmailWhenOn() {
        StatementEmailService off = new StatementEmailService(users, transactions, mail, Clock.fixed(NOW, ZoneOffset.UTC), "Asia/Kolkata", false);
        off.sendMonthlyStatements();
        verify(users, never()).findAll();

        Bank noEmail = new Bank();
        noEmail.setPhno(9123456789L);
        when(users.findAll()).thenReturn(List.of(bank, noEmail));
        when(transactions.findByPhno(anyLongArg(), any(), any(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        service.sendMonthlyStatements();

        verify(mail).sendWithAttachment(eq("me@example.com"), eq("Your account statement for 2026-09"), anyString(), anyString(), any(), anyString());
    }

    private static long anyLongArg() {
        return org.mockito.ArgumentMatchers.anyLong();
    }
}
