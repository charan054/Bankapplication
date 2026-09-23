package com.example.bankapplication.configuration;

import com.example.bankapplication.service.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Runs before every self-service endpoint (deposit, withdraw, my profile, ...). Identifies the caller from their
 * OWN token and hands their phone number to the controller as a request attribute; self-service endpoints never
 * take a phno from the request itself, so one customer can never act on another's account.
 */
public class CustomerAuthInterceptor implements HandlerInterceptor {
    public static final String AUTHENTICATED_PHNO = "authenticatedPhno";

    private final SessionService sessions;

    public CustomerAuthInterceptor(SessionService sessions) {
        this.sessions = sessions;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;   // browser pre-flight requests carry no credentials
        }
        long phno = sessions.authenticate(bearerToken(request.getHeader("Authorization")));
        request.setAttribute(AUTHENTICATED_PHNO, phno);
        return true;
    }

    /** "Bearer abc" gives "abc"; anything else gives null. */
    public static String bearerToken(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        return authorizationHeader.substring(7).trim();
    }
}
