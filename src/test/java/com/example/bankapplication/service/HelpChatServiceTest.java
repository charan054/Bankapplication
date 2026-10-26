package com.example.bankapplication.service;

import com.example.bankapplication.entity.Bank;
import com.example.bankapplication.entity.BankTransaction;
import com.example.bankapplication.exception.WithdrawException;
import com.example.bankapplication.repository.BankRepository;
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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HelpChatServiceTest {
    private static final long PHNO = 9876543210L;

    private BankRepository users;
    private BankTransactionRepository transactions;
    private DailyLimitService limits;
    private HelpChatService chat;

    @BeforeEach
    void setUp() {
        users = mock(BankRepository.class);
        transactions = mock(BankTransactionRepository.class);
        limits = mock(DailyLimitService.class);
        chat = new HelpChatService(users, transactions, limits, Clock.fixed(Instant.parse("2026-10-26T10:00:00Z"), ZoneOffset.UTC));
        Bank bank = new Bank();
        bank.setPhno(PHNO);
        bank.setBalance(new BigDecimal("12345.5"));
        when(users.findByphno(PHNO)).thenReturn(bank);
    }

    private static BankTransaction tx(String action, String amount, String balance, String at) {
        BankTransaction t = new BankTransaction();
        t.setAction(action);
        t.setAmount(new BigDecimal(amount));
        t.setBalance(new BigDecimal(balance));
        t.setCreatedAt(Instant.parse(at));
        return t;
    }

    @Test
    void balanceIsTheCallersOwn() {
        assertTrue(chat.ask(PHNO, "What is my balance?").text().contains("Rs. 12,345.50"));
        verify(users, never()).findByphno(9000000001L);
    }

    @Test
    void limitShowsUsedAndRemainingAndAPendingRaise() {
        when(limits.view(PHNO)).thenReturn(new DailyLimitService.LimitView(new BigDecimal("50000"), new BigDecimal("1000"),
                new BigDecimal("49000"), new BigDecimal("80000"), Instant.parse("2026-10-27T10:00:00Z"), new BigDecimal("200000"), false));

        String text = chat.ask(PHNO, "what is my daily limit").text();

        assertTrue(text.contains("Rs. 50,000.00") && text.contains("Rs. 49,000.00 is left"), text);
        assertTrue(text.contains("raise to Rs. 80,000.00"), text);
    }

    @Test
    void lastTransactionsAreListedNewestFirstAsPaidOutOrReceived() {
        when(transactions.findByPhno(anyLong(), any(), any(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(
                tx("Debit", "250", "9750", "2026-10-25T10:00:00Z"), tx("Credit", "10000", "10000", "2026-10-24T10:00:00Z"))));

        String text = chat.ask(PHNO, "show my last transactions").text();

        assertTrue(text.contains("paid out Rs. 250.00") && text.contains("received Rs. 10,000.00"), text);
    }

    @Test
    void pinQuestionsNeverAskForThePinAndPaymentProblemsExplainRefunds() {
        assertTrue(chat.ask(PHNO, "I forgot my pin").text().contains("Never type your PIN"));
        when(transactions.findByPhno(anyLong(), any(), any(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
        assertTrue(chat.ask(PHNO, "money debited but payment failed").text().contains("not taken from your account"));
    }

    @Test
    void greetingsGetTheMenuUnknownThingsGetAnApology() {
        assertTrue(chat.ask(PHNO, "hi").text().startsWith("Hi!"));
        assertFalse(chat.ask(PHNO, "this is odd").text().startsWith("Hi!"));
        assertTrue(chat.ask(PHNO, "purple elephants").text().contains("didn't understand"));
    }

    @Test
    void emptyAndHugeMessagesAreRefused() {
        assertThrows(WithdrawException.class, () -> chat.ask(PHNO, " "));
        assertThrows(WithdrawException.class, () -> chat.ask(PHNO, "x".repeat(HelpChatService.MAX_MESSAGE + 1)));
    }
}
