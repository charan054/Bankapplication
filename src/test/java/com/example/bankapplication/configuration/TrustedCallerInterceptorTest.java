package com.example.bankapplication.configuration;

import com.example.bankapplication.exception.UnauthorizedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TrustedCallerInterceptorTest {

    private static final String ADMIN_KEY = "the-real-admin-key";
    private static final String SERVICE_KEY = "the-real-service-key";

    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private final HttpServletResponse response = mock(HttpServletResponse.class);
    private final TrustedCallerInterceptor interceptor = new TrustedCallerInterceptor(ADMIN_KEY, SERVICE_KEY);

    @Test
    void correctAdminKey_passesThrough() {
        when(request.getHeader("X-Admin-Key")).thenReturn(ADMIN_KEY);

        assertTrue(interceptor.preHandle(request, response, new Object()));
    }

    @Test
    void correctServiceKey_passesThrough() {
        when(request.getHeader("X-Service-Key")).thenReturn(SERVICE_KEY);

        assertTrue(interceptor.preHandle(request, response, new Object()));
    }

    @Test
    void wrongKey_isRejected() {
        when(request.getHeader("X-Admin-Key")).thenReturn("guessed-key");

        assertThrows(UnauthorizedException.class, () -> interceptor.preHandle(request, response, new Object()));
    }

    @Test
    void noKeyAtAll_isRejected() {
        assertThrows(UnauthorizedException.class, () -> interceptor.preHandle(request, response, new Object()));
    }

    @Test
    void emptyKey_isRejected() {
        when(request.getHeader("X-Admin-Key")).thenReturn("");

        assertThrows(UnauthorizedException.class, () -> interceptor.preHandle(request, response, new Object()));
    }

    // A key one character short of the real one must fail exactly like any other wrong key - this is really a
    // regression guard for the underlying MessageDigest.isEqual comparison, which (unlike String.equals) must
    // still work correctly for differing lengths, not just differing content of the same length.
    @Test
    void keyOfADifferentLength_isRejected() {
        when(request.getHeader("X-Admin-Key")).thenReturn(ADMIN_KEY.substring(0, ADMIN_KEY.length() - 1));

        assertThrows(UnauthorizedException.class, () -> interceptor.preHandle(request, response, new Object()));
    }

    @Test
    void anOptionsPreflight_isNeverChecked() {
        when(request.getMethod()).thenReturn("OPTIONS");

        assertTrue(interceptor.preHandle(request, response, new Object()));
    }
}
