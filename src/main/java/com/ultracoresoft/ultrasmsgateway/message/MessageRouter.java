package com.ultracoresoft.ultrasmsgateway.message;

import com.ultracoresoft.ultrasmsgateway.apikey.KeyDevices;
import com.ultracoresoft.ultrasmsgateway.device.Device;
import com.ultracoresoft.ultrasmsgateway.device.DeviceRepository;
import com.ultracoresoft.ultrasmsgateway.device.DeviceSessionRegistry;
import com.ultracoresoft.ultrasmsgateway.device.Network;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Which queued messages a phone may take: never one of a key tied to other phones; and, with routing by operator,
 * preferably the messages to its own network, leaving the others to a phone of their network.
 */
@Component
@RequiredArgsConstructor
class MessageRouter {

    private final KeyDevices keyDevices;
    private final RoutingService routing;
    private final DeviceRepository devices;
    private final DeviceSessionRegistry sessions;

    /** What the organization's phones are, read once for a claim. */
    record Plan(Map<UUID, Set<UUID>> ties, RoutingService.Settings routing, Map<UUID, Network> online, Map<UUID, Network> active) {
    }

    Plan plan(UUID organizationId) {
        RoutingService.Settings settings = routing.applied(organizationId);
        Map<UUID, Network> online = new HashMap<>();
        Map<UUID, Network> active = new HashMap<>();
        if (settings.enabled()) {
            for (Device device : devices.findAllByOrganizationIdOrderByCreatedAtDesc(organizationId)) {
                Optional<Network> network = Network.ofOperator(device.getOperatorName());
                if (device.isActive() && network.isPresent()) {
                    active.put(device.getId(), network.get());
                    if (sessions.isOnline(device.getId())) {
                        online.put(device.getId(), network.get());
                    }
                }
            }
        }
        return new Plan(keyDevices.applied(organizationId), settings, online, active);
    }

    boolean mayTake(Plan plan, Device device, SmsMessage message) {
        Set<UUID> tied = plan.ties().get(message.getApiKeyId());
        if (tied != null && !tied.contains(device.getId())) {
            return false;
        }
        if (!plan.routing().enabled()) {
            return true;
        }
        Optional<Network> wanted = Network.ofRecipient(message.getRecipient());
        if (wanted.isEmpty() || wanted.equals(Network.ofOperator(device.getOperatorName()))) {
            return true;
        }
        // Another network: left to a phone of the right one when the key may use it.
        Map<UUID, Network> candidates = plan.routing().fallback() == RoutingService.Fallback.WAIT ? plan.active() : plan.online();
        return candidates.entrySet().stream()
                .noneMatch(entry -> entry.getValue() == wanted.get() && (tied == null || tied.contains(entry.getKey())));
    }
}
