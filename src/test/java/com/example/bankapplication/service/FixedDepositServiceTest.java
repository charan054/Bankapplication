package com.example.bankapplication.service;

import com.example.bankapplication.entity.Bank;
import com.example.bankapplication.entity.BankTransaction;
import com.example.bankapplication.entity.FixedDeposit;
import com.example.bankapplication.exception.InvalidRequestException;
import com.example.bankapplication.exception.UserNotFoundException;
import com.example.bankapplication.exception.WithdrawException;
import com.example.bankapplication.repository.BankRepository;
import com.example.bankapplication.repository.BankTransactionRepository;
import com.example.bankapplication.repository.FixedDepositRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FixedDepositServiceTest {
    private static final long PHNO = 9876543210L;
    private static final Instant NOW = Instant.parse("2026-10-30T10:00:00Z");

    private FixedDepositRepository deposits;
    private BankRepository users;
    private BankTransactionRepository ledger;
    private FixedDepositService service;
    private Bank account;

    @BeforeEach
    void setUp() {
        deposits = mock(FixedDepositRepository.class);
        users = mock(BankRepository.class);
        ledger = mock(BankTransactionRepository.class);
        service = new FixedDepositService(deposits, users, ledger, Clock.fixed(NOW, ZoneOffset.UTC),
                new BigDecimal("1000"), "Asia/Kolkata");
        account = new Bank();
        account.setPhno(PHNO);
        account.setBalance(new BigDecimal("20000.00"));
        when(users.findByphno(PHNO)).thenReturn(account);
        when(ledger.findMaxTransactionId()).thenReturn(100500L);
        when(deposits.save(any(FixedDeposit.class))).thenAnswer(i -> i.getArgument(0));
    }

    private FixedDeposit active(long id, String principal, String rate, int months, Instant matures) {
        FixedDeposit fd = new FixedDeposit();
        fd.setId(id);
        fd.setPhno(PHNO);
        fd.setPrincipal(new BigDecimal(principal));
        fd.setRatePercent(new BigDecimal(rate));
        fd.setTermMonths(months);
        fd.setMaturesAt(matures);
        return fd;
    }

    @Test
    void interestIsSimpleAndRoundedToThePaisa() {
        // 10000 x 7% for a year
        assertEquals(new BigDecimal("700.00"), FixedDepositService.interest(new BigDecimal("10000.00"), new BigDecimal("7.00"), 12));
        // 5000 x 6.5% for 6 months = 162.50
        assertEquals(new BigDecimal("162.50"), FixedDepositService.interest(new BigDecimal("5000.00"), new BigDecimal("6.50"), 6));
        // 1234.57 x 6% for 3 months = 18.51855 -> 18.52
        assertEquals(new BigDecimal("18.52"), FixedDepositService.interest(new BigDecimal("1234.57"), new BigDecimal("6.00"), 3));
    }

    @Test
    void openingTakesTheMoneyFromSavingsAndWritesAnFdLedgerRow() {
        FixedDeposit fd = service.open(PHNO, new BigDecimal("5000"), 6);

        assertEquals(new BigDecimal("15000.00"), account.getBalance());
        assertEquals(new BigDecimal("5000.00"), fd.getPrincipal());
        assertEquals(new BigDecimal("6.50"), fd.getRatePercent());
        assertEquals(FixedDeposit.ACTIVE, fd.getStatus());
        assertEquals(NOW, fd.getOpenedAt());
        assertTrue(fd.getMaturesAt().isAfter(NOW.plusSeconds(150L * 24 * 3600)));
        assertTrue(fd.getMaturesAt().isBefore(NOW.plusSeconds(190L * 24 * 3600)));

        ArgumentCaptor<BankTransaction> row = ArgumentCaptor.forClass(BankTransaction.class);
        verify(ledger).save(row.capture());
        assertEquals("FD Opened", row.getValue().getAction());
        assertEquals(new BigDecimal("15000.00"), row.getValue().getBalance());
        assertEquals(100501L, row.getValue().getTransactionId());
        assertTrue(row.getValue().isMoneyOut());
    }

    @Test
    void anUnknownTermTooSmallAnAmountOrTooLittleBalanceChangesNothing() {
        assertThrows(InvalidRequestException.class, () -> service.open(PHNO, new BigDecimal("5000"), 5));
        assertThrows(InvalidRequestException.class, () -> service.open(PHNO, new BigDecimal("999.99"), 6));
        assertThrows(InvalidRequestException.class, () -> service.open(PHNO, null, 6));
        assertThrows(WithdrawException.class, () -> service.open(PHNO, new BigDecimal("20000.01"), 6));

        assertEquals(new BigDecimal("20000.00"), account.getBalance());
        verify(deposits, never()).save(any());
        verify(ledger, never()).save(any());
    }

    @Test
    void openingForAnUnknownAccountIsRefused() {
        when(users.findByphno(PHNO)).thenReturn(null);

        assertThrows(UserNotFoundException.class, () -> service.open(PHNO, new BigDecimal("5000"), 6));
    }

    @Test
    void aMaturedDepositIsPaidWithItsInterestWhenTheCustomerLooks() {
        FixedDeposit due = active(1, "10000.00", "7.00", 12, NOW.minusSeconds(60));
        when(deposits.findByPhnoAndStatusAndMaturesAtLessThanEqual(PHNO, "ACTIVE", NOW)).thenReturn(List.of(due));
        when(deposits.findByPhnoOrderByIdDesc(PHNO)).thenReturn(List.of(due));

        List<FixedDeposit> listed = service.list(PHNO);

        assertEquals(new BigDecimal("30700.00"), account.getBalance());
        assertEquals(FixedDeposit.MATURED, listed.get(0).getStatus());
        assertEquals(new BigDecimal("10700.00"), listed.get(0).getPayout());
        assertEquals(NOW, listed.get(0).getClosedAt());
        ArgumentCaptor<BankTransaction> row = ArgumentCaptor.forClass(BankTransaction.class);
        verify(ledger).save(row.capture());
        assertEquals("FD Closed", row.getValue().getAction());
        assertFalse(row.getValue().isMoneyOut());
    }

    @Test
    void anUnmaturedDepositIsLeftAloneByListing() {
        FixedDeposit later = active(1, "10000.00", "7.00", 12, NOW.plusSeconds(3600));
        when(deposits.findByPhnoAndStatusAndMaturesAtLessThanEqual(PHNO, "ACTIVE", NOW)).thenReturn(List.of());
        when(deposits.findByPhnoOrderByIdDesc(PHNO)).thenReturn(List.of(later));

        service.list(PHNO);

        assertEquals(new BigDecimal("20000.00"), account.getBalance());
        verify(ledger, never()).save(any());
    }

    @Test
    void breakingEarlyReturnsThePrincipalOnly() {
        FixedDeposit fd = active(2, "5000.00", "6.50", 6, NOW.plusSeconds(86400));
        when(deposits.findById(2L)).thenReturn(Optional.of(fd));

        FixedDeposit closed = service.breakEarly(PHNO, 2);

        assertEquals(FixedDeposit.BROKEN, closed.getStatus());
        assertEquals(new BigDecimal("5000.00"), closed.getPayout());
        assertEquals(new BigDecimal("25000.00"), account.getBalance());
    }

    @Test
    void breakingADepositThatAlreadyMaturedPaysItInFullInsteadOfPenalisingIt() {
        FixedDeposit due = active(2, "5000.00", "6.50", 6, NOW.minusSeconds(60));
        when(deposits.findByPhnoAndStatusAndMaturesAtLessThanEqual(PHNO, "ACTIVE", NOW)).thenReturn(List.of(due));
        when(deposits.findById(2L)).thenReturn(Optional.of(due));

        assertThrows(InvalidRequestException.class, () -> service.breakEarly(PHNO, 2));   // already closed by the maturity pass

        assertEquals(FixedDeposit.MATURED, due.getStatus());
        assertEquals(new BigDecimal("5162.50"), due.getPayout());
        assertEquals(new BigDecimal("25162.50"), account.getBalance());
    }

    @Test
    void youCannotBreakSomeoneElsesOrAnAlreadyClosedDeposit() {
        FixedDeposit theirs = active(3, "5000.00", "6.50", 6, NOW.plusSeconds(86400));
        theirs.setPhno(9111111111L);
        when(deposits.findById(3L)).thenReturn(Optional.of(theirs));
        FixedDeposit closed = active(4, "5000.00", "6.50", 6, NOW.plusSeconds(86400));
        closed.setStatus(FixedDeposit.BROKEN);
        when(deposits.findById(4L)).thenReturn(Optional.of(closed));
        when(deposits.findById(5L)).thenReturn(Optional.empty());

        assertThrows(InvalidRequestException.class, () -> service.breakEarly(PHNO, 3));
        assertThrows(InvalidRequestException.class, () -> service.breakEarly(PHNO, 4));
        assertThrows(InvalidRequestException.class, () -> service.breakEarly(PHNO, 5));

        assertEquals(new BigDecimal("20000.00"), account.getBalance());
        assertNotNull(theirs);
        verify(ledger, never()).save(any());
    }

    @Test
    void theThreeTermsAreOfferedInOrder() {
        List<FixedDepositService.RateView> rates = service.rates();

        assertEquals(List.of(3, 6, 12), rates.stream().map(FixedDepositService.RateView::termMonths).toList());
        assertEquals(new BigDecimal("7.00"), rates.get(2).ratePercent());
    }
}
