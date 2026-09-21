package com.example.bankapplication.controller;

import com.example.bankapplication.dto.BankDto;
import com.example.bankapplication.entity.Bank;
import com.example.bankapplication.entity.BankTransaction;
import com.example.bankapplication.exception.MobileNumberException;
import com.example.bankapplication.exception.UserExistException;
import com.example.bankapplication.exception.UserNotFoundException;
import com.example.bankapplication.service.BankService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(BankController.class)
class BankControllerTest {

    private static final long PHNO = 9876543210L;
    private static final long ACNO = 1000000000L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BankService bankService;

    private Bank bankWithBalance(double balance) {
        Bank b = new Bank();
        b.setUserId(1);
        b.setAcno(ACNO);
        b.setFirstName("Charan");
        b.setLastName("Kumar");
        b.setAadharNumber(123456789012L);
        b.setPhno(PHNO);
        b.setBalance(balance);
        return b;
    }

    // ---------- GET /bank/all ----------

    @Test
    void getAll_returnsListOfDtos() throws Exception {
        when(bankService.findAll()).thenReturn(List.of(
                new BankDto(1, ACNO, "KUMAR CHARAN", 123456789012L, PHNO, 500)));

        mockMvc.perform(get("/bank/all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("KUMAR CHARAN"))
                .andExpect(jsonPath("$[0].acno").value(ACNO))
                .andExpect(jsonPath("$[0].balance").value(500.0));
    }

    // ---------- GET by phno / acno ----------

    @Test
    void getByPhno_returnsUser() throws Exception {
        when(bankService.findByphno(PHNO)).thenReturn(bankWithBalance(500));

        mockMvc.perform(get("/bank/getByphno/{phno}", PHNO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstName").value("Charan"))
                .andExpect(jsonPath("$.phno").value(PHNO));
    }

    @Test
    void getByAcno_returnsUser() throws Exception {
        when(bankService.findByacno(ACNO)).thenReturn(bankWithBalance(500));

        mockMvc.perform(get("/bank/getByacno/{acno}", ACNO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acno").value(ACNO));
    }

    @Test
    void getByPhno_unknownUser_returns400WithMessage() throws Exception {
        when(bankService.findByphno(PHNO)).thenReturn(null);

        mockMvc.perform(get("/bank/getByphno/{phno}", PHNO))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("User not found"));
    }

    @Test
    void getByAcno_unknownUser_returns400WithMessage() throws Exception {
        when(bankService.findByacno(ACNO)).thenReturn(null);

        mockMvc.perform(get("/bank/getByacno/{acno}", ACNO))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("User not found"));
    }

    @Test
    void getByPhno_nonNumericPath_returns400() throws Exception {
        mockMvc.perform(get("/bank/getByphno/abc"))
                .andExpect(status().isBadRequest());
    }

    // ---------- POST /bank/save ----------

    private static final String VALID_USER_JSON = """
            {"firstName":"Charan","lastName":"Kumar","aadharNumber":123456789012,"phno":9876543210,"balance":0}
            """;

    @Test
    void save_validUser_returnsSavedUser() throws Exception {
        when(bankService.save(any(Bank.class))).thenReturn(bankWithBalance(0));

        mockMvc.perform(post("/bank/save").contentType(MediaType.APPLICATION_JSON).content(VALID_USER_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acno").value(ACNO))
                .andExpect(jsonPath("$.firstName").value("Charan"));
    }

    @Test
    void save_invalidMobile_returns400WithMessage() throws Exception {
        when(bankService.save(any(Bank.class))).thenThrow(new MobileNumberException("Invalid mobile number"));

        mockMvc.perform(post("/bank/save").contentType(MediaType.APPLICATION_JSON).content(VALID_USER_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Invalid mobile number"));
    }

    @Test
    void save_duplicateUser_returns400WithMessage() throws Exception {
        when(bankService.save(any(Bank.class))).thenThrow(new UserExistException("mobile number already exist"));

        mockMvc.perform(post("/bank/save").contentType(MediaType.APPLICATION_JSON).content(VALID_USER_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("mobile number already exist"));
    }

    // Two simultaneous requests can both pass the service's checks; a DB unique constraint then stops one of them.
    // Retrying re-runs the normal checks against the committed data, so the client gets the accurate answer.
    private static final String RETRY_MESSAGE = "Another request changed the same data at the same time. Please retry.";

    @Test
    void save_databaseUniqueConstraintViolation_returns409Retry() throws Exception {
        when(bankService.save(any(Bank.class))).thenThrow(new DataIntegrityViolationException("Duplicate entry"));

        mockMvc.perform(post("/bank/save").contentType(MediaType.APPLICATION_JSON).content(VALID_USER_JSON))
                .andExpect(status().isConflict())
                .andExpect(content().string(RETRY_MESSAGE));
    }

    @Test
    void deposit_optimisticLockConflict_returns409Retry() throws Exception {
        when(bankService.depositByphno(anyLong(), anyDouble()))
                .thenThrow(new ObjectOptimisticLockingFailureException(Bank.class, 1));

        mockMvc.perform(put("/bank/depositByphno").param("phno", "" + PHNO).param("balance", "10"))
                .andExpect(status().isConflict())
                .andExpect(content().string(RETRY_MESSAGE));
    }

    @Test
    void withdraw_optimisticLockConflict_returns409Retry() throws Exception {
        when(bankService.findByphno(PHNO)).thenReturn(bankWithBalance(1000));
        when(bankService.withdrawByphno(anyLong(), anyDouble()))
                .thenThrow(new ObjectOptimisticLockingFailureException(Bank.class, 1));

        mockMvc.perform(put("/bank/withdrawByphno").param("phno", "" + PHNO).param("balance", "10"))
                .andExpect(status().isConflict())
                .andExpect(content().string(RETRY_MESSAGE));
    }

    @Test
    void save_malformedJson_returns400() throws Exception {
        mockMvc.perform(post("/bank/save").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest());
    }

    // ---------- PUT /bank/withdrawByphno ----------

    @Test
    void withdrawByphno_success() throws Exception {
        when(bankService.findByphno(PHNO)).thenReturn(bankWithBalance(1000));
        when(bankService.withdrawByphno(PHNO, 400)).thenReturn("Withdraw Successful Amount Inr : 400.0");

        mockMvc.perform(put("/bank/withdrawByphno").param("phno", "" + PHNO).param("balance", "400"))
                .andExpect(status().isOk())
                .andExpect(content().string("Withdraw Successful Amount Inr : 400.0"));
    }

    @Test
    void withdrawByphno_exactBalance_isAllowed() throws Exception {
        when(bankService.findByphno(PHNO)).thenReturn(bankWithBalance(1000));
        when(bankService.withdrawByphno(PHNO, 1000)).thenReturn("Withdraw Successful Amount Inr : 1000.0");

        mockMvc.perform(put("/bank/withdrawByphno").param("phno", "" + PHNO).param("balance", "1000"))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-50"})
    void withdrawByphno_zeroOrNegativeAmount_returns400(String amount) throws Exception {
        mockMvc.perform(put("/bank/withdrawByphno").param("phno", "" + PHNO).param("balance", amount))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Amount too low"));

        verify(bankService, never()).withdrawByphno(anyLong(), anyDouble());
    }

    @Test
    void withdrawByphno_insufficientFunds_returns400() throws Exception {
        when(bankService.findByphno(PHNO)).thenReturn(bankWithBalance(100));

        mockMvc.perform(put("/bank/withdrawByphno").param("phno", "" + PHNO).param("balance", "100.01"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Insufficient Funds"));

        verify(bankService, never()).withdrawByphno(anyLong(), anyDouble());
    }

    @Test
    void withdrawByphno_unknownUser_returns400() throws Exception {
        when(bankService.findByphno(PHNO)).thenReturn(null);

        mockMvc.perform(put("/bank/withdrawByphno").param("phno", "" + PHNO).param("balance", "10"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("User not found"));
    }

    @Test
    void withdrawByphno_missingParam_returns400() throws Exception {
        mockMvc.perform(put("/bank/withdrawByphno").param("phno", "" + PHNO))
                .andExpect(status().isBadRequest());
    }

    // ---------- PUT /bank/withdrawByacno ----------

    @Test
    void withdrawByacno_passesPositiveAmountToService() throws Exception {
        when(bankService.findByacno(ACNO)).thenReturn(bankWithBalance(1000));
        when(bankService.withdrawByacno(anyLong(), anyDouble())).thenReturn("Withdraw Successful Amount Inr : 300.0");

        mockMvc.perform(put("/bank/withdrawByacno").param("acno", "" + ACNO).param("balance", "300"))
                .andExpect(status().isOk());

        // The service SUBTRACTS the amount it receives, so it must receive +300.
        verify(bankService).withdrawByacno(ACNO, 300.0);
    }

    @Test
    void withdrawByacno_insufficientFunds_returns400() throws Exception {
        when(bankService.findByacno(ACNO)).thenReturn(bankWithBalance(100));

        mockMvc.perform(put("/bank/withdrawByacno").param("acno", "" + ACNO).param("balance", "500"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Insufficient Funds"));
    }

    @Test
    void withdrawByacno_zeroAmount_returns400() throws Exception {
        mockMvc.perform(put("/bank/withdrawByacno").param("acno", "" + ACNO).param("balance", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Amount too low"));
    }

    @Test
    void withdrawByacno_unknownUser_returns400() throws Exception {
        when(bankService.findByacno(ACNO)).thenReturn(null);

        mockMvc.perform(put("/bank/withdrawByacno").param("acno", "" + ACNO).param("balance", "10"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("User not found"));
    }

    // ---------- PUT /bank/depositByphno ----------

    @Test
    void depositByphno_success() throws Exception {
        when(bankService.depositByphno(PHNO, 250)).thenReturn("Deposit Successful Amount Inr : 250.0");

        mockMvc.perform(put("/bank/depositByphno").param("phno", "" + PHNO).param("balance", "250"))
                .andExpect(status().isOk())
                .andExpect(content().string("Deposit Successful Amount Inr : 250.0"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1"})
    void depositByphno_zeroOrNegativeAmount_returns400(String amount) throws Exception {
        mockMvc.perform(put("/bank/depositByphno").param("phno", "" + PHNO).param("balance", amount))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Amount too low"));

        verify(bankService, never()).depositByphno(anyLong(), anyDouble());
    }

    @Test
    void depositByphno_unknownUser_returns400() throws Exception {
        when(bankService.depositByphno(anyLong(), anyDouble())).thenThrow(new UserNotFoundException("User not found"));

        mockMvc.perform(put("/bank/depositByphno").param("phno", "" + PHNO).param("balance", "10"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("User not found"));
    }

    @Test
    void depositByphno_nonNumericAmount_returns400() throws Exception {
        mockMvc.perform(put("/bank/depositByphno").param("phno", "" + PHNO).param("balance", "abc"))
                .andExpect(status().isBadRequest());
    }

    // ---------- PUT /bank/depositByacno ----------

    @Test
    void depositByacno_success() throws Exception {
        when(bankService.depositByacno(ACNO, 75)).thenReturn("Deposit Successful Amount Inr : 75.0");

        mockMvc.perform(put("/bank/depositByacno").param("acno", "" + ACNO).param("balance", "75"))
                .andExpect(status().isOk())
                .andExpect(content().string("Deposit Successful Amount Inr : 75.0"));
    }

    @Test
    void depositByacno_negativeAmount_returns400() throws Exception {
        mockMvc.perform(put("/bank/depositByacno").param("acno", "" + ACNO).param("balance", "-5"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Amount too low"));

        verify(bankService, never()).depositByacno(anyLong(), anyDouble());
    }

    // ---------- PUT /bank/updatephno ----------

    @Test
    void updatePhno_success() throws Exception {
        Bank updated = bankWithBalance(0);
        updated.setPhno(9123456789L);
        when(bankService.updatePhno(PHNO, 9123456789L)).thenReturn(updated);

        mockMvc.perform(put("/bank/updatephno").param("phno", "" + PHNO).param("newphno", "9123456789"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phno").value(9123456789L));
    }

    @Test
    void updatePhno_numberTaken_returns400() throws Exception {
        when(bankService.updatePhno(anyLong(), anyLong())).thenThrow(new UserExistException("Phone number already exist"));

        mockMvc.perform(put("/bank/updatephno").param("phno", "" + PHNO).param("newphno", "9123456789"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Phone number already exist"));
    }

    // ---------- DELETE /bank/deleteuser ----------

    @Test
    void deleteUser_success() throws Exception {
        mockMvc.perform(delete("/bank/deleteuser").param("phno", "" + PHNO))
                .andExpect(status().isOk());

        verify(bankService).deleteByPhno(PHNO);
    }

    @Test
    void deleteUser_unknownUser_returns400() throws Exception {
        doThrow(new UserNotFoundException("User not found")).when(bankService).deleteByPhno(PHNO);

        mockMvc.perform(delete("/bank/deleteuser").param("phno", "" + PHNO))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("User not found"));
    }

    // ---------- GET /bank/displayuser ----------

    @Test
    void displayUser_success() throws Exception {
        when(bankService.displayUserByPhno(PHNO))
                .thenReturn(new BankDto(1, ACNO, "KUMAR CHARAN", 123456789012L, PHNO, 500));

        mockMvc.perform(get("/bank/displayuser").param("phno", "" + PHNO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("KUMAR CHARAN"));
    }

    @Test
    void displayUser_unknownUser_returns400() throws Exception {
        when(bankService.displayUserByPhno(PHNO)).thenThrow(new UserNotFoundException("User not found"));

        mockMvc.perform(get("/bank/displayuser").param("phno", "" + PHNO))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("User not found"));
    }

    // ---------- GET /bank/transactions ----------

    @Test
    void transactions_returnsList() throws Exception {
        BankTransaction t = new BankTransaction();
        t.setTransactionId(100000);
        t.setAction("Credit");
        t.setAmount(250);
        t.setBalance(750);
        when(bankService.displayTransactionByPhno(PHNO)).thenReturn(List.of(t));

        mockMvc.perform(get("/bank/transactions").param("phno", "" + PHNO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].action").value("Credit"))
                .andExpect(jsonPath("$[0].transactionId").value(100000))
                .andExpect(jsonPath("$[0].amount").value(250.0));
    }
}
