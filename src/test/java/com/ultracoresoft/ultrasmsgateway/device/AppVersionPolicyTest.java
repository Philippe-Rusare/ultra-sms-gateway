package com.ultracoresoft.ultrasmsgateway.device;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AppVersionPolicyTest {

    @Test
    void versionsAreComparedNumberByNumber() {
        assertThat(AppVersionPolicy.compare("1.10.0", "1.9.2")).isPositive();
        assertThat(AppVersionPolicy.compare("1.2", "1.2.0")).isZero();
        assertThat(AppVersionPolicy.compare("1.2.0-debug", "1.2.0")).isZero();
        assertThat(AppVersionPolicy.compare("0.9.9", "1.0.0")).isNegative();
    }

    @Test
    void withoutAMinimumEveryVersionIsAccepted() {
        assertThat(new AppVersionPolicy("").isTooOld("0.0.1")).isFalse();
    }

    @Test
    void onlyAKnownOlderVersionIsTooOld() {
        AppVersionPolicy policy = new AppVersionPolicy("1.2.0");
        assertThat(policy.isTooOld("1.1.9")).isTrue();
        assertThat(policy.isTooOld("1.2.0")).isFalse();
        assertThat(policy.isTooOld("2.0.0")).isFalse();
        // A phone that does not say its version cannot be judged.
        assertThat(policy.isTooOld(null)).isFalse();
        assertThat(policy.isTooOld(" ")).isFalse();
    }
}
