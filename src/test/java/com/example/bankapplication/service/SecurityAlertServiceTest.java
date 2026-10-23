package com.example.bankapplication.service;

import com.example.bankapplication.entity.Bank;
import com.example.bankapplication.service.SecurityAlertService.Kind;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class SecurityAlertServiceTest {

    private static final Instant WHEN = Instant.parse("2026-10-24T10:00:00Z");

    /** Runs tasks on the calling thread so the test can assert right away. */
    private static final class DirectExecutor extends AbstractExecutorService {
        @Override public void execute(Runnable command) { command.run(); }
        @Override public void shutdown() { }
        @Override public List<Runnable> shutdownNow() { return List.of(); }
        @Override public boolean isShutdown() { return false; }
        @Override public boolean isTerminated() { return false; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
    }

    private final MailService mail = mock(MailService.class);
    private final ExecutorService direct = new DirectExecutor();

    private SecurityAlertService service(boolean enabled, boolean loginAlerts) {
        return new SecurityAlertService(mail, enabled, loginAlerts, direct);
    }

    private static Bank account() {
        Bank b = new Bank();
        b.setPhno(9876543210L);
        return b;
    }

    @Test
    void aLoginEmailNamesTheMaskedAccountAndNeverTheFullNumber() {
        service(true, true).alert(Kind.LOGIN, account(), "me@example.com", WHEN);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(mail).send(eq("me@example.com"), eq("New login to your bank account"), body.capture());
        assertTrue(body.getValue().contains("XXXXXX3210"));
        assertFalse(body.getValue().contains("9876543210"));
    }

    @Test
    void eachKindHasItsOwnSubject() {
        SecurityAlertService s = service(true, true);
        s.alert(Kind.LOCKED_OUT, account(), "a@example.com", WHEN);
        s.alert(Kind.PIN_CHANGED, account(), "a@example.com", WHEN);
        s.alert(Kind.EMAIL_CHANGED, account(), "old@example.com", WHEN);

        verify(mail).send(eq("a@example.com"), eq("Your bank account was temporarily locked"), anyString());
        verify(mail).send(eq("a@example.com"), eq("Your bank PIN was changed"), anyString());
        verify(mail).send(eq("old@example.com"), eq("The email on your bank account was changed"), anyString());
    }

    @Test
    void noEmailOnFileMeansNoAlert() {
        service(true, true).alert(Kind.LOGIN, account(), null, WHEN);
        service(true, true).alert(Kind.LOGIN, account(), "  ", WHEN);

        verify(mail, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void alertsCanBeSwitchedOffAndLoginAlertsSeparately() {
        service(false, true).alert(Kind.PIN_CHANGED, account(), "a@example.com", WHEN);
        service(true, false).alert(Kind.LOGIN, account(), "a@example.com", WHEN);
        verify(mail, never()).send(anyString(), anyString(), anyString());

        service(true, false).alert(Kind.PIN_CHANGED, account(), "a@example.com", WHEN);
        verify(mail).send(eq("a@example.com"), eq("Your bank PIN was changed"), anyString());
    }

    @Test
    void aFailingMailServerNeverThrowsToTheCaller() {
        doThrow(new RuntimeException("smtp down")).when(mail).send(anyString(), anyString(), anyString());

        service(true, true).alert(Kind.LOGIN, account(), "a@example.com", WHEN);
    }
}
