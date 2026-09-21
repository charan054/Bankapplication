package com.example.bankapplication.controller;

import com.example.bankapplication.dto.BankDto;
import com.example.bankapplication.entity.Bank;
import com.example.bankapplication.entity.BankTransaction;
import com.example.bankapplication.exception.DepositException;
import com.example.bankapplication.exception.UserNotFoundException;
import com.example.bankapplication.exception.WithdrawException;
import com.example.bankapplication.service.BankService;
import org.slf4j.ILoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/bank")
public class BankController {
    @Autowired
    private BankService bankService;
    @GetMapping("/all")
    public List<BankDto> getAllUser(){
        return bankService.findAll();
    }
    @GetMapping("/getByphno/{phno}")
    public Bank getByPhno(@PathVariable("phno") long phno){
        Bank exis=bankService.findByphno(phno);
        if(exis==null)
        {
            throw new UserNotFoundException("User not found");
        }
        return exis;
    }
    @GetMapping("/getByacno/{acno}")
    public Bank getByAcno(@PathVariable  long acno){
        Bank exis=bankService.findByacno(acno);
        if(exis==null)
        {
            throw new UserNotFoundException("User not found");
        }
        return exis;
    }
    @PostMapping("/save")
    public Bank save(@RequestBody Bank user){
        return bankService.save(user);
    }
    @PutMapping("/withdrawByphno")
    public String withdrawByphno(@RequestParam long phno,@RequestParam double balance){
        if(balance<=0)
        {
            throw new DepositException("Amount too low");
        }
        Bank exis= bankService.findByphno(phno);
        if(exis==null) {
            throw new UserNotFoundException("User not found");
        }
        else if (balance > exis.getBalance()) {
            throw new WithdrawException("Insufficient Funds");
        }
        return bankService.withdrawByphno(phno, balance);
    }
    @PutMapping("/withdrawByacno")
    public String withdrawByacno(@RequestParam long acno,@RequestParam double balance){
        if(balance<=0)
        {
            throw new DepositException("Amount too low");
        }
        Bank exis= bankService.findByacno(acno);
        if(exis==null) {
            throw new UserNotFoundException("User not found");
        }
        else if (balance > exis.getBalance()) {
                throw new WithdrawException("Insufficient Funds");
            }
            return bankService.withdrawByacno(acno, balance);
    }
    @PutMapping("/depositByphno")
    public String depositByphno(@RequestParam long phno,@RequestParam double balance){
       if(balance<=0)
        {
            throw new DepositException("Amount too low");
        }
        return bankService.depositByphno(phno,balance);
    }
    @PutMapping("/depositByacno")
    public String depositByacno(@RequestParam long acno,@RequestParam double balance){
        if(balance<=0)
        {
            throw new DepositException("Amount too low");
        }
        return bankService.depositByacno(acno,balance);
    }
    @PutMapping("/updatephno")
    public Bank updatephno(@RequestParam long phno,@RequestParam long newphno){
     return  bankService.updatePhno(phno, newphno);
    }
    @DeleteMapping("/deleteuser")
    public void deleteUser(@RequestParam long phno){
        bankService.deleteByPhno(phno);
    }
    @GetMapping("/displayuser")
    public BankDto displayUser(@RequestParam long phno){
        return bankService.displayUserByPhno(phno);
    }
    @GetMapping("/transactions")
    public List<BankTransaction> displayTransactionByPhno(@RequestParam long phno){
        return bankService.displayTransactionByPhno(phno);
    }
}
