package com.example.bankapplication.controller;

import com.example.bankapplication.configuration.WebConfig;
import com.example.bankapplication.dto.BankDto;
import com.example.bankapplication.entity.Bank;
import com.example.bankapplication.entity.BankTransaction;
import com.example.bankapplication.exception.MobileNumberException;
import com.example.bankapplication.exception.UnauthorizedException;
import com.example.bankapplication.exception.UserExistException;
import com.example.bankapplication.exception.UserNotFoundException;
import com.example.bankapplication.service.BankService;
import com.example.bankapplication.service.SessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// "test" profile: gives spring.datasource.password / bank.admin.api-key / bank.service.api-key values, so these
// tests don't need those set on the machine (see src/test/resources/application-test.properties).
@WebMvcTest(BankController.class)
@Import(WebConfig.class)
@ActiveProfiles("test")
class BankControllerTest {

    private static final long PHNO = 9876543210L;
    private static final long ACNO = 1000000000L;
    private static final String TOKEN = "good-token";
    private static final String ADMIN_KEY = "test-admin-key";       // matches application-test.properties
    private static final String SERVICE_KEY = "test-service-key";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BankService bankService;
    @MockitoBean
    private SessionService sessionService;

    @BeforeEach
    void loginTheCaller() {
        when(sessionService.authenticate(TOKEN)).thenReturn(PHNO);
        when(sessionService.authenticate(null)).thenThrow(new UnauthorizedException("Login required"));
        when(sessionService.authenticate("bad-token")).thenThrow(new UnauthorizedException("Invalid session. Please login again."));
    }

    private MockHttpServletRequestBuilder asCaller(MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + TOKEN);
    }

    private MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder request) {
        return request.header("X-Admin-Key", ADMIN_KEY);
    }

    // Plain conversion: for entity fields only read back via compareTo() or JSON, where scale never matters.
    private static BigDecimal money(double v) {
        return BigDecimal.valueOf(v);
    }

    // Scale-2: what BankController.requirePositiveAmount actually produces, so a mock stub/verify given this
    // exact value matches the argument the controller really passes (BigDecimal.equals() is scale-sensitive:
    // "400" and "400.00" are numerically equal but NOT .equals(), which is what Mockito's default matching uses).
    private static BigDecimal normalized(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }

    private Bank bankWithBalance(double balance) {
        Bank b = new Bank();
        b.setUserId(1);
        b.setAcno(ACNO);
        b.setFirstName("Charan");
        b.setLastName("Kumar");
        b.setAadharNumber(123456789012L);
        b.setPhno(PHNO);
        b.setBalance(money(balance));
        return b;
    }

    // ============ authentication tiers ============

    private static List<MockHttpServletRequestBuilder> selfServiceEndpoints() {
        return List.of(
                post("/bank/logout"),
                get("/bank/me"),
                put("/bank/deposit").contentType(MediaType.APPLICATION_JSON).content("{\"amount\":10}"),
                put("/bank/withdraw").contentType(MediaType.APPLICATION_JSON).content("{\"amount\":10}"),
                put("/bank/update-phone").contentType(MediaType.APPLICATION_JSON).content("{\"newPhno\":9123456789}"),
                get("/bank/my-transactions"),
                delete("/bank/me"));
    }

    private static List<MockHttpServletRequestBuilder> trustedEndpoints() {
        return List.of(
                get("/bank/all"),
                get("/bank/getByphno/{phno}", PHNO),
                get("/bank/getByacno/{acno}", ACNO),
                put("/bank/withdrawByphno").param("phno", "" + PHNO).param("balance", "10"),
                put("/bank/withdrawByacno").param("acno", "" + ACNO).param("balance", "10"),
                put("/bank/depositByphno").param("phno", "" + PHNO).param("balance", "10"),
                put("/bank/depositByacno").param("acno", "" + ACNO).param("balance", "10"),
                put("/bank/updatephno").param("phno", "" + PHNO).param("newphno", "9123456789"),
                delete("/bank/deleteuser").param("phno", "" + PHNO),
                get("/bank/displayuser").param("phno", "" + PHNO),
                get("/bank/transactions").param("phno", "" + PHNO),
                put("/bank/admin/set-pin").contentType(MediaType.APPLICATION_JSON).content("{\"phno\":9876543210,\"newPin\":\"5678\"}"));
    }

    @Test
    void everySelfServiceEndpoint_withoutAToken_returns401() throws Exception {
        for (MockHttpServletRequestBuilder endpoint : selfServiceEndpoints()) {
            mockMvc.perform(endpoint)
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().string("Login required"));
        }
        verifyNoInteractions(bankService);
    }

    @Test
    void everySelfServiceEndpoint_withABadToken_returns401() throws Exception {
        for (MockHttpServletRequestBuilder endpoint : selfServiceEndpoints()) {
            mockMvc.perform(endpoint.header("Authorization", "Bearer bad-token"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().string("Invalid session. Please login again."));
        }
        verifyNoInteractions(bankService);
    }

    @Test
    void everyTrustedEndpoint_withNoKeyAtAll_returns401() throws Exception {
        for (MockHttpServletRequestBuilder endpoint : trustedEndpoints()) {
            mockMvc.perform(endpoint).andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(bankService);
    }

    @Test
    void everyTrustedEndpoint_withAWrongAdminKey_returns401() throws Exception {
        for (MockHttpServletRequestBuilder endpoint : trustedEndpoints()) {
            mockMvc.perform(endpoint.header("X-Admin-Key", "not-the-real-key")).andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(bankService);
    }

    @Test
    void everyTrustedEndpoint_acceptsTheServiceKeyJustAsWellAsTheAdminKey() throws Exception {
        // getByphno needs a non-null result to reach 200; the rest just need to get past the auth check (mocks
        // return defaults), so this only asserts "not 401", proving the service key is an accepted credential.
        for (MockHttpServletRequestBuilder endpoint : trustedEndpoints()) {
            mockMvc.perform(endpoint.header("X-Service-Key", SERVICE_KEY))
                    .andExpect(result -> org.junit.jupiter.api.Assertions.assertNotEquals(401, result.getResponse().getStatus()));
        }
    }

    @Test
    void aCustomerToken_doesNotUnlockTheTrustedEndpoints() throws Exception {
        // Logging in as a customer must never be enough to view/act on every account.
        mockMvc.perform(asCaller(get("/bank/all"))).andExpect(status().isUnauthorized());
    }

    @Test
    void anAdminKey_doesNotUnlockTheSelfServiceEndpoints() throws Exception {
        // The admin key proves "trusted caller acting on a named account", not "acting as a specific customer" -
        // self-service endpoints have no phno to act on without a customer token.
        mockMvc.perform(asAdmin(get("/bank/me"))).andExpect(status().isUnauthorized());
    }

    @Test
    void aHeaderThatIsNotABearerToken_isTreatedAsNoToken() throws Exception {
        mockMvc.perform(get("/bank/me").header("Authorization", "Basic dXNlcjpwYXNz")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/bank/me").header("Authorization", "good-token")).andExpect(status().isUnauthorized());
    }

    // ============ POST /bank/login ============

    @Test
    void login_correctCredentials_returnsToken() throws Exception {
        when(bankService.login(PHNO, "1234")).thenReturn(
                new com.example.bankapplication.dto.LoginResponse("abc123", java.time.Instant.parse("2026-09-23T10:30:00Z"), PHNO, "KUMAR CHARAN"));

        mockMvc.perform(post("/bank/login").contentType(MediaType.APPLICATION_JSON).content("{\"phno\":9876543210,\"pin\":\"1234\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("abc123"))
                .andExpect(jsonPath("$.name").value("KUMAR CHARAN"));

        verify(sessionService, never()).authenticate(any());
    }

    @Test
    void login_missingPin_returns400() throws Exception {
        mockMvc.perform(post("/bank/login").contentType(MediaType.APPLICATION_JSON).content("{\"phno\":9876543210}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("PIN is required"));

        verifyNoInteractions(bankService);
    }

    @Test
    void login_wrongCredentials_returns401() throws Exception {
        when(bankService.login(PHNO, "0000")).thenThrow(new com.example.bankapplication.exception.InvalidCredentialsException("Invalid phone number or PIN"));

        mockMvc.perform(post("/bank/login").contentType(MediaType.APPLICATION_JSON).content("{\"phno\":9876543210,\"pin\":\"0000\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string("Invalid phone number or PIN"));
    }

    @Test
    void login_lockedAccount_returns423() throws Exception {
        when(bankService.login(PHNO, "0000")).thenThrow(new com.example.bankapplication.exception.AccountLockedException("locked"));

        mockMvc.perform(post("/bank/login").contentType(MediaType.APPLICATION_JSON).content("{\"phno\":9876543210,\"pin\":\"0000\"}"))
                .andExpect(status().isLocked());
    }

    // ============ POST /bank/logout ============

    @Test
    void logout_returns204_andEndsThatTokensSession() throws Exception {
        mockMvc.perform(asCaller(post("/bank/logout")))
                .andExpect(status().isNoContent());

        verify(bankService).logout(TOKEN);
    }

    // ============ GET /bank/me, PUT /bank/deposit, /bank/withdraw, /bank/update-phone ============

    @Test
    void me_isTheCallersOwnProfile() throws Exception {
        when(bankService.displayUserByPhno(PHNO)).thenReturn(new BankDto(1, ACNO, "KUMAR CHARAN", 123456789012L, PHNO, money(500)));

        mockMvc.perform(asCaller(get("/bank/me")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("KUMAR CHARAN"));
    }

    @Test
    void deposit_usesThePhoneFromTheToken_ignoringAnyPhnoInTheRequest() throws Exception {
        when(bankService.depositByphno(PHNO, normalized(50))).thenReturn("Deposit Successful Amount Inr : 50.00");

        mockMvc.perform(asCaller(put("/bank/deposit")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phno\":9000000001,\"amount\":50}"))
                .andExpect(status().isOk());

        verify(bankService).depositByphno(PHNO, normalized(50));
        verify(bankService, never()).depositByphno(9000000001L, normalized(50));
    }

    @Test
    void deposit_zeroAmount_returns400() throws Exception {
        mockMvc.perform(asCaller(put("/bank/deposit")).contentType(MediaType.APPLICATION_JSON).content("{\"amount\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Amount too low"));

        verify(bankService, never()).depositByphno(anyLong(), any());
    }

    @Test
    void withdraw_ownAccount_insufficientFunds_returns400() throws Exception {
        when(bankService.findByphno(PHNO)).thenReturn(bankWithBalance(10));

        mockMvc.perform(asCaller(put("/bank/withdraw")).contentType(MediaType.APPLICATION_JSON).content("{\"amount\":500}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Insufficient Funds"));

        verify(bankService, never()).withdrawByphno(anyLong(), any());
    }

    @Test
    void withdraw_success() throws Exception {
        when(bankService.findByphno(PHNO)).thenReturn(bankWithBalance(1000));
        when(bankService.withdrawByphno(PHNO, normalized(400))).thenReturn("Withdraw Successful Amount Inr : 400.00");

        mockMvc.perform(asCaller(put("/bank/withdraw")).contentType(MediaType.APPLICATION_JSON).content("{\"amount\":400}"))
                .andExpect(status().isOk())
                .andExpect(content().string("Withdraw Successful Amount Inr : 400.00"));
    }

    @Test
    void updatePhone_usesThePhoneFromTheToken() throws Exception {
        Bank updated = bankWithBalance(0);
        updated.setPhno(9123456789L);
        when(bankService.updatePhno(PHNO, 9123456789L)).thenReturn(updated);

        mockMvc.perform(asCaller(put("/bank/update-phone")).contentType(MediaType.APPLICATION_JSON).content("{\"newPhno\":9123456789}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phno").value(9123456789L));
    }

    @Test
    void myTransactions_isTheCallersOwn() throws Exception {
        BankTransaction t = new BankTransaction();
        t.setTransactionId(100000);
        t.setAction("Credit");
        when(bankService.displayTransactionByPhno(PHNO)).thenReturn(List.of(t));

        mockMvc.perform(asCaller(get("/bank/my-transactions")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].action").value("Credit"));

        verify(bankService, never()).displayTransactionByPhno(9000000001L);
    }

    @Test
    void deleteMe_deletesTheCallersOwnAccount() throws Exception {
        mockMvc.perform(asCaller(delete("/bank/me")))
                .andExpect(status().isOk());

        verify(bankService).deleteByPhno(PHNO);
    }

    // ============ GET /bank/all (trusted) ============

    @Test
    void getAll_withAdminKey_returnsListOfDtos() throws Exception {
        when(bankService.findAll()).thenReturn(List.of(
                new BankDto(1, ACNO, "KUMAR CHARAN", 123456789012L, PHNO, money(500))));

        mockMvc.perform(asAdmin(get("/bank/all")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("KUMAR CHARAN"))
                .andExpect(jsonPath("$[0].acno").value(ACNO))
                .andExpect(jsonPath("$[0].balance").value(500.0));
    }

    // ---------- GET by phno / acno ----------

    @Test
    void getByPhno_returnsUser() throws Exception {
        when(bankService.findByphno(PHNO)).thenReturn(bankWithBalance(500));

        mockMvc.perform(asAdmin(get("/bank/getByphno/{phno}", PHNO)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstName").value("Charan"))
                .andExpect(jsonPath("$.phno").value(PHNO))
                .andExpect(jsonPath("$.pinHash").doesNotExist());
    }

    @Test
    void getByAcno_returnsUser() throws Exception {
        when(bankService.findByacno(ACNO)).thenReturn(bankWithBalance(500));

        mockMvc.perform(asAdmin(get("/bank/getByacno/{acno}", ACNO)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acno").value(ACNO));
    }

    @Test
    void getByPhno_unknownUser_returns400WithMessage() throws Exception {
        when(bankService.findByphno(PHNO)).thenReturn(null);

        mockMvc.perform(asAdmin(get("/bank/getByphno/{phno}", PHNO)))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("User not found"));
    }

    @Test
    void getByAcno_unknownUser_returns400WithMessage() throws Exception {
        when(bankService.findByacno(ACNO)).thenReturn(null);

        mockMvc.perform(asAdmin(get("/bank/getByacno/{acno}", ACNO)))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("User not found"));
    }

    @Test
    void getByPhno_nonNumericPath_returns400() throws Exception {
        mockMvc.perform(asAdmin(get("/bank/getByphno/abc")))
                .andExpect(status().isBadRequest());
    }

    // ---------- POST /bank/save (public) ----------

    private static final String VALID_REGISTER_JSON = """
            {"firstName":"Charan","lastName":"Kumar","aadharNumber":123456789012,"phno":9876543210,"pin":"1234"}
            """;

    @Test
    void save_needsNoAuthentication() throws Exception {
        when(bankService.register(any())).thenReturn(bankWithBalance(0));

        mockMvc.perform(post("/bank/save").contentType(MediaType.APPLICATION_JSON).content(VALID_REGISTER_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acno").value(ACNO))
                .andExpect(jsonPath("$.firstName").value("Charan"))
                .andExpect(jsonPath("$.pinHash").doesNotExist());

        verifyNoInteractions(sessionService);
    }

    @Test
    void save_missingPin_returns400() throws Exception {
        mockMvc.perform(post("/bank/save").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"Charan\",\"lastName\":\"Kumar\",\"aadharNumber\":123456789012,\"phno\":9876543210}"))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"123", "1234567"})
    void save_pinWrongLength_returns400(String pin) throws Exception {
        String json = "{\"firstName\":\"Charan\",\"lastName\":\"Kumar\",\"aadharNumber\":123456789012,\"phno\":9876543210,\"pin\":\"" + pin + "\"}";

        mockMvc.perform(post("/bank/save").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("PIN must be 4 to 6 digits"));
    }

    @Test
    void save_invalidMobile_returns400WithMessage() throws Exception {
        when(bankService.register(any())).thenThrow(new MobileNumberException("Invalid mobile number"));

        mockMvc.perform(post("/bank/save").contentType(MediaType.APPLICATION_JSON).content(VALID_REGISTER_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Invalid mobile number"));
    }

    @Test
    void save_duplicateUser_returns400WithMessage() throws Exception {
        when(bankService.register(any())).thenThrow(new UserExistException("mobile number already exist"));

        mockMvc.perform(post("/bank/save").contentType(MediaType.APPLICATION_JSON).content(VALID_REGISTER_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("mobile number already exist"));
    }

    // Two simultaneous requests can both pass the service's checks; a DB unique constraint then stops one of them.
    // Retrying re-runs the normal checks against the committed data, so the client gets the accurate answer.
    private static final String RETRY_MESSAGE = "Another request changed the same data at the same time. Please retry.";

    @Test
    void save_databaseUniqueConstraintViolation_returns409Retry() throws Exception {
        when(bankService.register(any())).thenThrow(new DataIntegrityViolationException("Duplicate entry"));

        mockMvc.perform(post("/bank/save").contentType(MediaType.APPLICATION_JSON).content(VALID_REGISTER_JSON))
                .andExpect(status().isConflict())
                .andExpect(content().string(RETRY_MESSAGE));
    }

    @Test
    void deposit_optimisticLockConflict_returns409Retry() throws Exception {
        when(bankService.depositByphno(anyLong(), any()))
                .thenThrow(new ObjectOptimisticLockingFailureException(Bank.class, 1));

        mockMvc.perform(asAdmin(put("/bank/depositByphno")).param("phno", "" + PHNO).param("balance", "10"))
                .andExpect(status().isConflict())
                .andExpect(content().string(RETRY_MESSAGE));
    }

    @Test
    void withdraw_optimisticLockConflict_returns409Retry() throws Exception {
        when(bankService.findByphno(PHNO)).thenReturn(bankWithBalance(1000));
        when(bankService.withdrawByphno(anyLong(), any()))
                .thenThrow(new ObjectOptimisticLockingFailureException(Bank.class, 1));

        mockMvc.perform(asAdmin(put("/bank/withdrawByphno")).param("phno", "" + PHNO).param("balance", "10"))
                .andExpect(status().isConflict())
                .andExpect(content().string(RETRY_MESSAGE));
    }

    @Test
    void save_malformedJson_returns400() throws Exception {
        mockMvc.perform(post("/bank/save").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest());
    }

    // ---------- PUT /bank/withdrawByphno (trusted) ----------

    @Test
    void withdrawByphno_success() throws Exception {
        when(bankService.findByphno(PHNO)).thenReturn(bankWithBalance(1000));
        when(bankService.withdrawByphno(PHNO, normalized(400))).thenReturn("Withdraw Successful Amount Inr : 400.00");

        mockMvc.perform(asAdmin(put("/bank/withdrawByphno")).param("phno", "" + PHNO).param("balance", "400"))
                .andExpect(status().isOk())
                .andExpect(content().string("Withdraw Successful Amount Inr : 400.00"));
    }

    @Test
    void withdrawByphno_exactBalance_isAllowed() throws Exception {
        when(bankService.findByphno(PHNO)).thenReturn(bankWithBalance(1000));
        when(bankService.withdrawByphno(PHNO, normalized(1000))).thenReturn("Withdraw Successful Amount Inr : 1000.00");

        mockMvc.perform(asAdmin(put("/bank/withdrawByphno")).param("phno", "" + PHNO).param("balance", "1000"))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-50"})
    void withdrawByphno_zeroOrNegativeAmount_returns400(String amount) throws Exception {
        mockMvc.perform(asAdmin(put("/bank/withdrawByphno")).param("phno", "" + PHNO).param("balance", amount))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Amount too low"));

        verify(bankService, never()).withdrawByphno(anyLong(), any());
    }

    @Test
    void withdrawByphno_insufficientFunds_returns400() throws Exception {
        when(bankService.findByphno(PHNO)).thenReturn(bankWithBalance(100));

        mockMvc.perform(asAdmin(put("/bank/withdrawByphno")).param("phno", "" + PHNO).param("balance", "100.01"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Insufficient Funds"));

        verify(bankService, never()).withdrawByphno(anyLong(), any());
    }

    @Test
    void withdrawByphno_unknownUser_returns400() throws Exception {
        when(bankService.findByphno(PHNO)).thenReturn(null);

        mockMvc.perform(asAdmin(put("/bank/withdrawByphno")).param("phno", "" + PHNO).param("balance", "10"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("User not found"));
    }

    @Test
    void withdrawByphno_missingParam_returns400() throws Exception {
        mockMvc.perform(asAdmin(put("/bank/withdrawByphno")).param("phno", "" + PHNO))
                .andExpect(status().isBadRequest());
    }

    // ---------- PUT /bank/withdrawByacno ----------

    @Test
    void withdrawByacno_passesPositiveAmountToService() throws Exception {
        when(bankService.findByacno(ACNO)).thenReturn(bankWithBalance(1000));
        when(bankService.withdrawByacno(anyLong(), any())).thenReturn("Withdraw Successful Amount Inr : 300.00");

        mockMvc.perform(asAdmin(put("/bank/withdrawByacno")).param("acno", "" + ACNO).param("balance", "300"))
                .andExpect(status().isOk());

        // The service SUBTRACTS the amount it receives, so it must receive +300, not -300.
        verify(bankService).withdrawByacno(ACNO, normalized(300));
    }

    @Test
    void withdrawByacno_insufficientFunds_returns400() throws Exception {
        when(bankService.findByacno(ACNO)).thenReturn(bankWithBalance(100));

        mockMvc.perform(asAdmin(put("/bank/withdrawByacno")).param("acno", "" + ACNO).param("balance", "500"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Insufficient Funds"));
    }

    @Test
    void withdrawByacno_zeroAmount_returns400() throws Exception {
        mockMvc.perform(asAdmin(put("/bank/withdrawByacno")).param("acno", "" + ACNO).param("balance", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Amount too low"));
    }

    @Test
    void withdrawByacno_unknownUser_returns400() throws Exception {
        when(bankService.findByacno(ACNO)).thenReturn(null);

        mockMvc.perform(asAdmin(put("/bank/withdrawByacno")).param("acno", "" + ACNO).param("balance", "10"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("User not found"));
    }

    // ---------- PUT /bank/depositByphno ----------

    @Test
    void depositByphno_success() throws Exception {
        when(bankService.depositByphno(PHNO, normalized(250))).thenReturn("Deposit Successful Amount Inr : 250.00");

        mockMvc.perform(asAdmin(put("/bank/depositByphno")).param("phno", "" + PHNO).param("balance", "250"))
                .andExpect(status().isOk())
                .andExpect(content().string("Deposit Successful Amount Inr : 250.00"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1"})
    void depositByphno_zeroOrNegativeAmount_returns400(String amount) throws Exception {
        mockMvc.perform(asAdmin(put("/bank/depositByphno")).param("phno", "" + PHNO).param("balance", amount))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Amount too low"));

        verify(bankService, never()).depositByphno(anyLong(), any());
    }

    @Test
    void depositByphno_unknownUser_returns400() throws Exception {
        when(bankService.depositByphno(anyLong(), any())).thenThrow(new UserNotFoundException("User not found"));

        mockMvc.perform(asAdmin(put("/bank/depositByphno")).param("phno", "" + PHNO).param("balance", "10"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("User not found"));
    }

    @Test
    void depositByphno_nonNumericAmount_returns400() throws Exception {
        mockMvc.perform(asAdmin(put("/bank/depositByphno")).param("phno", "" + PHNO).param("balance", "abc"))
                .andExpect(status().isBadRequest());
    }

    // ---------- PUT /bank/depositByacno ----------

    @Test
    void depositByacno_success() throws Exception {
        when(bankService.depositByacno(ACNO, normalized(75))).thenReturn("Deposit Successful Amount Inr : 75.00");

        mockMvc.perform(asAdmin(put("/bank/depositByacno")).param("acno", "" + ACNO).param("balance", "75"))
                .andExpect(status().isOk())
                .andExpect(content().string("Deposit Successful Amount Inr : 75.00"));
    }

    @Test
    void depositByacno_negativeAmount_returns400() throws Exception {
        mockMvc.perform(asAdmin(put("/bank/depositByacno")).param("acno", "" + ACNO).param("balance", "-5"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Amount too low"));

        verify(bankService, never()).depositByacno(anyLong(), any());
    }

    // ---------- PUT /bank/updatephno ----------

    @Test
    void updatePhno_success() throws Exception {
        Bank updated = bankWithBalance(0);
        updated.setPhno(9123456789L);
        when(bankService.updatePhno(PHNO, 9123456789L)).thenReturn(updated);

        mockMvc.perform(asAdmin(put("/bank/updatephno")).param("phno", "" + PHNO).param("newphno", "9123456789"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phno").value(9123456789L));
    }

    @Test
    void updatePhno_numberTaken_returns400() throws Exception {
        when(bankService.updatePhno(anyLong(), anyLong())).thenThrow(new UserExistException("Phone number already exist"));

        mockMvc.perform(asAdmin(put("/bank/updatephno")).param("phno", "" + PHNO).param("newphno", "9123456789"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Phone number already exist"));
    }

    // ---------- DELETE /bank/deleteuser ----------

    @Test
    void deleteUser_success() throws Exception {
        mockMvc.perform(asAdmin(delete("/bank/deleteuser")).param("phno", "" + PHNO))
                .andExpect(status().isOk());

        verify(bankService).deleteByPhno(PHNO);
    }

    @Test
    void deleteUser_unknownUser_returns400() throws Exception {
        doThrow(new UserNotFoundException("User not found")).when(bankService).deleteByPhno(PHNO);

        mockMvc.perform(asAdmin(delete("/bank/deleteuser")).param("phno", "" + PHNO))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("User not found"));
    }

    // ---------- GET /bank/displayuser ----------

    @Test
    void displayUser_success() throws Exception {
        when(bankService.displayUserByPhno(PHNO))
                .thenReturn(new BankDto(1, ACNO, "KUMAR CHARAN", 123456789012L, PHNO, money(500)));

        mockMvc.perform(asAdmin(get("/bank/displayuser")).param("phno", "" + PHNO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("KUMAR CHARAN"));
    }

    @Test
    void displayUser_unknownUser_returns400() throws Exception {
        when(bankService.displayUserByPhno(PHNO)).thenThrow(new UserNotFoundException("User not found"));

        mockMvc.perform(asAdmin(get("/bank/displayuser")).param("phno", "" + PHNO))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("User not found"));
    }

    // ---------- GET /bank/transactions ----------

    @Test
    void transactions_returnsList() throws Exception {
        BankTransaction t = new BankTransaction();
        t.setTransactionId(100000);
        t.setAction("Credit");
        t.setAmount(money(250));
        t.setBalance(money(750));
        when(bankService.displayTransactionByPhno(PHNO)).thenReturn(List.of(t));

        mockMvc.perform(asAdmin(get("/bank/transactions")).param("phno", "" + PHNO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].action").value("Credit"))
                .andExpect(jsonPath("$[0].transactionId").value(100000))
                .andExpect(jsonPath("$[0].amount").value(250.0));
    }

    // ---------- PUT /bank/admin/set-pin ----------

    @Test
    void setPin_success() throws Exception {
        mockMvc.perform(asAdmin(put("/bank/admin/set-pin")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phno\":9876543210,\"newPin\":\"5678\"}"))
                .andExpect(status().isNoContent());

        verify(bankService).setPin(PHNO, "5678");
    }

    @Test
    void setPin_unknownUser_returns400() throws Exception {
        doThrow(new UserNotFoundException("User not found")).when(bankService).setPin(PHNO, "5678");

        mockMvc.perform(asAdmin(put("/bank/admin/set-pin")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phno\":9876543210,\"newPin\":\"5678\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("User not found"));
    }

    @Test
    void setPin_badPinFormat_returns400() throws Exception {
        mockMvc.perform(asAdmin(put("/bank/admin/set-pin")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phno\":9876543210,\"newPin\":\"12\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("PIN must be 4 to 6 digits"));

        verifyNoInteractions(bankService);
    }
}
