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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
@Service
public class BankService {
    private static final Logger log = LoggerFactory.getLogger(BankService.class);
    static final int MAX_FAILED_LOGIN_ATTEMPTS = 5;
    static final Duration LOCKOUT_DURATION = Duration.ofMinutes(15);
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;

    @Autowired
    private BankRepository userRepository;
    @Autowired
    BankTransactionRepository bankTransactionRepository;
    @Autowired
    TransferRepository transferRepository;
    @Autowired
    BankKafkaProducer bankKafkaProducer;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private SessionService sessionService;
    @Autowired
    private Clock clock;

    public Bank findByacno(long acno) {
        return userRepository.findByacno(acno);
    }
    public Bank findByphno(long phno) {
        return userRepository.findByphno(phno);
    }
    public PageResponse<BankDto> findAll(int page, int size)
    {
        return PageResponse.of(userRepository.findAll(pageable(page, size, Sort.by("userId").ascending()))
                .map(b -> new BankDto(b.getUserId(), b.getAcno(), (b.getLastName() + " " + b.getFirstName()).toUpperCase(),
                        b.getAadharNumber(), b.getPhno(), b.getBalance())));
    }

    // ---------- authentication ----------

    /**
     * Deliberately generic on a wrong phone number or PIN ("Invalid phone number or PIN"), so a login attempt
     * cannot be used to discover whether a phone number has an account at all.
     */
    // noRollbackFor: a wrong PIN increments failedLoginAttempts and then throws. Without this, Spring's default
    // rollback-on-unchecked-exception behaviour would undo that save too, silently defeating the lockout.
    @Transactional(noRollbackFor = InvalidCredentialsException.class)
    public LoginResponse login(long phno, String rawPin)
    {
        Bank user = userRepository.findByphno(phno);
        if (user == null)
        {
            throw new InvalidCredentialsException("Invalid phone number or PIN");
        }
        if (user.getPinHash() == null)
        {
            throw new PinNotSetException("This account has no PIN yet. Ask an administrator to set one.");
        }
        Instant now = clock.instant();
        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(now))
        {
            throw new AccountLockedException("Too many failed attempts. Try again after "
                    + DateTimeFormatter.ISO_INSTANT.format(user.getLockedUntil()) + ".");
        }
        if (!passwordEncoder.matches(rawPin, user.getPinHash()))
        {
            registerFailedAttempt(user, now);
            throw new InvalidCredentialsException("Invalid phone number or PIN");
        }
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        userRepository.save(user);
        SessionService.IssuedSession session = sessionService.start(phno);
        String name = (user.getLastName()+" "+user.getFirstName()).toUpperCase();
        return new LoginResponse(session.token(), session.expiresAt(), phno, name);
    }

    public void logout(String token)
    {
        sessionService.end(token);
    }

    private void registerFailedAttempt(Bank user, Instant now)
    {
        int attempts = user.getFailedLoginAttempts() + 1;
        user.setFailedLoginAttempts(attempts);
        if (attempts >= MAX_FAILED_LOGIN_ATTEMPTS)
        {
            user.setLockedUntil(now.plus(LOCKOUT_DURATION));
        }
        userRepository.save(user);
    }

    /** Admin-only: bootstraps an account created before login existed, or resets a forgotten PIN. */
    @Transactional
    public void setPin(long phno, String newPin)
    {
        Bank user = userRepository.findByphno(phno);
        if (user == null)
        {
            throw new UserNotFoundException("User not found");
        }
        user.setPinHash(passwordEncoder.encode(newPin));
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        userRepository.save(user);
        // an admin resetting a PIN is usually a response to it being compromised - a session opened under the
        // old PIN must not go on working after that
        sessionService.invalidateAllFor(phno);
    }

    // ---------- registration ----------

    @Transactional
    public Bank register(RegisterRequest request)
    {
        Bank user = new Bank();
        user.setFirstName(request.firstName());
        user.setLastName(request.lastName());
        user.setAadharNumber(request.aadharNumber());
        user.setPhno(request.phno());
        user.setBalance(BigDecimal.valueOf(0, 2));

        requireValidPhone(user.getPhno());
        long aadharNumber= user.getAadharNumber();
        String y=""+aadharNumber;
        if(y.length()!=12)
        {
            throw new MobileNumberException("Invalid AADHAR NUMBER");
        }
        else
        {
            try {
                long aa = Long.parseLong(y);
            }
            catch(NumberFormatException e) {
                throw new MobileNumberException("Invalid AADHAR NUMBER");
            }
        }
        if(userRepository.existsByPhno(user.getPhno()))
        {
            throw new UserExistException("mobile number already exist");
        }
        if(userRepository.existsByAadharNumber(user.getAadharNumber()))
        {
            throw new UserExistException("AADHAR NUMBER already exist");
        }

        user.setPinHash(passwordEncoder.encode(request.pin()));

        Long maxAcno = userRepository.findMaxAcno();
        user.setAcno(maxAcno == null ? 1000000000 : maxAcno + 1);
        return userRepository.save(user);
    }

    @Transactional
    public String withdrawByphno(long phno, BigDecimal amount)
    {
        Bank exis=userRepository.findByphno(phno);
        if(exis!=null)
        {
            checkSufficientFunds(exis,amount);
            exis.setBalance(exis.getBalance().subtract(amount));
            BankTransaction b=new BankTransaction();
            b.setPhno(phno);
            b.setAmount(amount);
            b.setAction("Debit");
            b.setBalance(exis.getBalance());
            b.setUserId(exis.getUserId());
            b.setTransactionId(nextTransactionId());
            b.setCreatedAt(clock.instant());
            bankTransactionRepository.save(b);
        }
        else
        {
            throw new UserNotFoundException("User not found");
        }
        notifyAfterCommit("Amount Withdraw successfully. Phone: "+mask(phno)+" Amount: "+ amount +", Current Balance: "+ exis.getBalance());
        userRepository.save(exis);
        return "Withdraw Successful Amount Inr : "+amount;
    }
    @Transactional
    public  String withdrawByacno(long acno, BigDecimal amount)
    {
        Bank exis=userRepository.findByacno(acno);
        if(exis!=null)
        {
            checkSufficientFunds(exis,amount);
            exis.setBalance(exis.getBalance().subtract(amount));
            BankTransaction b=new BankTransaction();
            b.setPhno(exis.getPhno());
            b.setAmount(amount);
            b.setAction("Debit");
            b.setBalance(exis.getBalance());
            b.setUserId(exis.getUserId());
            b.setTransactionId(nextTransactionId());
            b.setCreatedAt(clock.instant());
            bankTransactionRepository.save(b);        }
        else {
            throw new UserNotFoundException("User not found");
        }
        notifyAfterCommit("Amount Withdraw successfully. Acno: "+acno+" Amount: "+ amount +", Current Balance: "+ exis.getBalance());

        userRepository.save(exis);
        return "Withdraw Successful Amount Inr : "+amount;
    }
    @Transactional
    public String depositByphno(long phno, BigDecimal amount)
    {
        Bank exis=userRepository.findByphno(phno);
        if(exis!=null)
        {
            exis.setBalance(exis.getBalance().add(amount));
            BankTransaction b=new BankTransaction();
            b.setPhno(phno);
            b.setAmount(amount);
            b.setAction("Credit");
            b.setBalance(exis.getBalance());
            b.setUserId(exis.getUserId());
            b.setTransactionId(nextTransactionId());
            b.setCreatedAt(clock.instant());
            notifyAfterCommit("Amount deposited successfully. Phone: "+mask(phno)+" Amount: "+ amount +", Current Balance: "+ b.getBalance());
            bankTransactionRepository.save(b);        }
        else
        {
            throw new UserNotFoundException("User not found");
        }
        userRepository.save(exis);
        return "Deposit Successful Amount Inr : "+amount;
    }
    @Transactional
    public String depositByacno(long acno, BigDecimal amount)
    {
        Bank exis=userRepository.findByacno(acno);
        if(exis!=null)
        {
            exis.setBalance(exis.getBalance().add(amount));
            BankTransaction b=new BankTransaction();
            b.setPhno(exis.getPhno());
            b.setAmount(amount);
            b.setAction("Credit");
            b.setBalance(exis.getBalance());
            b.setUserId(exis.getUserId());
            b.setTransactionId(nextTransactionId());
            b.setCreatedAt(clock.instant());
            notifyAfterCommit("Amount deposited successfully. Acno: "+acno+" Amount: "+ amount +", Current Balance: "+ b.getBalance());
            bankTransactionRepository.save(b);        }
        else
        {
            throw new UserNotFoundException("User not found");
        }
        userRepository.save(exis);
        return "Deposit Successful Amount Inr : "+amount;
    }
    /**
     * Moves money between two accounts in ONE database transaction: either both balances change and both ledger
     * rows are written, or nothing happens at all. This is what makes the operation safe to retry after a
     * timeout - a caller that never got a response can simply send the identical request again with the same
     * idempotencyKey, and either finds the completed transfer (if it actually went through) or gets it executed
     * fresh (if it never did), with no way to end up moving the money twice.
     */
    @Transactional
    public String transfer(long payerPhno, long receiverPhno, BigDecimal amount, String idempotencyKey)
    {
        Transfer existing = transferRepository.findByPayerPhnoAndIdempotencyKey(payerPhno, idempotencyKey).orElse(null);
        if (existing != null)
        {
            // A retry must send back the SAME request, not just the same key - otherwise a reused key (a client
            // bug, or a copy-pasted key) would silently claim success for money that was never moved as asked.
            if (existing.getReceiverPhno() != receiverPhno || existing.getAmount().compareTo(amount) != 0)
            {
                throw new InvalidRequestException("This idempotency key was already used for a different transfer request.");
            }
            return "Transfer Successful Amount Inr : " + existing.getAmount();
        }
        if (payerPhno == receiverPhno)
        {
            throw new InvalidRequestException("Cannot transfer to the same account");
        }
        Bank payer = userRepository.findByphno(payerPhno);
        if (payer == null)
        {
            throw new UserNotFoundException("Payer not found");
        }
        Bank receiver = userRepository.findByphno(receiverPhno);
        if (receiver == null)
        {
            throw new UserNotFoundException("Receiver not found");
        }
        checkSufficientFunds(payer, amount);

        payer.setBalance(payer.getBalance().subtract(amount));
        receiver.setBalance(receiver.getBalance().add(amount));

        long debitId = nextTransactionId();
        long creditId = debitId + 1;
        Instant now = clock.instant();

        BankTransaction debit = new BankTransaction();
        debit.setTransactionId(debitId);
        debit.setUserId(payer.getUserId());
        debit.setPhno(payerPhno);
        debit.setAction("Debit");
        debit.setAmount(amount);
        debit.setBalance(payer.getBalance());
        debit.setCreatedAt(now);
        bankTransactionRepository.save(debit);

        BankTransaction credit = new BankTransaction();
        credit.setTransactionId(creditId);
        credit.setUserId(receiver.getUserId());
        credit.setPhno(receiverPhno);
        credit.setAction("Credit");
        credit.setAmount(amount);
        credit.setBalance(receiver.getBalance());
        credit.setCreatedAt(now);
        bankTransactionRepository.save(credit);

        userRepository.save(payer);
        userRepository.save(receiver);

        Transfer record = new Transfer();
        record.setIdempotencyKey(idempotencyKey);
        record.setPayerPhno(payerPhno);
        record.setReceiverPhno(receiverPhno);
        record.setAmount(amount);
        record.setDebitTransactionId(debitId);
        record.setCreditTransactionId(creditId);
        record.setCreatedAt(clock.instant());
        transferRepository.save(record);

        notifyAfterCommit("Transfer successful. From: " + mask(payerPhno) + " To: " + mask(receiverPhno)
                + " Amount: " + amount + ", Payer balance: " + payer.getBalance());

        return "Transfer Successful Amount Inr : " + amount;
    }

    @Transactional
    public Bank updatePhno(long phno,long newphno)
    {
        requireValidPhone(newphno);
        if(userRepository.existsByPhno(newphno))
        {
            throw new UserExistException("Phone number already exist");
        }
        Bank exis=userRepository.findByphno(phno);
        if(exis!=null)
        {
            exis.setPhno(newphno);
        }
        else
        {
            throw new UserNotFoundException("User not found");
        }
        notifyAfterCommit("Mobile number updated old phno: "+mask(phno)+" New phno: "+mask(newphno));
        Bank saved = userRepository.save(exis);
        // a token issued under the old number must not go on authenticating as this account (or, worse, as
        // whoever the old number belongs to next) - the caller has to log in again under the new number
        sessionService.invalidateAllFor(phno);
        return saved;
    }
    @Transactional
    public void deleteByPhno(long phno)
    {
        Bank exis=userRepository.findByphno(phno);
        if(exis!=null)
        userRepository.delete(exis);
        else
            throw new UserNotFoundException("User not found");
        // otherwise a still-valid token survives account deletion and can end up authenticating against
        // whoever registers with this phone number next
        sessionService.invalidateAllFor(phno);
    }
    public BankDto displayUserByPhno(long phno)
    {
        Bank b=userRepository.findByphno(phno);
        if(b==null)
            throw new UserNotFoundException("User not found");
        return new BankDto(b.getUserId(),b.getAcno(),(b.getLastName()+" "+b.getFirstName()).toUpperCase(),b.getAadharNumber(),b.getPhno(),b.getBalance());
    }
    // Checked here, inside the transaction, against the balance we are about to change. The controller's earlier
    // check can be stale by now; together with @Version this makes an overdraft impossible.
    private void checkSufficientFunds(Bank account, BigDecimal amount)
    {
        if(amount.compareTo(account.getBalance())>0)
        {
            throw new WithdrawException("Insufficient Funds");
        }
    }
    // A MAX() query instead of loading every transaction to find the last one. Two concurrent calls can still
    // compute the same id; the unique constraint on transactionId catches that and the caller gets a 409
    // (GlobalExceptionHandler) telling them to retry, the same as any other write conflict in this app.
    private long nextTransactionId()
    {
        Long highest = bankTransactionRepository.findMaxTransactionId();
        return highest == null ? 100000 : highest + 1;
    }
    // Shared by register() (a brand new number) and updatePhno() (a number swapped in later) - both must be a
    // real 10-digit Indian mobile number, not just anything unique. updatePhno() previously skipped this check
    // entirely, so an admin call (or the self-service /bank/update-phone endpoint) could silently set an
    // account's phone number to 0 or any other garbage value as long as it wasn't already taken.
    private static void requireValidPhone(long phno)
    {
        String x = "" + phno;
        if (x.length() != 10 || !x.matches("^[6-9].*"))
        {
            throw new MobileNumberException("Invalid mobile number");
        }
    }
    // Kafka is told only AFTER the database commit succeeds, so a rolled-back payment never produces a
    // "success" message, and a Kafka outage can never fail or undo a payment that already went through.
    private void notifyAfterCommit(String message)
    {
        if(TransactionSynchronizationManager.isSynchronizationActive())
        {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    sendNotification(message);
                }
            });
        }
        else
        {
            sendNotification(message);
        }
    }
    private void sendNotification(String message)
    {
        try {
            bankKafkaProducer.sendMessage(message);
        }
        catch(RuntimeException e) {
            log.error("Kafka notification failed: {}", e.getMessage());
        }
    }
    // keep phone numbers out of the Kafka topic and its logs
    private static String mask(long phno)
    {
        String s = String.valueOf(phno);
        return "XXXXXX" + s.substring(Math.max(0, s.length() - 4));
    }
    public PageResponse<BankTransaction> displayTransactionByPhno(long phno, int page, int size, Instant from, Instant to)
    {
        if (from != null && to != null && from.isAfter(to))
        {
            throw new InvalidRequestException("'from' must not be after 'to'.");
        }
        Bank b=userRepository.findByphno(phno);
        if(b==null)
            throw new UserNotFoundException("User not found");
        return PageResponse.of(bankTransactionRepository.findByPhno(phno, from, to, pageable(page, size, Sort.by("id").ascending())));
    }

    // Self-service only: scoped to the caller's own account identity (userId), not the phone number value used
    // to look them up. displayTransactionByPhno() above is for the trusted-caller /bank/transactions endpoint,
    // which is deliberately allowed to look up a phno's full history even for a deleted account - this one must
    // not, or a number reassigned to a new customer would show them (or leak to them) the previous owner's history.
    public PageResponse<BankTransaction> displayMyTransactions(long phno, int page, int size, Instant from, Instant to)
    {
        if (from != null && to != null && from.isAfter(to))
        {
            throw new InvalidRequestException("'from' must not be after 'to'.");
        }
        Bank b=userRepository.findByphno(phno);
        if(b==null)
            throw new UserNotFoundException("User not found");
        return PageResponse.of(bankTransactionRepository.findByUserId(b.getUserId(), from, to, pageable(page, size, Sort.by("id").ascending())));
    }

    // page/size come straight from a query parameter, so out-of-range values are a caller mistake, not a crash.
    private Pageable pageable(int page, int size, Sort sort)
    {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE)
        {
            throw new InvalidRequestException("page must be 0 or more, and size must be between 1 and " + MAX_PAGE_SIZE + ".");
        }
        return PageRequest.of(page, size, sort);
    }
}



