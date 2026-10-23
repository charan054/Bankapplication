package com.example.bankapplication.configuration;

import com.example.bankapplication.exception.TooManyRequestsException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.function.Function;

/**
 * Refuses a request once its key (see keyExtractor) has used up its budget in the current window. Two endpoints
 * guarded by two different RateLimiter instances never share a budget.
 */
public class RateLimitInterceptor implements HandlerInterceptor {
    private final RateLimiter limiter;
    private final Function<HttpServletRequest, String> keyExtractor;
    private final String message;
    private final java.util.function.Predicate<HttpServletRequest> exempt;

    public RateLimitInterceptor(RateLimiter limiter, Function<HttpServletRequest, String> keyExtractor, String message) {
        this(limiter, keyExtractor, message, request -> false);
    }

    /** exempt: requests that never count against the budget (e.g. a trusted backend authenticated by the service key). */
    public RateLimitInterceptor(RateLimiter limiter, Function<HttpServletRequest, String> keyExtractor, String message,
                                java.util.function.Predicate<HttpServletRequest> exempt) {
        this.exempt = exempt;
        this.limiter = limiter;
        this.keyExtractor = keyExtractor;
        this.message = message;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;   // browser pre-flight requests carry no credentials
        }
        if (exempt.test(request)) {
            return true;
        }
        if (!limiter.allow(keyExtractor.apply(request))) {
            throw new TooManyRequestsException(message);
        }
        return true;
    }
}
