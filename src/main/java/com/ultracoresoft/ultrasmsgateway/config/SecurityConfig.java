package com.ultracoresoft.ultrasmsgateway.config;

import com.ultracoresoft.ultrasmsgateway.apikey.ApiKeyAuthenticationFilter;
import com.ultracoresoft.ultrasmsgateway.apikey.ApiKeyService;
import com.ultracoresoft.ultrasmsgateway.apikey.ProblemAuthenticationEntryPoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Three security zones:
 * <ol>
 *   <li>{@code /v1/messages/**}: client applications, stateless, authenticated by API key.</li>
 *   <li>{@code /v1/device/**}: Android devices; the WebSocket handshake checks the device token itself.</li>
 *   <li>Everything else: the dashboard. Its login is in {@link BuiltinDashboardSecurity}, which an embedding
 *       application can switch off to provide its own.</li>
 * </ol>
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
class SecurityConfig {

    @Bean
    @Order(1)
    SecurityFilterChain clientApiChain(HttpSecurity http, ApiKeyService apiKeys) throws Exception {
        ProblemAuthenticationEntryPoint entryPoint = new ProblemAuthenticationEntryPoint();
        return http
                .securityMatcher("/v1/messages/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .addFilterBefore(new ApiKeyAuthenticationFilter(apiKeys, entryPoint), UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(errors -> errors.authenticationEntryPoint(entryPoint))
                .build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain deviceChain(HttpSecurity http) throws Exception {
        return http
                .securityMatcher("/v1/device/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
