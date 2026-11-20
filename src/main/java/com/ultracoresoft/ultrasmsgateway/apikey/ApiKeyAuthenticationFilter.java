package com.ultracoresoft.ultrasmsgateway.apikey;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Reads the API key from {@code X-Api-Key} or {@code Authorization: Bearer}. Not a Spring bean on purpose,
 * so that it only runs inside the client API security chain.
 */
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    public static final String API_KEY_HEADER = "X-Api-Key";
    private static final String BEARER = "Bearer ";

    private final ApiKeyService apiKeys;
    private final AuthenticationEntryPoint entryPoint;
    private final java.util.function.Predicate<java.util.UUID> suspended;

    public ApiKeyAuthenticationFilter(ApiKeyService apiKeys, AuthenticationEntryPoint entryPoint) {
        this(apiKeys, entryPoint, organizationId -> false);
    }

    /** {@code suspended} tells whether an organization is suspended: its keys are then refused with 403. */
    public ApiKeyAuthenticationFilter(ApiKeyService apiKeys, AuthenticationEntryPoint entryPoint,
                                      java.util.function.Predicate<java.util.UUID> suspended) {
        this.apiKeys = apiKeys;
        this.entryPoint = entryPoint;
        this.suspended = suspended;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String secret = extract(request);
        if (secret == null) {
            chain.doFilter(request, response);
            return;
        }
        Optional<ApiKeyPrincipal> principal = apiKeys.authenticate(secret);
        if (principal.isEmpty()) {
            SecurityContextHolder.clearContext();
            entryPoint.commence(request, response, new BadCredentialsException("Invalid API key"));
            return;
        }
        if (suspended.test(principal.get().organizationId())) {
            SecurityContextHolder.clearContext();
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/problem+json");
            response.getWriter().write("""
                    {"type":"about:blank","title":"Forbidden","status":403,"detail":"This organization is suspended."}""");
            return;
        }
        UsernamePasswordAuthenticationToken authentication = UsernamePasswordAuthenticationToken.authenticated(
                principal.get(), null, List.of(new SimpleGrantedAuthority("ROLE_API_CLIENT")));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        chain.doFilter(request, response);
    }

    private static String extract(HttpServletRequest request) {
        String header = request.getHeader(API_KEY_HEADER);
        if (header != null && !header.isBlank()) {
            return header.trim();
        }
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization != null && authorization.startsWith(BEARER)) {
            return authorization.substring(BEARER.length()).trim();
        }
        return null;
    }
}
