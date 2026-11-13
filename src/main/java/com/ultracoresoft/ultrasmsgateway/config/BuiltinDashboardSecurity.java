package com.ultracoresoft.ultrasmsgateway.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Dashboard login of the self-hosted gateway: one administrator, whose name and password come from the
 * environment.
 *
 * <p>Extension point: with {@code gateway.dashboard.login=external}, none of this is created. The embedding
 * application must then declare its own {@link SecurityFilterChain} for the dashboard (a chain without
 * {@code securityMatcher}, ordered after the API and device chains, i.e. {@code @Order(3)} or later) and
 * its own users. Without such a chain the dashboard would be left unprotected.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "gateway.dashboard.login", havingValue = "builtin", matchIfMissing = true)
class BuiltinDashboardSecurity {

    @Bean
    @Order(3)
    SecurityFilterChain dashboardChain(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs", "/v3/api-docs/**").permitAll()
                        .requestMatchers("/login", "/css/**", "/img/**", "/error").permitAll()
                        .anyRequest().hasRole("ADMIN"))
                .formLogin(form -> form.loginPage("/login").defaultSuccessUrl("/", true))
                .logout(logout -> logout.logoutSuccessUrl("/login?logout"))
                .build();
    }

    @Bean
    UserDetailsService adminUser(GatewayProperties properties, PasswordEncoder encoder) {
        GatewayProperties.Admin admin = properties.admin();
        if (admin.password() == null || admin.password().isBlank()) {
            throw new IllegalStateException(
                    "No dashboard password configured: set the GATEWAY_ADMIN_PASSWORD environment variable.");
        }
        return new InMemoryUserDetailsManager(User.withUsername(admin.username())
                .password(encoder.encode(admin.password()))
                .roles("ADMIN")
                .build());
    }
}
