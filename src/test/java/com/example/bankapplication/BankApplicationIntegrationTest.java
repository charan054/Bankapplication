package com.example.bankapplication;

import com.example.bankapplication.entity.Bank;
import com.example.bankapplication.entity.BankTransaction;
import com.example.bankapplication.kafka.BankKafkaProducer;
import com.example.bankapplication.repository.BankRepository;
import com.example.bankapplication.repository.BankSessionRepository;
import com.example.bankapplication.repository.BankTransactionRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end tests: real controller + real service + real JPA + in-memory H2 + real BCrypt.
 * Only Kafka is faked, so no broker is needed. ADMIN_KEY/SERVICE_KEY come from application-test.properties.
 */
// Login and deposit/withdraw are both rate-limited (see WebConfig); this suite legitimately logs the same few
// simulated customers in and out, and deposits/withdraws on their accounts, many times over its run, so it needs
// much higher budgets than production traffic from one real address/account would ever need.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@TestPropertySource(properties = {
        "bank.login.rate-limit.max-attempts=1000",
        "bank.money.rate-limit.max-attempts=1000"
})
class BankApplicationIntegrationTest {

    private static final long PHNO_A = 9876543210L;
    private static final long PHNO_B = 9123456789L;
    private static final long AADHAR_A = 111111111111L;
    private static final long AADHAR_B = 222222222222L;
    private static final long FIRST_ACNO = 1000000000L;
    private static final String PIN = "1234";
    private static final String ADMIN_KEY = "test-admin-key";     // matches application-test.properties
    private static final String SERVICE_KEY = "test-service-key";

    @Autowired
    private MockMvc mockMvc;
    // A spy behaves like the real repository until a test tells it to fail (see the atomicity tests below).
    @MockitoSpyBean
    private BankRepository bankRepository;
    @Autowired
    private BankTransactionRepository bankTransactionRepository;
    @Autowired
    private BankSessionRepository bankSessionRepository;

    @MockitoBean
    private BankKafkaProducer bankKafkaProducer;
    @MockitoBean
    private com.example.bankapplication.service.MailService mailService;
    @Autowired
    private com.example.bankapplication.repository.PendingSignupRepository pendingSignupRepository;
    @Autowired
    private com.example.bankapplication.repository.WithdrawalOtpRepository withdrawalOtpRepository;

    // The database lives for the whole test run, so start every test from an empty bank.
    @BeforeEach
    void emptyTheBank() {
        pendingSignupRepository.deleteAll();
        withdrawalOtpRepository.deleteAll();
        bankSessionRepository.deleteAll();
        bankTransactionRepository.deleteAll();
        bankRepository.deleteAll();
    }


    // ---------- self-signup confirmed by an emailed code ----------

    private static final String SIGNUP_JSON = """
            {"firstName":"Charan","lastName":"Kumar","aadharNumber":333344445555,"phno":9123456789,"pin":"4321","email":"new@example.com"}
            """;

    private String requestSignupAndReadTheEmailedCode() throws Exception {
        mockMvc.perform(post("/bank/register/request").contentType(MediaType.APPLICATION_JSON).content(SIGNUP_JSON))
                .andExpect(status().isNoContent());
        org.mockito.ArgumentCaptor<String> body = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(mailService).send(org.mockito.ArgumentMatchers.eq("new@example.com"),
                org.mockito.ArgumentMatchers.anyString(), body.capture());
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\b[0-9]{6}\\b").matcher(body.getValue());
        org.junit.jupiter.api.Assertions.assertTrue(m.find());
        return m.group();
    }

    @Test
    void signup_createsNoAccountUntilTheEmailedCodeIsEntered_thenTheCustomerCanLogIn() throws Exception {
        String code = requestSignupAndReadTheEmailedCode();

        // not an account yet
        mockMvc.perform(post("/bank/login").contentType(MediaType.APPLICATION_JSON).content("{\"phno\":9123456789,\"pin\":\"4321\"}"))
                .andExpect(status().is4xxClientError());
        org.junit.jupiter.api.Assertions.assertEquals(0, bankRepository.count());

        mockMvc.perform(post("/bank/register/verify").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phno\":9123456789,\"otp\":\"" + code + "\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/bank/login").contentType(MediaType.APPLICATION_JSON).content("{\"phno\":9123456789,\"pin\":\"4321\"}"))
                .andExpect(status().isOk());
        org.junit.jupiter.api.Assertions.assertEquals(1, bankRepository.count());
        org.junit.jupiter.api.Assertions.assertEquals(0, pendingSignupRepository.count());
    }

    @Test
    void signup_aWrongCodeCreatesNothingAndTheRightCodeStillWorksAfterwards() throws Exception {
        String code = requestSignupAndReadTheEmailedCode();
        String wrong = code.equals("000000") ? "111111" : "000000";

        mockMvc.perform(post("/bank/register/verify").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phno\":9123456789,\"otp\":\"" + wrong + "\"}"))
                .andExpect(status().is4xxClientError());
        org.junit.jupiter.api.Assertions.assertEquals(0, bankRepository.count());
        org.junit.jupiter.api.Assertions.assertEquals(1, pendingSignupRepository.findAll().get(0).getAttempts());

        mockMvc.perform(post("/bank/register/verify").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phno\":9123456789,\"otp\":\"" + code + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void signup_aTakenPhoneNumberIsRefusedBeforeAnyCodeIsEmailed() throws Exception {
        createUser(9123456789L, 111122223333L);

        mockMvc.perform(post("/bank/register/request").contentType(MediaType.APPLICATION_JSON).content(SIGNUP_JSON))
                .andExpect(status().is4xxClientError());

        org.mockito.Mockito.verifyNoInteractions(mailService);
        org.junit.jupiter.api.Assertions.assertEquals(0, pendingSignupRepository.count());
    }

    // ---------- emailed code for large withdrawals ----------

    private String loggedInRichCustomerWithEmail() throws Exception {
        String json = """
                {"firstName":"Charan","lastName":"Kumar","aadharNumber":%d,"phno":%d,"pin":"%s","email":"rich@example.com"}
                """.formatted(AADHAR_A, PHNO_A, PIN);
        mockMvc.perform(asAdmin(post("/bank/save")).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk());
        depositByPhno(PHNO_A, "50000");
        return login(PHNO_A, PIN);
    }

    private String requestWithdrawalCode(String token, int amount) throws Exception {
        org.mockito.Mockito.clearInvocations(mailService);
        mockMvc.perform(as(token, post("/bank/withdraw/otp")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":" + amount + "}"))
                .andExpect(status().isNoContent());
        org.mockito.ArgumentCaptor<String> body = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(mailService).send(org.mockito.ArgumentMatchers.eq("rich@example.com"),
                org.mockito.ArgumentMatchers.anyString(), body.capture());
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\b[0-9]{6}\\b").matcher(body.getValue());
        org.junit.jupiter.api.Assertions.assertTrue(m.find());
        return m.group();
    }

    @Test
    void largeWithdrawal_needsTheEmailedCode_andTheCodeWorksOnceForThatAmountOnly() throws Exception {
        String token = loggedInRichCustomerWithEmail();

        // no code: refused, nothing debited
        mockMvc.perform(as(token, put("/bank/withdraw")).contentType(MediaType.APPLICATION_JSON).content("{\"amount\":20000}"))
                .andExpect(status().isForbidden());
        balanceShouldBe(PHNO_A, 50000.0);

        String code = requestWithdrawalCode(token, 20000);

        // the code is for 20000 only
        mockMvc.perform(as(token, put("/bank/withdraw")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":30000,\"otp\":\"" + code + "\"}"))
                .andExpect(status().isBadRequest());
        balanceShouldBe(PHNO_A, 50000.0);

        // the right code for the right amount goes through...
        mockMvc.perform(as(token, put("/bank/withdraw")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":20000,\"otp\":\"" + code + "\"}"))
                .andExpect(status().isOk());
        balanceShouldBe(PHNO_A, 30000.0);

        // ...and cannot be replayed
        mockMvc.perform(as(token, put("/bank/withdraw")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":20000,\"otp\":\"" + code + "\"}"))
                .andExpect(status().isBadRequest());
        balanceShouldBe(PHNO_A, 30000.0);
    }

    @Test
    void smallWithdrawal_neverNeedsACode() throws Exception {
        String token = loggedInRichCustomerWithEmail();

        mockMvc.perform(as(token, put("/bank/withdraw")).contentType(MediaType.APPLICATION_JSON).content("{\"amount\":9999}"))
                .andExpect(status().isOk());
        balanceShouldBe(PHNO_A, 40001.0);
    }

    @Test
    void largeWithdrawalCodeRequest_needsALogin() throws Exception {
        mockMvc.perform(post("/bank/withdraw/otp").contentType(MediaType.APPLICATION_JSON).content("{\"amount\":20000}"))
                .andExpect(status().isUnauthorized());
    }

    // ---------- security alert emails ----------

    private void createUserWithEmail(long phno, long aadhar, String email) throws Exception {
        String json = """
                {"firstName":"Charan","lastName":"Kumar","aadharNumber":%d,"phno":%d,"pin":"%s","email":"%s"}
                """.formatted(aadhar, phno, PIN, email);
        mockMvc.perform(asAdmin(post("/bank/save")).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk());
        org.mockito.Mockito.clearInvocations(mailService);
    }

    @Test
    void alerts_aSuccessfulLoginEmailsTheAccountOwner() throws Exception {
        createUserWithEmail(PHNO_A, AADHAR_A, "alerts@example.com");

        login(PHNO_A, PIN);

        org.mockito.Mockito.verify(mailService, org.mockito.Mockito.timeout(3000)).send(
                org.mockito.ArgumentMatchers.eq("alerts@example.com"),
                org.mockito.ArgumentMatchers.eq("New login to your bank account"), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void alerts_aWrongPinSendsNoLoginEmail() throws Exception {
        createUserWithEmail(PHNO_A, AADHAR_A, "alerts@example.com");

        mockMvc.perform(post("/bank/login").contentType(MediaType.APPLICATION_JSON).content("{\"phno\":" + PHNO_A + ",\"pin\":\"9999\"}"))
                .andExpect(status().isUnauthorized());

        org.mockito.Mockito.verify(mailService, org.mockito.Mockito.after(500).never()).send(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void alerts_repeatedWrongPinsEmailALockoutNotice() throws Exception {
        createUserWithEmail(PHNO_A, AADHAR_A, "alerts@example.com");

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/bank/login").contentType(MediaType.APPLICATION_JSON).content("{\"phno\":" + PHNO_A + ",\"pin\":\"9999\"}"));
        }

        org.mockito.Mockito.verify(mailService, org.mockito.Mockito.timeout(3000)).send(
                org.mockito.ArgumentMatchers.eq("alerts@example.com"),
                org.mockito.ArgumentMatchers.eq("Your bank account was temporarily locked"), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void alerts_changingTheEmailWarnsTheOldAddressNotTheNewOne() throws Exception {
        createUserWithEmail(PHNO_A, AADHAR_A, "old@example.com");
        String token = login(PHNO_A, PIN);
        org.mockito.Mockito.clearInvocations(mailService);

        mockMvc.perform(as(token, put("/bank/update-email")).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"new@example.com\"}"))
                .andExpect(status().isOk());

        org.mockito.Mockito.verify(mailService, org.mockito.Mockito.timeout(3000)).send(
                org.mockito.ArgumentMatchers.eq("old@example.com"),
                org.mockito.ArgumentMatchers.eq("The email on your bank account was changed"), org.mockito.ArgumentMatchers.anyString());
        org.mockito.Mockito.verify(mailService, org.mockito.Mockito.never()).send(
                org.mockito.ArgumentMatchers.eq("new@example.com"), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void alerts_aPinResetEmailsAPinChangedNotice() throws Exception {
        createUserWithEmail(PHNO_A, AADHAR_A, "alerts@example.com");

        mockMvc.perform(asAdmin(put("/bank/admin/set-pin")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phno\":" + PHNO_A + ",\"newPin\":\"5678\"}"))
                .andExpect(status().is2xxSuccessful());

        org.mockito.Mockito.verify(mailService, org.mockito.Mockito.timeout(3000)).send(
                org.mockito.ArgumentMatchers.eq("alerts@example.com"),
                org.mockito.ArgumentMatchers.eq("Your bank PIN was changed"), org.mockito.ArgumentMatchers.anyString());
    }

    // ---------- logins made by a trusted backend (PhonepayService) ----------

    @Test
    void trustedServiceLogin_worksButSendsNoNewLoginEmail() throws Exception {
        createUserWithEmail(PHNO_A, AADHAR_A, "alerts@example.com");

        mockMvc.perform(post("/bank/login").header("X-Service-Key", "test-service-key")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"phno\":" + PHNO_A + ",\"pin\":\"" + PIN + "\"}"))
                .andExpect(status().isOk());

        org.mockito.Mockito.verify(mailService, org.mockito.Mockito.after(500).never()).send(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void trustedServiceLogin_isNotHeldToThePerAddressLoginLimit() throws Exception {
        createUserWithEmail(PHNO_A, AADHAR_A, "alerts@example.com");

        // far more than the per-address budget, as when every PhonePe customer logs in through one backend
        for (int i = 0; i < 40; i++) {
            mockMvc.perform(post("/bank/login").header("X-Service-Key", "test-service-key")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"phno\":" + PHNO_A + ",\"pin\":\"" + PIN + "\"}"))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void aWrongServiceKeyOnLoginIsJustAnOrdinaryLogin() throws Exception {
        createUserWithEmail(PHNO_A, AADHAR_A, "alerts@example.com");

        mockMvc.perform(post("/bank/login").header("X-Service-Key", "not-the-key")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"phno\":" + PHNO_A + ",\"pin\":\"" + PIN + "\"}"))
                .andExpect(status().isOk());

        org.mockito.Mockito.verify(mailService, org.mockito.Mockito.timeout(3000)).send(
                org.mockito.ArgumentMatchers.eq("alerts@example.com"),
                org.mockito.ArgumentMatchers.eq("New login to your bank account"), org.mockito.ArgumentMatchers.anyString());
    }

    // ---------- active logins ----------

    private String loginWithAgent(long phno, String agent) throws Exception {
        String body = mockMvc.perform(post("/bank/login").header("User-Agent", agent).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phno\":" + phno + ",\"pin\":\"" + PIN + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"token\":\"([^\"]+)\"").matcher(body);
        org.junit.jupiter.api.Assertions.assertTrue(m.find());
        return m.group(1);
    }

    @Test
    void sessions_listShowsEachLoginWithItsDeviceAndMarksTheCurrentOne() throws Exception {
        createUser(PHNO_A, AADHAR_A);
        String laptop = loginWithAgent(PHNO_A, "Mozilla/5.0 (Windows NT 10.0) Chrome/120.0 Safari/537.36");
        loginWithAgent(PHNO_A, "Mozilla/5.0 (Linux; Android 14) Chrome/120.0 Mobile Safari/537.36");

        mockMvc.perform(as(laptop, get("/bank/sessions")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.current==true)].device").value("Chrome on Windows"))
                .andExpect(jsonPath("$[?(@.current==false)].device").value("Chrome on Android"))
                .andExpect(jsonPath("$[0].tokenHash").doesNotExist());
    }

    @Test
    void sessions_aCustomerCanEndAnotherLoginButNotSomeoneElses() throws Exception {
        createUser(PHNO_A, AADHAR_A);
        createUser(PHNO_B, AADHAR_B);
        String mine = loginWithAgent(PHNO_A, "Mozilla/5.0 (Windows NT 10.0) Chrome/120.0");
        String stolen = loginWithAgent(PHNO_A, "Mozilla/5.0 (Linux; Android 14) Firefox/120.0");
        String other = loginWithAgent(PHNO_B, "Mozilla/5.0 (Windows NT 10.0) Chrome/120.0");

        String list = mockMvc.perform(as(mine, get("/bank/sessions"))).andReturn().getResponse().getContentAsString();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"id\":(\\d+),[^}]*\"current\":false").matcher(list);
        org.junit.jupiter.api.Assertions.assertTrue(m.find());
        String otherLoginId = m.group(1);

        mockMvc.perform(as(mine, delete("/bank/sessions/" + otherLoginId))).andExpect(status().isNoContent());
        // that login is dead now
        mockMvc.perform(as(stolen, get("/bank/me"))).andExpect(status().isUnauthorized());
        // an unknown id (or someone else's) is a 404, and the other customer is untouched
        mockMvc.perform(as(mine, delete("/bank/sessions/" + otherLoginId))).andExpect(status().isNotFound());
        mockMvc.perform(as(other, get("/bank/me"))).andExpect(status().isOk());
    }

    @Test
    void logoutAll_keepCurrentLeavesThisLoginAndEndsTheRest() throws Exception {
        createUser(PHNO_A, AADHAR_A);
        String here = loginWithAgent(PHNO_A, "Mozilla/5.0 (Windows NT 10.0) Chrome/120.0");
        String there = loginWithAgent(PHNO_A, "Mozilla/5.0 (Linux; Android 14) Chrome/120.0");

        mockMvc.perform(as(here, post("/bank/logout-all")).param("keepCurrent", "true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ended").value(1));

        mockMvc.perform(as(here, get("/bank/me"))).andExpect(status().isOk());
        mockMvc.perform(as(there, get("/bank/me"))).andExpect(status().isUnauthorized());

        mockMvc.perform(as(here, post("/bank/logout-all"))).andExpect(status().isOk()).andExpect(jsonPath("$.ended").value(1));
        mockMvc.perform(as(here, get("/bank/me"))).andExpect(status().isUnauthorized());
    }

    @Test
    void sessions_needALogin() throws Exception {
        mockMvc.perform(get("/bank/sessions")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/bank/logout-all")).andExpect(status().isUnauthorized());
    }

    // ---------- daily limit ----------

    @Test
    void dailyLimit_aCustomerCanLowerItAndThenCannotWithdrawPastIt() throws Exception {
        createUser(PHNO_A, AADHAR_A);
        depositByPhno(PHNO_A, "5000");
        String token = login(PHNO_A, PIN);

        mockMvc.perform(as(token, get("/bank/limits"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.dailyLimit").value(100000)).andExpect(jsonPath("$.isDefault").value(true));

        mockMvc.perform(as(token, put("/bank/limits")).contentType(MediaType.APPLICATION_JSON).content("{\"dailyLimit\":1000}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.dailyLimit").value(1000));

        mockMvc.perform(as(token, put("/bank/withdraw")).contentType(MediaType.APPLICATION_JSON).content("{\"amount\":700}"))
                .andExpect(status().isOk());
        mockMvc.perform(as(token, put("/bank/withdraw")).contentType(MediaType.APPLICATION_JSON).content("{\"amount\":400}"))
                .andExpect(status().isBadRequest());
        balanceShouldBe(PHNO_A, 4300.0);

        mockMvc.perform(as(token, get("/bank/limits"))).andExpect(jsonPath("$.usedToday").value(700))
                .andExpect(jsonPath("$.remainingToday").value(300));
    }

    @Test
    void dailyLimit_raisingItDoesNotApplyStraightAway() throws Exception {
        createUser(PHNO_A, AADHAR_A);
        depositByPhno(PHNO_A, "5000");
        String token = login(PHNO_A, PIN);
        mockMvc.perform(as(token, put("/bank/limits")).contentType(MediaType.APPLICATION_JSON).content("{\"dailyLimit\":1000}"))
                .andExpect(status().isOk());

        mockMvc.perform(as(token, put("/bank/limits")).contentType(MediaType.APPLICATION_JSON).content("{\"dailyLimit\":50000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dailyLimit").value(1000))
                .andExpect(jsonPath("$.pendingLimit").value(50000));

        mockMvc.perform(as(token, put("/bank/withdraw")).contentType(MediaType.APPLICATION_JSON).content("{\"amount\":2000}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void dailyLimit_alsoStopsDebitsMadeByTheTrustedBackend() throws Exception {
        createUser(PHNO_A, AADHAR_A);
        depositByPhno(PHNO_A, "5000");
        String token = login(PHNO_A, PIN);
        mockMvc.perform(as(token, put("/bank/limits")).contentType(MediaType.APPLICATION_JSON).content("{\"dailyLimit\":1000}"))
                .andExpect(status().isOk());

        mockMvc.perform(asAdmin(put("/bank/withdrawByphno")).param("phno", "" + PHNO_A).param("balance", "1500"))
                .andExpect(status().isBadRequest());
        balanceShouldBe(PHNO_A, 5000.0);
    }

    @Test
    void dailyLimit_needsALogin() throws Exception {
        mockMvc.perform(get("/bank/limits")).andExpect(status().isUnauthorized());
        mockMvc.perform(put("/bank/limits").contentType(MediaType.APPLICATION_JSON).content("{\"dailyLimit\":1}"))
                .andExpect(status().isUnauthorized());
    }

    // ---------- statement by email ----------

    @Test
    void statementEmail_sendsTheMonthToTheAddressOnFile() throws Exception {
        createUserWithEmail(PHNO_A, AADHAR_A, "stmt@example.com");
        org.mockito.Mockito.when(mailService.sendWithAttachment(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        String token = login(PHNO_A, PIN);

        mockMvc.perform(as(token, post("/bank/statement/email")).param("month", java.time.YearMonth.now().toString()))
                .andExpect(status().isNoContent());

        org.mockito.Mockito.verify(mailService).sendWithAttachment(org.mockito.ArgumentMatchers.eq("stmt@example.com"),
                org.mockito.ArgumentMatchers.contains("statement"), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.endsWith(".csv"), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("text/csv"));
    }

    @Test
    void statementEmail_badMonthAndFutureMonthAreRefused_andItNeedsALogin() throws Exception {
        createUserWithEmail(PHNO_A, AADHAR_A, "stmt@example.com");
        String token = login(PHNO_A, PIN);

        mockMvc.perform(as(token, post("/bank/statement/email")).param("month", "last-week")).andExpect(status().isBadRequest());
        mockMvc.perform(as(token, post("/bank/statement/email")).param("month", "2999-01")).andExpect(status().isBadRequest());
        mockMvc.perform(post("/bank/statement/email")).andExpect(status().isUnauthorized());
    }
    // ---------- helpers ----------

    private MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder request) {
        return request.header("X-Admin-Key", ADMIN_KEY);
    }

    private void createUser(long phno, long aadhar) throws Exception {
        String json = """
                {"firstName":"Charan","lastName":"Kumar","aadharNumber":%d,"phno":%d,"pin":"%s"}
                """.formatted(aadhar, phno, PIN);
        mockMvc.perform(asAdmin(post("/bank/save")).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk());
    }

    private void depositByPhno(long phno, String amount) throws Exception {
        mockMvc.perform(asAdmin(put("/bank/depositByphno")).param("phno", "" + phno).param("balance", amount))
                .andExpect(status().isOk());
    }

    private void balanceShouldBe(long phno, double expected) throws Exception {
        mockMvc.perform(asAdmin(get("/bank/displayuser")).param("phno", "" + phno))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(expected));
    }

    // BigDecimal.equals() is scale-sensitive ("1000" != "1000.00" though numerically equal); compareTo() is not.
    private void assertMoney(double expected, BigDecimal actual) {
        assertEquals(0, BigDecimal.valueOf(expected).compareTo(actual), () -> expected + " != " + actual);
    }

    private String login(long phno, String pin) throws Exception {
        String body = mockMvc.perform(post("/bank/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phno\":" + phno + ",\"pin\":\"" + pin + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.token");
    }

    private MockHttpServletRequestBuilder as(String token, MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + token);
    }

    // ---------- account creation ----------

    @Test
    void createUsers_getSequentialAccountNumbers() throws Exception {
        createUser(PHNO_A, AADHAR_A);
        createUser(PHNO_B, AADHAR_B);

        mockMvc.perform(asAdmin(get("/bank/all")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].acno").value(FIRST_ACNO))
                .andExpect(jsonPath("$.content[1].acno").value(FIRST_ACNO + 1))
                .andExpect(jsonPath("$.content[0].name").value("KUMAR CHARAN"));
    }

    @Test
    void getAllUsers_isPaginated() throws Exception {
        createUser(PHNO_A, AADHAR_A);
        createUser(PHNO_B, AADHAR_B);
        createUser(9000000001L, 333333333333L);

        mockMvc.perform(asAdmin(get("/bank/all")).param("page", "0").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content[0].acno").value(FIRST_ACNO));

        mockMvc.perform(asAdmin(get("/bank/all")).param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].acno").value(FIRST_ACNO + 2));
    }

    @Test
    void getAllUsers_invalidPageOrSize_returns400() throws Exception {
        mockMvc.perform(asAdmin(get("/bank/all")).param("page", "-1"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(asAdmin(get("/bank/all")).param("size", "0"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(asAdmin(get("/bank/all")).param("size", "1000"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createUser_duplicatePhone_isRejected_andNothingExtraIsStored() throws Exception {
        createUser(PHNO_A, AADHAR_A);

        String duplicate = """
                {"firstName":"Other","lastName":"Person","aadharNumber":%d,"phno":%d,"pin":"%s"}
                """.formatted(AADHAR_B, PHNO_A, PIN);
        mockMvc.perform(asAdmin(post("/bank/save")).contentType(MediaType.APPLICATION_JSON).content(duplicate))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("mobile number already exist"));

        assertEquals(1, bankRepository.count());
    }

    @Test
    void createUser_invalidMobile_isRejected() throws Exception {
        String bad = """
                {"firstName":"A","lastName":"B","aadharNumber":%d,"phno":5876543210,"pin":"%s"}
                """.formatted(AADHAR_A, PIN);
        mockMvc.perform(asAdmin(post("/bank/save")).contentType(MediaType.APPLICATION_JSON).content(bad))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Invalid mobile number"));

        assertEquals(0, bankRepository.count());
    }

    @Test
    void createUser_pinIsHashed_neverStoredOrReturnedInPlainText() throws Exception {
        String response = mockMvc.perform(asAdmin(post("/bank/save")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"Charan\",\"lastName\":\"Kumar\",\"aadharNumber\":" + AADHAR_A
                                + ",\"phno\":" + PHNO_A + ",\"pin\":\"" + PIN + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(!response.contains(PIN), "the raw PIN must never appear in the response: " + response);
        assertTrue(!response.contains("pinHash"), "the hash field must not be serialized at all: " + response);
        Bank stored = bankRepository.findByphno(PHNO_A);
        assertTrue(!PIN.equals(stored.getPinHash()), "the stored value must be a hash, not the raw PIN");
    }

    // ---------- customer login and self-service ----------

    @Test
    void login_withCorrectPin_thenSelfServiceWorks() throws Exception {
        createUser(PHNO_A, AADHAR_A);
        String token = login(PHNO_A, PIN);

        mockMvc.perform(as(token, get("/bank/me")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("KUMAR CHARAN"));

        mockMvc.perform(as(token, put("/bank/deposit")).contentType(MediaType.APPLICATION_JSON).content("{\"amount\":500}"))
                .andExpect(status().isOk());
        balanceShouldBe(PHNO_A, 500.0);

        mockMvc.perform(as(token, put("/bank/withdraw")).contentType(MediaType.APPLICATION_JSON).content("{\"amount\":200}"))
                .andExpect(status().isOk());
        balanceShouldBe(PHNO_A, 300.0);

        mockMvc.perform(as(token, get("/bank/my-transactions")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2));
    }

    @Test
    void login_wrongPin_isRejected_andStartsNoSession() throws Exception {
        createUser(PHNO_A, AADHAR_A);

        mockMvc.perform(post("/bank/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phno\":" + PHNO_A + ",\"pin\":\"0000\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string("Invalid phone number or PIN"));

        assertEquals(0, bankSessionRepository.count());
    }

    @Test
    void login_unknownPhoneNumber_getsTheSameMessageAsAWrongPin() throws Exception {
        // so a login attempt cannot be used to discover whether a phone number has an account at all
        mockMvc.perform(post("/bank/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phno\":9999999999,\"pin\":\"0000\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string("Invalid phone number or PIN"));
    }

    @Test
    void login_fiveWrongPins_locksTheAccount_evenForTheCorrectPinAfterwards() throws Exception {
        createUser(PHNO_A, AADHAR_A);

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/bank/login").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"phno\":" + PHNO_A + ",\"pin\":\"0000\"}"))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(post("/bank/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phno\":" + PHNO_A + ",\"pin\":\"" + PIN + "\"}"))
                .andExpect(status().isLocked());
    }

    @Test
    void logout_endsTheSession() throws Exception {
        createUser(PHNO_A, AADHAR_A);
        String token = login(PHNO_A, PIN);

        mockMvc.perform(as(token, post("/bank/logout"))).andExpect(status().isNoContent());

        mockMvc.perform(as(token, get("/bank/me"))).andExpect(status().isUnauthorized());
    }

    @Test
    void twoCustomersLoggedInAtOnce_eachActsOnlyOnTheirOwnAccount() throws Exception {
        createUser(PHNO_A, AADHAR_A);
        createUser(PHNO_B, AADHAR_B);
        String tokenA = login(PHNO_A, PIN);
        String tokenB = login(PHNO_B, PIN);

        mockMvc.perform(as(tokenA, put("/bank/deposit")).contentType(MediaType.APPLICATION_JSON).content("{\"amount\":100}"))
                .andExpect(status().isOk());

        balanceShouldBe(PHNO_A, 100.0);
        balanceShouldBe(PHNO_B, 0.0);   // untouched by A's deposit, even though B logged in most recently
    }

    // ---------- admin: bootstrapping / resetting a PIN ----------

    @Test
    void adminSetPin_letsAnAccountWithNoPinLogIn() throws Exception {
        // Simulates an account that existed before login did: register normally, then simulate the old state
        // by clearing the hash directly, the way a pre-existing row from before this feature would look.
        createUser(PHNO_A, AADHAR_A);
        Bank user = bankRepository.findByphno(PHNO_A);
        user.setPinHash(null);
        bankRepository.save(user);

        mockMvc.perform(post("/bank/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phno\":" + PHNO_A + ",\"pin\":\"" + PIN + "\"}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(asAdmin(put("/bank/admin/set-pin")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phno\":" + PHNO_A + ",\"newPin\":\"5678\"}"))
                .andExpect(status().isNoContent());

        String token = login(PHNO_A, "5678");
        mockMvc.perform(as(token, get("/bank/me"))).andExpect(status().isOk());
    }

    @Test
    void adminSetPin_needsTheAdminKey() throws Exception {
        createUser(PHNO_A, AADHAR_A);

        mockMvc.perform(put("/bank/admin/set-pin").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phno\":" + PHNO_A + ",\"newPin\":\"5678\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ---------- deposit / withdraw / history (trusted tier) ----------

    @Test
    void depositThenWithdraw_updatesBalance_andRecordsHistoryInOrder() throws Exception {
        createUser(PHNO_A, AADHAR_A);

        depositByPhno(PHNO_A, "1000");
        mockMvc.perform(asAdmin(put("/bank/withdrawByphno")).param("phno", "" + PHNO_A).param("balance", "400"))
                .andExpect(status().isOk())
                .andExpect(content().string("Withdraw Successful Amount Inr : 400.00"));

        balanceShouldBe(PHNO_A, 600.0);

        mockMvc.perform(asAdmin(get("/bank/transactions")).param("phno", "" + PHNO_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].transactionId").value(100000))
                .andExpect(jsonPath("$.content[0].action").value("Credit"))
                .andExpect(jsonPath("$.content[0].amount").value(1000.0))
                .andExpect(jsonPath("$.content[0].balance").value(1000.0))
                .andExpect(jsonPath("$.content[1].transactionId").value(100001))
                .andExpect(jsonPath("$.content[1].action").value("Debit"))
                .andExpect(jsonPath("$.content[1].amount").value(400.0))
                .andExpect(jsonPath("$.content[1].balance").value(600.0));
    }

    // Regression test for the bug where the controller negated the amount and a withdrawal ADDED money.
    @Test
    void withdrawByAccountNumber_reducesBalance() throws Exception {
        createUser(PHNO_A, AADHAR_A);
        mockMvc.perform(asAdmin(put("/bank/depositByacno")).param("acno", "" + FIRST_ACNO).param("balance", "1000"))
                .andExpect(status().isOk());

        mockMvc.perform(asAdmin(put("/bank/withdrawByacno")).param("acno", "" + FIRST_ACNO).param("balance", "300"))
                .andExpect(status().isOk());

        balanceShouldBe(PHNO_A, 700.0);
        mockMvc.perform(asAdmin(get("/bank/transactions")).param("phno", "" + PHNO_A))
                .andExpect(jsonPath("$.content[1].action").value("Debit"))
                .andExpect(jsonPath("$.content[1].amount").value(300.0))     // recorded as +300, not -300
                .andExpect(jsonPath("$.content[1].balance").value(700.0));
    }

    @Test
    void overdraw_isRejected_balanceUnchanged_noTransactionRecorded() throws Exception {
        createUser(PHNO_A, AADHAR_A);
        depositByPhno(PHNO_A, "100");

        mockMvc.perform(asAdmin(put("/bank/withdrawByphno")).param("phno", "" + PHNO_A).param("balance", "500"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Insufficient Funds"));

        balanceShouldBe(PHNO_A, 100.0);
        assertEquals(1, bankTransactionRepository.count());   // only the deposit
    }

    @Test
    void deposit_sendsKafkaNotification() throws Exception {
        createUser(PHNO_A, AADHAR_A);

        depositByPhno(PHNO_A, "250");

        verify(bankKafkaProducer).sendMessage(contains("Amount deposited successfully"));
    }

    @Test
    void transactionsOfOneUser_doNotLeakToAnother() throws Exception {
        createUser(PHNO_A, AADHAR_A);
        createUser(PHNO_B, AADHAR_B);
        depositByPhno(PHNO_A, "100");
        depositByPhno(PHNO_B, "900");

        mockMvc.perform(asAdmin(get("/bank/transactions")).param("phno", "" + PHNO_A))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].amount").value(100.0));
        mockMvc.perform(asAdmin(get("/bank/transactions")).param("phno", "" + PHNO_B))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].amount").value(900.0));
    }

    @Test
    void transactions_arePaginated() throws Exception {
        createUser(PHNO_A, AADHAR_A);
        for (int i = 0; i < 3; i++) {
            depositByPhno(PHNO_A, "100");
        }

        mockMvc.perform(asAdmin(get("/bank/transactions")).param("phno", "" + PHNO_A).param("page", "0").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content[0].transactionId").value(100000));

        mockMvc.perform(asAdmin(get("/bank/transactions")).param("phno", "" + PHNO_A).param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].transactionId").value(100002));
    }

    @Test
    void transactions_canBeFilteredByDateRange() throws Exception {
        createUser(PHNO_A, AADHAR_A);
        depositByPhno(PHNO_A, "100");

        BankTransaction stored = bankTransactionRepository.findAll().get(0);
        stored.setCreatedAt(Instant.parse("2020-01-01T00:00:00Z"));
        bankTransactionRepository.save(stored);

        mockMvc.perform(asAdmin(get("/bank/transactions")).param("phno", "" + PHNO_A)
                        .param("from", "2026-01-01T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));

        mockMvc.perform(asAdmin(get("/bank/transactions")).param("phno", "" + PHNO_A)
                        .param("to", "2026-01-01T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1));
    }

    @Test
    void transactions_fromAfterTo_returns400() throws Exception {
        createUser(PHNO_A, AADHAR_A);

        mockMvc.perform(asAdmin(get("/bank/transactions")).param("phno", "" + PHNO_A)
                        .param("from", "2026-02-01T00:00:00Z").param("to", "2026-01-01T00:00:00Z"))
                .andExpect(status().isBadRequest());
    }

    // ---------- update / delete ----------

    @Test
    void updatePhone_movesTheAccountToTheNewNumber() throws Exception {
        createUser(PHNO_A, AADHAR_A);
        depositByPhno(PHNO_A, "500");

        mockMvc.perform(asAdmin(put("/bank/updatephno")).param("phno", "" + PHNO_A).param("newphno", "" + PHNO_B))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phno").value(PHNO_B));

        balanceShouldBe(PHNO_B, 500.0);
        mockMvc.perform(asAdmin(get("/bank/displayuser")).param("phno", "" + PHNO_A))
                .andExpect(status().isNotFound())
                .andExpect(content().string("User not found"));
    }

    @Test
    void updatePhno_invalidNewNumber_isRejected_regardlessOfCallerTier() throws Exception {
        createUser(PHNO_A, AADHAR_A);
        String token = login(PHNO_A, PIN);

        // trusted caller (admin key)
        mockMvc.perform(asAdmin(put("/bank/updatephno")).param("phno", "" + PHNO_A).param("newphno", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Invalid mobile number"));

        // self-service (the customer's own token)
        mockMvc.perform(as(token, put("/bank/update-phone")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newPhno\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Invalid mobile number"));

        assertEquals(PHNO_A, bankRepository.findByphno(PHNO_A).getPhno());   // unchanged either way
    }

    @Test
    void deleteUser_removesTheAccount_butKeepsTheirTransactionHistory() throws Exception {
        // The ledger is a financial record: closing an account must not erase what it ever did.
        createUser(PHNO_A, AADHAR_A);
        depositByPhno(PHNO_A, "500");

        mockMvc.perform(asAdmin(delete("/bank/deleteuser")).param("phno", "" + PHNO_A))
                .andExpect(status().isOk());

        mockMvc.perform(asAdmin(get("/bank/displayuser")).param("phno", "" + PHNO_A))
                .andExpect(status().isNotFound());
        assertEquals(0, bankRepository.count());
        assertEquals(1, bankTransactionRepository.count());
        assertEquals(500.0, bankTransactionRepository.findAll().get(0).getBalance().doubleValue());
    }

    @Test
    void customerDeletingTheirOwnAccount_needsOnlyTheirOwnToken() throws Exception {
        createUser(PHNO_A, AADHAR_A);
        String token = login(PHNO_A, PIN);

        mockMvc.perform(as(token, delete("/bank/me"))).andExpect(status().isOk());

        assertEquals(0, bankRepository.count());
    }

    // ---------- session lifecycle: a phone number's meaning can change, an old token must not survive it ----------

    @Test
    void deletingAnAccount_invalidatesItsToken_soItCannotHijackWhoeverRegistersTheNumberNext() throws Exception {
        createUser(PHNO_A, AADHAR_A);
        String staleToken = login(PHNO_A, PIN);
        mockMvc.perform(as(staleToken, delete("/bank/me"))).andExpect(status().isOk());

        createUser(PHNO_A, AADHAR_B);   // someone else registers with the now-freed number

        mockMvc.perform(as(staleToken, get("/bank/me"))).andExpect(status().isUnauthorized());
        mockMvc.perform(as(staleToken, put("/bank/deposit")).contentType(MediaType.APPLICATION_JSON).content("{\"amount\":500}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void changingPhoneNumber_invalidatesTheOldToken() throws Exception {
        createUser(PHNO_A, AADHAR_A);
        String oldToken = login(PHNO_A, PIN);

        mockMvc.perform(as(oldToken, put("/bank/update-phone")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newPhno\":" + PHNO_B + "}"))
                .andExpect(status().isOk());

        mockMvc.perform(as(oldToken, get("/bank/me"))).andExpect(status().isUnauthorized());
    }

    @Test
    void adminResettingAPin_invalidatesAnySessionOpenedUnderTheOldOne() throws Exception {
        createUser(PHNO_A, AADHAR_A);
        String token = login(PHNO_A, PIN);

        mockMvc.perform(asAdmin(put("/bank/admin/set-pin")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phno\":" + PHNO_A + ",\"newPin\":\"5678\"}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(as(token, get("/bank/me"))).andExpect(status().isUnauthorized());
    }

    @Test
    void myTransactions_doesNotLeakThePreviousOwnersHistory_whenAPhoneNumberIsReused() throws Exception {
        createUser(PHNO_A, AADHAR_A);
        depositByPhno(PHNO_A, "500");
        String oldOwnerToken = login(PHNO_A, PIN);
        mockMvc.perform(as(oldOwnerToken, delete("/bank/me"))).andExpect(status().isOk());

        createUser(PHNO_A, AADHAR_B);   // a different customer now owns this number
        String newOwnerToken = login(PHNO_A, PIN);

        mockMvc.perform(as(newOwnerToken, get("/bank/my-transactions")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    // ---------- atomicity: a payment happens completely or not at all ----------

    @Test
    void withdraw_failingMidway_rollsBackEverything_andSendsNoNotification() throws Exception {
        createUser(PHNO_A, AADHAR_A);
        depositByPhno(PHNO_A, "1000");
        clearInvocations(bankKafkaProducer);
        doThrow(new RuntimeException("db down")).when(bankRepository).save(any(Bank.class));

        assertThrows(Exception.class, () ->
                mockMvc.perform(asAdmin(put("/bank/withdrawByphno")).param("phno", "" + PHNO_A).param("balance", "400")));

        assertMoney(1000.0, bankRepository.findByphno(PHNO_A).getBalance());
        assertEquals(1, bankTransactionRepository.count());            // only the earlier deposit
        verify(bankKafkaProducer, never()).sendMessage(any());
    }

    @Test
    void deposit_failingMidway_rollsBackEverything_andSendsNoNotification() throws Exception {
        createUser(PHNO_A, AADHAR_A);
        clearInvocations(bankKafkaProducer);
        doThrow(new RuntimeException("db down")).when(bankRepository).save(any(Bank.class));

        assertThrows(Exception.class, () ->
                mockMvc.perform(asAdmin(put("/bank/depositByphno")).param("phno", "" + PHNO_A).param("balance", "500")));

        assertMoney(0.0, bankRepository.findByphno(PHNO_A).getBalance());
        assertEquals(0, bankTransactionRepository.count());
        verify(bankKafkaProducer, never()).sendMessage(any());
    }

    @Test
    void deposit_whenKafkaIsDown_stillSucceeds() throws Exception {
        createUser(PHNO_A, AADHAR_A);
        doThrow(new RuntimeException("kafka down")).when(bankKafkaProducer).sendMessage(any());

        depositByPhno(PHNO_A, "500");

        balanceShouldBe(PHNO_A, 500.0);
        assertEquals(1, bankTransactionRepository.count());
    }

    @Test
    void withdraw_whenKafkaIsDown_stillSucceeds() throws Exception {
        createUser(PHNO_A, AADHAR_A);
        depositByPhno(PHNO_A, "1000");
        doThrow(new RuntimeException("kafka down")).when(bankKafkaProducer).sendMessage(any());

        mockMvc.perform(asAdmin(put("/bank/withdrawByphno")).param("phno", "" + PHNO_A).param("balance", "400"))
                .andExpect(status().isOk());

        balanceShouldBe(PHNO_A, 600.0);
        assertEquals(2, bankTransactionRepository.count());
    }

    // ---------- concurrency: many simultaneous requests must never corrupt money ----------
    // Which requests win is up to the scheduler, so these tests assert invariants that must hold for ANY outcome:
    // a request either fully succeeds (200) or is cleanly refused (400/409), and the books always add up.

    private List<Integer> fireSimultaneously(int requests, Callable<Integer> call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(requests);
        try {
            CountDownLatch ready = new CountDownLatch(requests);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return call.call();
                }));
            }
            ready.await();
            go.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : futures) {
                statuses.add(f.get(60, TimeUnit.SECONDS));
            }
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void simultaneousDeposits_neverLoseMoney_orReuseATransactionId() throws Exception {
        createUser(PHNO_A, AADHAR_A);

        List<Integer> statuses = fireSimultaneously(10, () -> mockMvc
                .perform(asAdmin(put("/bank/depositByphno")).param("phno", "" + PHNO_A).param("balance", "10"))
                .andReturn().getResponse().getStatus());

        long succeeded = statuses.stream().filter(s -> s == 200).count();
        assertTrue(statuses.stream().allMatch(s -> s == 200 || s == 409), "unexpected statuses: " + statuses);
        assertTrue(succeeded >= 1, "at least one deposit must win: " + statuses);
        // every deposit the client was told succeeded is in the balance, and nothing else is
        assertMoney(10.0 * succeeded, bankRepository.findByphno(PHNO_A).getBalance());
        assertEquals(succeeded, bankTransactionRepository.count());
        long distinctIds = bankTransactionRepository.findAll().stream().map(BankTransaction::getTransactionId).distinct().count();
        assertEquals(succeeded, distinctIds);
    }

    @Test
    void simultaneousWithdrawals_neverOverdrawTheAccount() throws Exception {
        createUser(PHNO_A, AADHAR_A);
        depositByPhno(PHNO_A, "1000");

        List<Integer> statuses = fireSimultaneously(10, () -> mockMvc
                .perform(asAdmin(put("/bank/withdrawByphno")).param("phno", "" + PHNO_A).param("balance", "300"))
                .andReturn().getResponse().getStatus());

        long succeeded = statuses.stream().filter(s -> s == 200).count();
        assertTrue(statuses.stream().allMatch(s -> s == 200 || s == 400 || s == 409), "unexpected statuses: " + statuses);
        assertTrue(succeeded <= 3, "1000 can fund at most three withdrawals of 300, but " + succeeded + " succeeded");
        BigDecimal balance = bankRepository.findByphno(PHNO_A).getBalance();
        assertTrue(balance.signum() >= 0, "account went negative: " + balance);
        assertMoney(1000.0 - 300.0 * succeeded, balance);
        assertEquals(1 + succeeded, bankTransactionRepository.count());   // the deposit + each successful withdrawal
    }

    // ---------- atomic transfer ----------

    @Test
    void transfer_movesMoneyAndRecordsBothLegs_inOneCall() throws Exception {
        createUser(PHNO_A, AADHAR_A);
        createUser(PHNO_B, AADHAR_B);
        depositByPhno(PHNO_A, "1000");

        mockMvc.perform(asAdmin(post("/bank/transfer")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payerPhno\":" + PHNO_A + ",\"receiverPhno\":" + PHNO_B
                                + ",\"amount\":250,\"idempotencyKey\":\"test-key-1\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string("Transfer Successful Amount Inr : 250.00"));

        balanceShouldBe(PHNO_A, 750.0);
        balanceShouldBe(PHNO_B, 250.0);
        mockMvc.perform(asAdmin(get("/bank/transactions")).param("phno", "" + PHNO_A))
                .andExpect(jsonPath("$.content[1].action").value("Debit"))
                .andExpect(jsonPath("$.content[1].amount").value(250.0));
        mockMvc.perform(asAdmin(get("/bank/transactions")).param("phno", "" + PHNO_B))
                .andExpect(jsonPath("$.content[0].action").value("Credit"))
                .andExpect(jsonPath("$.content[0].amount").value(250.0));
    }

    @Test
    void transfer_sameIdempotencyKeySentAgain_doesNotMoveMoneyASecondTime() throws Exception {
        createUser(PHNO_A, AADHAR_A);
        createUser(PHNO_B, AADHAR_B);
        depositByPhno(PHNO_A, "1000");
        String body = "{\"payerPhno\":" + PHNO_A + ",\"receiverPhno\":" + PHNO_B
                + ",\"amount\":250,\"idempotencyKey\":\"test-key-2\"}";

        // The first call's response never has to "arrive" for this to matter - simulate exactly that by just
        // sending the identical request a second time, the way a caller retrying after a timeout would.
        mockMvc.perform(asAdmin(post("/bank/transfer")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        mockMvc.perform(asAdmin(post("/bank/transfer")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(content().string("Transfer Successful Amount Inr : 250.00"));

        balanceShouldBe(PHNO_A, 750.0);   // 1000 - 250, not - 500
        balanceShouldBe(PHNO_B, 250.0);
        assertEquals(3, bankTransactionRepository.count());   // the deposit + exactly one debit + one credit
    }

    // A reused key with a DIFFERENT amount is not a retry of the first request - it must be rejected, not silently
    // reported as successful for money that was never actually moved as this second request asked.
    @Test
    void transfer_sameIdempotencyKeyWithADifferentAmount_isRejected_movesNoMoney() throws Exception {
        createUser(PHNO_A, AADHAR_A);
        createUser(PHNO_B, AADHAR_B);
        depositByPhno(PHNO_A, "1000");
        String firstBody = "{\"payerPhno\":" + PHNO_A + ",\"receiverPhno\":" + PHNO_B
                + ",\"amount\":250,\"idempotencyKey\":\"test-key-mismatch\"}";
        String secondBody = "{\"payerPhno\":" + PHNO_A + ",\"receiverPhno\":" + PHNO_B
                + ",\"amount\":500,\"idempotencyKey\":\"test-key-mismatch\"}";

        mockMvc.perform(asAdmin(post("/bank/transfer")).contentType(MediaType.APPLICATION_JSON).content(firstBody))
                .andExpect(status().isOk());
        mockMvc.perform(asAdmin(post("/bank/transfer")).contentType(MediaType.APPLICATION_JSON).content(secondBody))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("This idempotency key was already used for a different transfer request."));

        balanceShouldBe(PHNO_A, 750.0);   // only the first (250) transfer ever happened
        balanceShouldBe(PHNO_B, 250.0);
    }

    // The key is scoped per payer: two different customers reusing the identical key string (their own
    // key-generation schemes just happened to collide) are two unrelated transfers, not a conflict.
    @Test
    void transfer_sameIdempotencyKey_fromTwoDifferentPayers_bothSucceed_independently() throws Exception {
        createUser(PHNO_A, AADHAR_A);
        createUser(PHNO_B, AADHAR_B);
        depositByPhno(PHNO_A, "1000");
        depositByPhno(PHNO_B, "1000");

        mockMvc.perform(asAdmin(post("/bank/transfer")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payerPhno\":" + PHNO_A + ",\"receiverPhno\":" + PHNO_B
                                + ",\"amount\":250,\"idempotencyKey\":\"shared-key\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(asAdmin(post("/bank/transfer")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payerPhno\":" + PHNO_B + ",\"receiverPhno\":" + PHNO_A
                                + ",\"amount\":400,\"idempotencyKey\":\"shared-key\"}"))
                .andExpect(status().isOk());

        balanceShouldBe(PHNO_A, 1150.0);   // 1000 - 250 + 400
        balanceShouldBe(PHNO_B, 850.0);    // 1000 + 250 - 400
    }

    @Test
    void transfer_toUnknownReceiver_movesNoMoney() throws Exception {
        createUser(PHNO_A, AADHAR_A);
        depositByPhno(PHNO_A, "1000");

        mockMvc.perform(asAdmin(post("/bank/transfer")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payerPhno\":" + PHNO_A + ",\"receiverPhno\":9999999999,\"amount\":250,\"idempotencyKey\":\"test-key-3\"}"))
                .andExpect(status().isNotFound())
                .andExpect(content().string("Receiver not found"));

        balanceShouldBe(PHNO_A, 1000.0);
        assertEquals(1, bankTransactionRepository.count());   // only the deposit
    }

    @Test
    void transfer_insufficientFunds_movesNoMoney() throws Exception {
        createUser(PHNO_A, AADHAR_A);
        createUser(PHNO_B, AADHAR_B);
        depositByPhno(PHNO_A, "100");

        mockMvc.perform(asAdmin(post("/bank/transfer")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payerPhno\":" + PHNO_A + ",\"receiverPhno\":" + PHNO_B + ",\"amount\":500,\"idempotencyKey\":\"test-key-4\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Insufficient Funds"));

        balanceShouldBe(PHNO_A, 100.0);
        balanceShouldBe(PHNO_B, 0.0);
    }

    // The core guarantee this whole feature exists for: many simultaneous requests carrying the SAME idempotency
    // key must result in the transfer happening exactly once, never zero and never more than once.
    @Test
    void transfer_manySimultaneousRequestsWithTheSameKey_moveTheMoneyExactlyOnce() throws Exception {
        createUser(PHNO_A, AADHAR_A);
        createUser(PHNO_B, AADHAR_B);
        depositByPhno(PHNO_A, "1000");
        String body = "{\"payerPhno\":" + PHNO_A + ",\"receiverPhno\":" + PHNO_B
                + ",\"amount\":250,\"idempotencyKey\":\"race-key\"}";

        List<Integer> statuses = fireSimultaneously(10, () -> mockMvc
                .perform(asAdmin(post("/bank/transfer")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getStatus());

        assertTrue(statuses.stream().allMatch(s -> s == 200 || s == 409), "unexpected statuses: " + statuses);
        assertTrue(statuses.stream().anyMatch(s -> s == 200), "at least one request must succeed: " + statuses);
        balanceShouldBe(PHNO_A, 750.0);    // exactly one transfer's worth left, however many requests "succeeded"
        balanceShouldBe(PHNO_B, 250.0);
        assertEquals(3, bankTransactionRepository.count());   // the deposit + exactly one debit + one credit
    }

    // ---------- lookups ----------

    @Test
    void lookupByPhoneAndAccountNumber_findsTheUser() throws Exception {
        createUser(PHNO_A, AADHAR_A);

        mockMvc.perform(asAdmin(get("/bank/getByphno/{phno}", PHNO_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acno").value(FIRST_ACNO));
        mockMvc.perform(asAdmin(get("/bank/getByacno/{acno}", FIRST_ACNO)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phno").value(PHNO_A));
    }

    @Test
    void lookupByPhone_neverReturnsThePinHash() throws Exception {
        createUser(PHNO_A, AADHAR_A);

        String body = mockMvc.perform(asAdmin(get("/bank/getByphno/{phno}", PHNO_A)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(!body.contains("pinHash"), body);
    }

    // ---------- unknown users ----------

    @Test
    void lookupByPhone_forUnknownUser_returns404() throws Exception {
        mockMvc.perform(asAdmin(get("/bank/getByphno/{phno}", 9999999999L)))
                .andExpect(status().isNotFound())
                .andExpect(content().string("User not found"));
    }

    @Test
    void lookupByAccountNumber_forUnknownUser_returns404() throws Exception {
        mockMvc.perform(asAdmin(get("/bank/getByacno/{acno}", 1234L)))
                .andExpect(status().isNotFound())
                .andExpect(content().string("User not found"));
    }

    @Test
    void transactions_forUnknownUser_returns404NotA500() throws Exception {
        mockMvc.perform(asAdmin(get("/bank/transactions")).param("phno", "9999999999"))
                .andExpect(status().isNotFound())
                .andExpect(content().string("User not found"));
    }

    @Test
    void deposit_forUnknownUser_returns404() throws Exception {
        mockMvc.perform(asAdmin(put("/bank/depositByphno")).param("phno", "9999999999").param("balance", "10"))
                .andExpect(status().isNotFound())
                .andExpect(content().string("User not found"));
    }

    // ---------- the API is no longer open ----------

    @Test
    void trustedEndpoints_withNoKeyAtAll_return401_notTheRealData() throws Exception {
        createUser(PHNO_A, AADHAR_A);

        mockMvc.perform(get("/bank/all")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/bank/displayuser").param("phno", "" + PHNO_A)).andExpect(status().isUnauthorized());
        mockMvc.perform(put("/bank/depositByphno").param("phno", "" + PHNO_A).param("balance", "10")).andExpect(status().isUnauthorized());
    }

    @Test
    void serviceKey_worksJustLikeTheAdminKey_forPhonepayServicesThreeEndpoints() throws Exception {
        createUser(PHNO_A, AADHAR_A);

        mockMvc.perform(get("/bank/displayuser").param("phno", "" + PHNO_A).header("X-Service-Key", SERVICE_KEY))
                .andExpect(status().isOk());
        mockMvc.perform(put("/bank/withdrawByphno").param("phno", "" + PHNO_A).param("balance", "0.01").header("X-Service-Key", SERVICE_KEY))
                .andExpect(status().isBadRequest());   // no balance yet - proves the request reached the service, past auth
    }
}
