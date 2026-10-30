package com.example.bankapplication.service;

import com.example.bankapplication.entity.Bank;
import com.example.bankapplication.entity.BankTransaction;
import com.example.bankapplication.exception.UserNotFoundException;
import com.example.bankapplication.exception.WithdrawException;
import com.example.bankapplication.repository.BankRepository;
import com.example.bankapplication.repository.BankTransactionRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The bank portal's help chat. No AI model: it recognises the question from keywords and answers from the signed-in
 * customer's own account (balance, daily limit, last transactions) or explains where a thing is done in the portal. It only
 * looks things up and explains - it never moves money, changes a limit or a PIN; those stay on their own cards where the
 * customer confirms them (and PINs are never typed into the chat).
 */
@Service
public class HelpChatService {
    static final int MAX_MESSAGE = 300;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("dd MMM, HH:mm", Locale.ENGLISH);
    private static final List<String> MENU = List.of("My balance", "My daily limit", "Last transactions", "Payment problem", "Forgot PIN");

    public record Reply(String text, List<String> suggestions) {
    }

    private final BankRepository users;
    private final BankTransactionRepository transactions;
    private final DailyLimitService limits;
    private final Clock clock;
    private final ZoneId zone = ZoneId.of("Asia/Kolkata");

    public HelpChatService(BankRepository users, BankTransactionRepository transactions, DailyLimitService limits, Clock clock) {
        this.users = users;
        this.transactions = transactions;
        this.limits = limits;
        this.clock = clock;
    }

    public Reply ask(long phno, String message) {
        if (message == null || message.isBlank()) {
            throw new WithdrawException("Type a question first.");
        }
        if (message.length() > MAX_MESSAGE) {
            throw new WithdrawException("Please keep your question under " + MAX_MESSAGE + " characters.");
        }
        String m = message.toLowerCase(Locale.ROOT).trim();

        if (has(m, "human", "agent", "real person", "complain", "customer care", "branch")) {
            return new Reply("I'm a simple helper and can't reach a person for you. For something I can't solve, please contact the bank directly "
                    + "with your account number.", MENU);
        }
        if (has(m, "forgot", "reset pin", "change pin", "pin reset", "pin locked", "locked", "wrong pin")) {
            return new Reply("Never type your PIN into this chat. To reset it, use Forgot PIN on the login card: we email you a one-time code and "
                    + "you choose a new PIN. After several wrong PINs the account locks for a short while - wait, then try again or reset it.", MENU);
        }
        if (has(m, "failed", "not received", "stuck", "debited", "deducted", "double charge", "payment problem", "refund", "money back", "transfer problem")) {
            return payment(phno);
        }
        if (has(m, "balance", "how much money", "money do i have")) {
            return balance(phno);
        }
        if (has(m, "limit")) {
            return limit(phno);
        }
        if (has(m, "last transaction", "recent", "history", "transactions", "statement", "spent", "spending")) {
            return recent(phno);
        }
        if (has(m, "login", "logged in", "sessions", "sign out", "hacked", "someone else")) {
            return new Reply("Open the Active logins card to see where your account is signed in and end any you don't recognise, or choose "
                    + "sign out everywhere. If you think someone else knows your PIN, reset it with Forgot PIN afterwards.", MENU);
        }
        if (has(m, "hello", "hi", "hey", "help", "menu", "start")) {
            return new Reply("Hi! I can look up your balance, daily limit and recent transactions, and explain payments and PIN problems. What do you need?", MENU);
        }
        return new Reply("Sorry, I didn't understand that. I can look up your balance, daily limit and recent transactions, and explain "
                + "failed payments and PIN problems.", MENU);
    }

    private static boolean has(String m, String... words) {
        for (String w : words) {
            // short words must match a whole word ("hi" must not match "this")
            if (w.length() <= 3 ? Pattern.compile("\\b" + Pattern.quote(w) + "\\b").matcher(m).find() : m.contains(w)) {
                return true;
            }
        }
        return false;
    }

    private Bank account(long phno) {
        Bank user = users.findByphno(phno);
        if (user == null) {
            throw new UserNotFoundException("User not found");
        }
        return user;
    }

    private Reply balance(long phno) {
        Bank user = account(phno);
        return new Reply("Your balance is Rs. " + money(user.getBalance()) + ".", List.of("My daily limit", "Last transactions"));
    }

    private Reply limit(long phno) {
        DailyLimitService.LimitView v = limits.view(phno);
        StringBuilder text = new StringBuilder("Your daily limit is Rs. " + money(v.dailyLimit()) + "; you have used Rs. " + money(v.usedToday())
                + " today, so Rs. " + money(v.remainingToday()) + " is left.");
        if (v.pendingLimit() != null && v.pendingLimitEffectiveAt() != null) {
            text.append(" A raise to Rs. ").append(money(v.pendingLimit())).append(" starts at ")
                    .append(STAMP.format(v.pendingLimitEffectiveAt().atZone(zone))).append(" (raising waits 24 hours; lowering is immediate).");
        }
        text.append(" You can change it on the Daily limit card.");
        return new Reply(text.toString(), List.of("My balance", "Last transactions"));
    }

    private Reply recent(long phno) {
        List<BankTransaction> rows = transactions.findByPhno(phno, null, null, PageRequest.of(0, 3, Sort.by(Sort.Direction.DESC, "createdAt"))).getContent();
        if (rows.isEmpty()) {
            return new Reply("There are no transactions on your account yet.", MENU);
        }
        StringBuilder text = new StringBuilder("Your last " + rows.size() + " transaction(s):");
        for (BankTransaction t : rows) {
            text.append("\n").append(t.getCreatedAt() == null ? "earlier" : STAMP.format(t.getCreatedAt().atZone(zone))).append(" - ")
                    .append(t.isMoneyOut() ? "paid out" : "received").append(" Rs. ").append(money(t.getAmount()))
                    .append(" (balance Rs. ").append(money(t.getBalance())).append(")");
        }
        text.append("\nThe full list and a CSV are on the Statement card; the Monthly Summary card shows each month.");
        return new Reply(text.toString(), List.of("My balance", "Payment problem"));
    }

    private Reply payment(long phno) {
        List<BankTransaction> rows = transactions.findByPhno(phno, null, null, PageRequest.of(0, 1, Sort.by(Sort.Direction.DESC, "createdAt"))).getContent();
        String last = rows.isEmpty() ? "" : " Your latest entry is a " + (rows.get(0).isMoneyOut() ? "debit" : "credit")
                + " of Rs. " + money(rows.get(0).getAmount()) + (rows.get(0).getCreatedAt() == null ? "" : " on " + STAMP.format(rows.get(0).getCreatedAt().atZone(zone))) + ".";
        return new Reply("A payment or transfer that failed is not taken from your account. If an amount left your balance and the payment "
                + "did not go through, it is returned automatically within a few working days, and a refunded shop order appears as a credit." + last
                + " Check the Statement card for the exact entries; if a debit has no matching payment after 3 working days, contact the bank with the "
                + "transaction id from the statement.", MENU);
    }

    private static String money(java.math.BigDecimal v) {
        return v == null ? "0.00" : String.format(Locale.ENGLISH, "%,.2f", v);
    }
}
