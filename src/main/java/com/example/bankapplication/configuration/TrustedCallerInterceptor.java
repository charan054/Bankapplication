package com.example.bankapplication.configuration;

import com.example.bankapplication.exception.UnauthorizedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Guards the endpoints that act on ANY account by phone/account number: viewing all users, deposit/withdraw by
 * account number, deleting a user, resetting a PIN, and the exact three endpoints PhonepayService calls
 * (displayuser, withdrawByphno, depositByphno).
 * <p>
 * Two DIFFERENT secrets satisfy this check, on purpose: the admin key (used by bank.html, a value typed into a
 * browser and kept in localStorage) and the service key (used only by trusted backend services over Feign). A leak
 * of the browser-facing admin key must not also compromise the PhonepayService integration, and vice versa.
 */
public class TrustedCallerInterceptor implements HandlerInterceptor {
    private final String adminApiKey;
    private final String serviceApiKey;

    public TrustedCallerInterceptor(String adminApiKey, String serviceApiKey) {
        this.adminApiKey = adminApiKey;
        this.serviceApiKey = serviceApiKey;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String admin = request.getHeader("X-Admin-Key");
        String service = request.getHeader("X-Service-Key");
        if (matches(admin, adminApiKey) || matches(service, serviceApiKey)) {
            return true;
        }
        throw new UnauthorizedException("A valid X-Admin-Key or X-Service-Key header is required for this operation.");
    }

    private boolean matches(String provided, String expected) {
        return provided != null && !provided.isEmpty() && provided.equals(expected);
    }
}
