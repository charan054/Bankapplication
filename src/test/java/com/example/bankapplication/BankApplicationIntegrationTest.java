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
// Login is rate-limited per address (see WebConfig); this suite legitimately logs the same few simulated
// customers in and out many times over its run, all from MockMvc's one default address, so it needs a much
// higher budget than production traffic from one real address would ever need.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@TestPropertySource(properties = "bank.login.rate-limit.max-attempts=1000")
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

    // The database lives for the whole test run, so start every test from an empty bank.
    @BeforeEach
    void emptyTheBank() {
        bankSessionRepository.deleteAll();
        bankTransactionRepository.deleteAll();
        bankRepository.deleteAll();
    }

    // ---------- helpers ----------

    private MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder request) {
        return request.header("X-Admin-Key", ADMIN_KEY);
    }

    private void createUser(long phno, long aadhar) throws Exception {
        String json = """
                {"firstName":"Charan","lastName":"Kumar","aadharNumber":%d,"phno":%d,"pin":"%s"}
                """.formatted(aadhar, phno, PIN);
        mockMvc.perform(post("/bank/save").contentType(MediaType.APPLICATION_JSON).content(json))
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
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].acno").value(FIRST_ACNO))
                .andExpect(jsonPath("$[1].acno").value(FIRST_ACNO + 1))
                .andExpect(jsonPath("$[0].name").value("KUMAR CHARAN"));
    }

    @Test
    void createUser_duplicatePhone_isRejected_andNothingExtraIsStored() throws Exception {
        createUser(PHNO_A, AADHAR_A);

        String duplicate = """
                {"firstName":"Other","lastName":"Person","aadharNumber":%d,"phno":%d,"pin":"%s"}
                """.formatted(AADHAR_B, PHNO_A, PIN);
        mockMvc.perform(post("/bank/save").contentType(MediaType.APPLICATION_JSON).content(duplicate))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("mobile number already exist"));

        assertEquals(1, bankRepository.count());
    }

    @Test
    void createUser_invalidMobile_isRejected() throws Exception {
        String bad = """
                {"firstName":"A","lastName":"B","aadharNumber":%d,"phno":5876543210,"pin":"%s"}
                """.formatted(AADHAR_A, PIN);
        mockMvc.perform(post("/bank/save").contentType(MediaType.APPLICATION_JSON).content(bad))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Invalid mobile number"));

        assertEquals(0, bankRepository.count());
    }

    @Test
    void createUser_pinIsHashed_neverStoredOrReturnedInPlainText() throws Exception {
        String response = mockMvc.perform(post("/bank/save").contentType(MediaType.APPLICATION_JSON)
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
                .andExpect(jsonPath("$.length()").value(2));
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
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].transactionId").value(100000))
                .andExpect(jsonPath("$[0].action").value("Credit"))
                .andExpect(jsonPath("$[0].amount").value(1000.0))
                .andExpect(jsonPath("$[0].balance").value(1000.0))
                .andExpect(jsonPath("$[1].transactionId").value(100001))
                .andExpect(jsonPath("$[1].action").value("Debit"))
                .andExpect(jsonPath("$[1].amount").value(400.0))
                .andExpect(jsonPath("$[1].balance").value(600.0));
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
                .andExpect(jsonPath("$[1].action").value("Debit"))
                .andExpect(jsonPath("$[1].amount").value(300.0))     // recorded as +300, not -300
                .andExpect(jsonPath("$[1].balance").value(700.0));
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
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].amount").value(100.0));
        mockMvc.perform(asAdmin(get("/bank/transactions")).param("phno", "" + PHNO_B))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].amount").value(900.0));
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
                .andExpect(status().isBadRequest())
                .andExpect(content().string("User not found"));
    }

    @Test
    void deleteUser_removesUserAndTheirTransactions() throws Exception {
        createUser(PHNO_A, AADHAR_A);
        depositByPhno(PHNO_A, "500");

        mockMvc.perform(asAdmin(delete("/bank/deleteuser")).param("phno", "" + PHNO_A))
                .andExpect(status().isOk());

        mockMvc.perform(asAdmin(get("/bank/displayuser")).param("phno", "" + PHNO_A))
                .andExpect(status().isBadRequest());
        assertEquals(0, bankRepository.count());
        assertEquals(0, bankTransactionRepository.count());
    }

    @Test
    void customerDeletingTheirOwnAccount_needsOnlyTheirOwnToken() throws Exception {
        createUser(PHNO_A, AADHAR_A);
        String token = login(PHNO_A, PIN);

        mockMvc.perform(as(token, delete("/bank/me"))).andExpect(status().isOk());

        assertEquals(0, bankRepository.count());
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
                .andExpect(jsonPath("$[1].action").value("Debit"))
                .andExpect(jsonPath("$[1].amount").value(250.0));
        mockMvc.perform(asAdmin(get("/bank/transactions")).param("phno", "" + PHNO_B))
                .andExpect(jsonPath("$[0].action").value("Credit"))
                .andExpect(jsonPath("$[0].amount").value(250.0));
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

    @Test
    void transfer_toUnknownReceiver_movesNoMoney() throws Exception {
        createUser(PHNO_A, AADHAR_A);
        depositByPhno(PHNO_A, "1000");

        mockMvc.perform(asAdmin(post("/bank/transfer")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payerPhno\":" + PHNO_A + ",\"receiverPhno\":9999999999,\"amount\":250,\"idempotencyKey\":\"test-key-3\"}"))
                .andExpect(status().isBadRequest())
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
    void lookupByPhone_forUnknownUser_returns400() throws Exception {
        mockMvc.perform(asAdmin(get("/bank/getByphno/{phno}", 9999999999L)))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("User not found"));
    }

    @Test
    void lookupByAccountNumber_forUnknownUser_returns400() throws Exception {
        mockMvc.perform(asAdmin(get("/bank/getByacno/{acno}", 1234L)))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("User not found"));
    }

    @Test
    void transactions_forUnknownUser_returns400NotA500() throws Exception {
        mockMvc.perform(asAdmin(get("/bank/transactions")).param("phno", "9999999999"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("User not found"));
    }

    @Test
    void deposit_forUnknownUser_returns400() throws Exception {
        mockMvc.perform(asAdmin(put("/bank/depositByphno")).param("phno", "9999999999").param("balance", "10"))
                .andExpect(status().isBadRequest())
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
