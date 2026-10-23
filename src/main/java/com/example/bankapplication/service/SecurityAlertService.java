package com.example.bankapplication.service;

import com.example.bankapplication.entity.Bank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Tells a customer by email when something security-relevant happens on their account: a login, an account lockout after
 * wrong PINs, a PIN change/reset, or the alert email address being changed (that one goes to the OLD address, so a
 * takeover cannot silently redirect alerts). Sent on a background thread and never throws - a slow or broken mail
 * server must not slow down or fail a login. Accounts with no email on file get nothing.
 */
@Service
public class SecurityAlertService {
    private static final Logger log = LoggerFactory.getLogger(SecurityAlertService.class);

    public enum Kind { LOGIN, LOCKED_OUT, PIN_CHANGED, EMAIL_CHANGED, LIMIT_CHANGED }

    private final MailService mailService;
    private final boolean enabled;
    private final boolean loginAlerts;
    private final ExecutorService sender;

    @org.springframework.beans.factory.annotation.Autowired
    public SecurityAlertService(MailService mailService,
                                @Value("${bank.alerts.enabled:true}") boolean enabled,
                                @Value("${bank.alerts.login:true}") boolean loginAlerts) {
        this(mailService, enabled, loginAlerts, Executors.newSingleThreadExecutor(runnable -> {
            Thread t = new Thread(runnable, "security-alert-mail");
            t.setDaemon(true);
            return t;
        }));
    }

    // Tests pass a direct (same-thread) executor.
    SecurityAlertService(MailService mailService, boolean enabled, boolean loginAlerts, ExecutorService sender) {
        this.mailService = mailService;
        this.enabled = enabled;
        this.loginAlerts = loginAlerts;
        this.sender = sender;
    }

    /** Alerts the account's email on file; for EMAIL_CHANGED pass the address that was just replaced as {@code toEmail}. */
    public void alert(Kind kind, Bank account, String toEmail, Instant when) {
        if (!enabled || (kind == Kind.LOGIN && !loginAlerts) || toEmail == null || toEmail.isBlank()) {
            return;
        }
        String phone = mask(account.getPhno());
        String subject;
        String body;
        switch (kind) {
            case LOGIN -> {
                subject = "New login to your bank account";
                body = "Your account " + phone + " was logged in to at " + when + ". If this was you, nothing more to do. "
                        + "If not, change your PIN now (Forgot PIN) and contact the bank.";
            }
            case LOCKED_OUT -> {
                subject = "Your bank account was temporarily locked";
                body = "There were too many wrong PIN attempts on account " + phone + " at " + when + ", so it is locked for a while. "
                        + "If this wasn't you, someone may be guessing your PIN - change it once the lock ends.";
            }
            case PIN_CHANGED -> {
                subject = "Your bank PIN was changed";
                body = "The PIN for account " + phone + " was changed at " + when + ". All earlier sessions were signed out. "
                        + "If this wasn't you, contact the bank immediately.";
            }
            case EMAIL_CHANGED -> {
                subject = "The email on your bank account was changed";
                body = "The email address for account " + phone + " (this one) was replaced at " + when + ". "
                        + "If this wasn't you, contact the bank immediately.";
            }
            case LIMIT_CHANGED -> {
                subject = "Your daily limit was changed";
                body = "The daily limit on account " + phone + " was changed at " + when + ". A higher limit only starts after a waiting period. "
                        + "If this wasn't you, change your PIN and contact the bank immediately.";
            }
            default -> {
                return;
            }
        }
        try {
            sender.execute(() -> {
                try {
                    mailService.send(toEmail, subject, body);
                } catch (RuntimeException e) {
                    log.warn("Security alert {} could not be sent: {}", kind, e.getMessage());
                }
            });
        } catch (RuntimeException e) {
            log.warn("Security alert {} could not be queued: {}", kind, e.getMessage());
        }
    }

    private static String mask(long phno) {
        String s = String.valueOf(phno);
        return "XXXXXX" + s.substring(Math.max(0, s.length() - 4));
    }
}
