package com.ultracoresoft.ultrasmsgateway.device;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The oldest version of the Android app the gateway still accepts ({@code gateway.devices.minimum-app-version},
 * {@code GATEWAY_MINIMUM_APP_VERSION}). Empty by default: every version is accepted.
 *
 * <p>Phones do not all update at once, so the gateway keeps accepting older versions; this minimum is only raised
 * when an old version can no longer work. A phone below it is refused with "update required" and the app asks to
 * update from Google Play. A phone that does not say its version (before 1.0.0) is accepted: it cannot be judged.
 */
@Component
public class AppVersionPolicy {

    private final String minimum;

    public AppVersionPolicy(@Value("${gateway.devices.minimum-app-version:}") String minimum) {
        this.minimum = minimum == null ? "" : minimum.trim();
    }

    /** The minimum version, or an empty string when there is none. */
    public String minimum() {
        return minimum;
    }

    public boolean isTooOld(String appVersion) {
        return !minimum.isEmpty() && appVersion != null && !appVersion.isBlank() && compare(appVersion, minimum) < 0;
    }

    /**
     * Compares two versions number by number: 1.10.0 comes after 1.9.2. What follows the numbers ({@code -debug},
     * {@code -beta}) is ignored, and a missing number counts as 0 (1.2 is 1.2.0).
     */
    static int compare(String a, String b) {
        int[] left = numbers(a);
        int[] right = numbers(b);
        for (int i = 0; i < Math.max(left.length, right.length); i++) {
            int x = i < left.length ? left[i] : 0;
            int y = i < right.length ? right[i] : 0;
            if (x != y) {
                return Integer.compare(x, y);
            }
        }
        return 0;
    }

    private static int[] numbers(String version) {
        String plain = version.trim().split("[^0-9.]", 2)[0];
        return java.util.Arrays.stream(plain.split("\\."))
                .filter(part -> !part.isEmpty())
                .mapToInt(part -> part.length() > 9 ? Integer.MAX_VALUE : Integer.parseInt(part))
                .toArray();
    }
}
