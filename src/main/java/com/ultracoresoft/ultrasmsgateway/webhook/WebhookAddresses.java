package com.ultracoresoft.ultrasmsgateway.webhook;

import com.ultracoresoft.ultrasmsgateway.config.GatewayProperties;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Which addresses may receive webhooks. The form is checked when an address is saved; the network is checked
 * before every request, because a name can point somewhere else later.
 */
@Component
public class WebhookAddresses {

    static final int MAX_LENGTH = 500;

    private final GatewayProperties.Webhooks settings;

    public WebhookAddresses(GatewayProperties properties) {
        this.settings = properties.webhooks();
    }

    public enum Problem { INVALID, HTTPS_REQUIRED, PRIVATE_NETWORK }

    /** A problem with the form of the address, or empty when it may be saved. */
    public Optional<Problem> checkForm(String url) {
        URI uri;
        try {
            uri = url == null || url.isBlank() || url.length() > MAX_LENGTH ? null : URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            uri = null;
        }
        if (uri == null || uri.getHost() == null || uri.getUserInfo() != null || uri.getScheme() == null) {
            return Optional.of(Problem.INVALID);
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http")) {
            return Optional.of(Problem.INVALID);
        }
        if (settings.requireHttps() && !scheme.equals("https")) {
            return Optional.of(Problem.HTTPS_REQUIRED);
        }
        if (!settings.allowPrivateNetworks() && isLiteralPrivate(uri.getHost())) {
            return Optional.of(Problem.PRIVATE_NETWORK);
        }
        return Optional.empty();
    }

    /** Whether a request may go to this address now: its name must not lead to a private network, unless allowed. */
    public boolean mayReach(URI uri) {
        if (settings.allowPrivateNetworks()) {
            return true;
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                if (isPrivate(address)) {
                    return false;
                }
            }
            return true;
        } catch (UnknownHostException e) {
            return false;
        }
    }

    private static boolean isLiteralPrivate(String host) {
        String name = host.toLowerCase(Locale.ROOT);
        if (name.equals("localhost") || name.endsWith(".localhost") || name.endsWith(".local") || name.endsWith(".internal")) {
            return true;
        }
        // Only addresses written as numbers are checked here: names are resolved before each request.
        if (name.matches("[0-9.]+") || name.startsWith("[")) {
            try {
                return isPrivate(InetAddress.getByName(name.replace("[", "").replace("]", "")));
            } catch (UnknownHostException e) {
                return true;
            }
        }
        return false;
    }

    static boolean isPrivate(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            // 100.64.0.0/10 (carrier-grade NAT) and 0.0.0.0/8.
            return (first == 100 && second >= 64 && second <= 127) || first == 0;
        }
        // fc00::/7, unique local IPv6 addresses.
        return (bytes[0] & 0xfe) == 0xfc;
    }
}
