package com.example.bankapplication.service;

import com.example.bankapplication.dto.BankDto;
import com.example.bankapplication.entity.Bank;
import com.example.bankapplication.entity.BankTransaction;
import com.example.bankapplication.exception.MobileNumberException;
import com.example.bankapplication.exception.UserExistException;
import com.example.bankapplication.exception.UserNotFoundException;
import com.example.bankapplication.exception.WithdrawException;
import com.example.bankapplication.kafka.BankKafkaProducer;
import com.example.bankapplication.repository.BankRepository;
import com.example.bankapplication.repository.BankTransactionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
@Service
public class BankService {
    @Autowired
    private BankRepository userRepository;
    @Autowired
    BankTransactionRepository bankTransactionRepository;
    @Autowired
    BankKafkaProducer bankKafkaProducer;
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
    @Transactional
    public Bank save(Bank user)
    {
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

        if(l.size()==0) {
            user.setAcno(1000000000);
            return userRepository.save(user);
        }
        long acno=l.get(l.size()-1).getAcno();
        user.setAcno(acno+1);
        return userRepository.save(user);
    }
    @Transactional
    public String withdrawByphno(long phno, double amount)
    {
        Bank exis=userRepository.findByphno(phno);
        if(exis!=null)
        {
            checkSufficientFunds(exis,amount);
            exis.setBalance(exis.getBalance()-amount);
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
    public  String withdrawByacno(long acno, double amount)
    {
        Bank exis=userRepository.findByacno(acno);
        if(exis!=null)
        {
            checkSufficientFunds(exis,amount);
            exis.setBalance(exis.getBalance()-amount);
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
    public String depositByphno(long phno, double amount)
    {
        Bank exis=userRepository.findByphno(phno);
        if(exis!=null)
        {
            exis.setBalance(exis.getBalance()+amount);
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
    public String depositByacno(long acno, double amount)
    {
        Bank exis=userRepository.findByacno(acno);
        if(exis!=null)
        {
            exis.setBalance(exis.getBalance()+amount);
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
    private void checkSufficientFunds(Bank account, double amount)
    {
        if(amount>account.getBalance())
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
