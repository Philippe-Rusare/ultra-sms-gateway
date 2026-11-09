package com.ultracoresoft.ultrasmsgateway.apikey;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

/** Answers 401 with an RFC 9457 problem document instead of a login redirect. */
public class ProblemAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final String BODY = """
            {"type":"about:blank","title":"Unauthorized","status":401,"detail":"Missing or invalid API key."}""";

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8);
        response.getWriter().write(BODY);
    }
}
