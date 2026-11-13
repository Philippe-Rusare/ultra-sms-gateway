package com.ultracoresoft.ultrasmsgateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

/** An embedding application can switch the built-in dashboard login off and provide its own. */
@SpringBootTest(properties = {"gateway.dashboard.login=external", "gateway.admin.password="})
@ActiveProfiles("test")
class ExternalDashboardLoginTest {

    @Autowired
    ApplicationContext context;

    @Test
    void builtinLoginIsNotCreatedAndNoAdminPasswordIsRequired() {
        assertThat(context.containsBean("dashboardChain")).isFalse();
        assertThat(context.containsBean("adminUser")).isFalse();
    }

    @Test
    void apiAndDeviceChainsStayInPlace() {
        assertThat(context.containsBean("clientApiChain")).isTrue();
        assertThat(context.containsBean("deviceChain")).isTrue();
        assertThat(context.containsBean("passwordEncoder")).isTrue();
    }
}
