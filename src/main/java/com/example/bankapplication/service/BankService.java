package com.example.bankapplication.service;

import com.example.bankapplication.dto.BankDto;
import com.example.bankapplication.entity.Bank;
import com.example.bankapplication.entity.BankTransaction;
import com.example.bankapplication.exception.MobileNumberException;
import com.example.bankapplication.exception.UserExistException;
import com.example.bankapplication.exception.UserNotFoundException;
import com.example.bankapplication.kafka.BankKafkaProducer;
import com.example.bankapplication.repository.BankRepository;
import com.example.bankapplication.repository.BankTransactionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

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
    public String withdrawByphno(long phno, double amount)
    {
        Bank exis=userRepository.findByphno(phno);
        if(exis!=null)
        {
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
        userRepository.save(exis);
        return "Withdraw Successful Amount Inr : "+amount;
    }
    public  String withdrawByacno(long acno, double amount)
    {
        Bank exis=userRepository.findByacno(acno);
        if(exis!=null)
        {
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
        else
        {
            throw new UserNotFoundException("User not found");
        }
        userRepository.save(exis);
        return "Withdraw Successful Amount Inr : "+amount;
    }
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
            //bankKafkaProducer.sendMessage("Amount deposited successfully. Phone: "+phno+" Amount: "+ amount +", Current Balance: "+ b.getBalance());
            bankTransactionRepository.save(b);        }
        else
        {
            throw new UserNotFoundException("User not found");
        }
        userRepository.save(exis);
        return "Deposit Successful Amount Inr : "+amount;
    }
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
            bankTransactionRepository.save(b);        }
        else
        {
            throw new UserNotFoundException("User not found");
        }
        userRepository.save(exis);
        return "Deposit Successful Amount Inr : "+amount;
    }
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
        return userRepository.save(exis);
    }
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
    public List<BankTransaction> displayTransactionByPhno(long phno)
    {
        return userRepository.findByphno(phno).getTransactions();
    }
}
