package com.example.bankapplication.service;

import com.example.bankapplication.dto.BankDto;
import com.example.bankapplication.dto.LoginResponse;
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
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.ArrayList;
import java.util.List;
@Service
public class BankService {
    static final int MAX_FAILED_LOGIN_ATTEMPTS = 5;
    static final Duration LOCKOUT_DURATION = Duration.ofMinutes(15);

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
    public List<BankDto> findAll()
    {

        List<Bank> l=userRepository.findAll();
        List<BankDto> dto=new ArrayList<>();
        for(Bank b:l)
        {
            dto.add(new BankDto(b.getUserId(),b.getAcno(),(b.getLastName()+" "+b.getFirstName()).toUpperCase(),b.getAadharNumber(),b.getPhno(),b.getBalance()));
        }
        return dto;
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

        long phno=user.getPhno();
        String x=""+phno;
        if(x.length()!=10||!x.matches("^[6-9].*"))
        {
            throw new MobileNumberException("Invalid mobile number");
        }
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
        List<Bank> l=userRepository.findAll();
        for(Bank u:l)
        {
            if(u.getPhno()==user.getPhno())
            {
                throw new UserExistException("mobile number already exist");
            }
            if(u.getAadharNumber()==user.getAadharNumber())
            {
                throw new UserExistException("AADHAR NUMBER already exist");
            }
        }

        user.setPinHash(passwordEncoder.encode(request.pin()));

        if(l.size()==0) {
            user.setAcno(1000000000);
            return userRepository.save(user);
        }
        long acno=l.get(l.size()-1).getAcno();
        user.setAcno(acno+1);
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
            List<BankTransaction>l=bankTransactionRepository.findAll();
            if(l.size()==0) {
                b.setTransactionId(100000);
            }
            else {
                b.setTransactionId(l.get(l.size()-1).getTransactionId()+1);
            }
            bankTransactionRepository.save(b);
        }
        else
        {
            throw new UserNotFoundException("User not found");
        }
        notifyAfterCommit("Amount Withdraw successfully. Phone: "+phno+" Amount: "+ amount +", Current Balance: "+ exis.getBalance());
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
            b.setPhno(userRepository.findByacno(acno).getPhno());
            b.setAmount(amount);
            b.setAction("Debit");
            b.setBalance(exis.getBalance());
            b.setUserId(exis.getUserId());
            List<BankTransaction>l=bankTransactionRepository.findAll();
            if(l.size()==0) {
                b.setTransactionId(100000);
            }
            else {
                b.setTransactionId(l.get(l.size()-1).getTransactionId()+1);
            }
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
            List<BankTransaction>l=bankTransactionRepository.findAll();
            if(l.size()==0) {
                b.setTransactionId(100000);
            }
            else {
                b.setTransactionId(l.get(l.size()-1).getTransactionId()+1);
            }
            notifyAfterCommit("Amount deposited successfully. Phone: "+phno+" Amount: "+ amount +", Current Balance: "+ b.getBalance());
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
            b.setPhno(userRepository.findByacno(acno).getPhno());
            b.setAmount(amount);
            b.setAction("Credit");
            b.setBalance(exis.getBalance());
            b.setUserId(exis.getUserId());
            List<BankTransaction>l=bankTransactionRepository.findAll();
            if(l.size()==0) {
                b.setTransactionId(100000);
            }
            else {
                b.setTransactionId(l.get(l.size()-1).getTransactionId()+1);
            }
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
        Transfer existing = transferRepository.findByIdempotencyKey(idempotencyKey).orElse(null);
        if (existing != null)
        {
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

        List<BankTransaction> existingTxns = bankTransactionRepository.findAll();
        long nextId = existingTxns.isEmpty() ? 100000 : existingTxns.get(existingTxns.size() - 1).getTransactionId() + 1;
        long debitId = nextId;
        long creditId = nextId + 1;

        BankTransaction debit = new BankTransaction();
        debit.setTransactionId(debitId);
        debit.setUserId(payer.getUserId());
        debit.setPhno(payerPhno);
        debit.setAction("Debit");
        debit.setAmount(amount);
        debit.setBalance(payer.getBalance());
        bankTransactionRepository.save(debit);

        BankTransaction credit = new BankTransaction();
        credit.setTransactionId(creditId);
        credit.setUserId(receiver.getUserId());
        credit.setPhno(receiverPhno);
        credit.setAction("Credit");
        credit.setAmount(amount);
        credit.setBalance(receiver.getBalance());
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

        notifyAfterCommit("Transfer successful. From: " + payerPhno + " To: " + receiverPhno
                + " Amount: " + amount + ", Payer balance: " + payer.getBalance());

        return "Transfer Successful Amount Inr : " + amount;
    }

    @Transactional
    public Bank updatePhno(long phno,long newphno)
    {
        List<Bank> l=userRepository.findAll();
        for(Bank u:l)
        {
            if(u.getPhno()==newphno)
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
        notifyAfterCommit("Mobile number updated old phno: "+phno+" New phno: "+newphno);
        return userRepository.save(exis);
    }
    @Transactional
    public void deleteByPhno(long phno)
    {
        Bank exis=userRepository.findByphno(phno);
        if(exis!=null)
        userRepository.delete(userRepository.findByphno(phno));
        else
            throw new UserNotFoundException("User not found");
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
            System.out.println("Kafka notification failed: "+e.getMessage());
        }
    }
    public List<BankTransaction> displayTransactionByPhno(long phno)
    {
        Bank b=userRepository.findByphno(phno);
        if(b==null)
            throw new UserNotFoundException("User not found");
        return b.getTransactions();
    }
}
