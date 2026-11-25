package com.ultracoresoft.ultrasmsgateway.dashboard;

import com.ultracoresoft.ultrasmsgateway.apikey.KeyDevices;
import com.ultracoresoft.ultrasmsgateway.device.Device;
import com.ultracoresoft.ultrasmsgateway.device.DeviceService;
import com.ultracoresoft.ultrasmsgateway.device.DeviceSessionRegistry;
import com.ultracoresoft.ultrasmsgateway.device.KeyDeviceRules;
import com.ultracoresoft.ultrasmsgateway.device.Network;
import com.ultracoresoft.ultrasmsgateway.message.RoutingService;
import com.ultracoresoft.ultrasmsgateway.organization.OrganizationContext;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The phones of each API key (Clés d'API) and routing by operator (Téléphones › Routage par opérateur). An edition
 * with plans limits both through KeyDeviceRules; without it, everything is open.
 */
@Controller
@RequiredArgsConstructor
class KeyPhonesController {

    private final OrganizationContext organization;
    private final DeviceService devices;
    private final DeviceSessionRegistry sessions;
    private final KeyDevices keyDevices;
    private final RoutingService routing;
    private final ObjectProvider<KeyDeviceRules> rules;
    private final Clock clock;

    /** A phone a key may be tied to: its network and whether it is online. */
    record PhoneChoice(UUID id, String name, String network, boolean online) {
    }

    /**
     * What the API keys page needs to tie keys to phones: the phones, the ties, and how many phones one key may have
     * ({@code keyPhones}: "many", "one", or "none" when keys use every phone).
     */
    static void addKeyPhones(Model model, UUID organizationId, DeviceService devices, DeviceSessionRegistry sessions, KeyDevices keyDevices) {
        List<PhoneChoice> phones = new ArrayList<>();
        for (Device device : devices.list(organizationId)) {
            if (device.isActive()) {
                phones.add(new PhoneChoice(device.getId(), device.getName(),
                        Network.ofOperator(device.getOperatorName()).map(Network::label).orElse(device.getOperatorName()),
                        sessions.isOnline(device.getId())));
            }
        }
        OptionalInt max = keyDevices.maxPerKey(organizationId);
        model.addAttribute("phones", phones);
        var ties = keyDevices.saved(organizationId);
        model.addAttribute("ties", ties);
        // Keys tied to phones none of which is online: their messages wait.
        model.addAttribute("offlineKeys", ties.entrySet().stream()
                .filter(entry -> entry.getValue().stream().noneMatch(sessions::isOnline))
                .map(java.util.Map.Entry::getKey).collect(java.util.stream.Collectors.toSet()));
        model.addAttribute("keyPhones", max.isEmpty() ? "many" : max.getAsInt() == 0 ? "none" : max.getAsInt() == 1 ? "one" : "many");
        model.addAttribute("maxPerKey", max.isPresent() ? Integer.valueOf(max.getAsInt()) : null);
        model.addAttribute("keyUpgradePath", keyDevices.upgradePath());
    }

    @PostMapping("/api-keys/{id}/devices")
    String keyDevices(@PathVariable UUID id, @RequestParam(required = false) List<UUID> devices, RedirectAttributes redirect) {
        try {
            keyDevices.set(organization.currentOrganizationId(), id, devices == null ? List.of() : devices);
            redirect.addFlashAttribute("noticeKey", "gw.keys.phones.saved");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("errorKey", "gw.keys.phones.error");
        }
        return "redirect:/api-keys";
    }

    /** A network of the routing page: its prefixes and the phones on it. */
    record NetworkRow(Network network, List<String> prefixes, List<PhoneChoice> phones) {
    }

    @GetMapping("/devices/routing")
    String routing(Model model) {
        UUID organizationId = organization.currentOrganizationId();
        List<NetworkRow> rows = new ArrayList<>();
        List<Device> active = devices.list(organizationId).stream().filter(Device::isActive).toList();
        for (Network network : Network.values()) {
            rows.add(new NetworkRow(network, network.prefixes(), active.stream()
                    .filter(device -> Network.ofOperator(device.getOperatorName()).filter(network::equals).isPresent())
                    .map(device -> new PhoneChoice(device.getId(), device.getName(), network.label(), sessions.isOnline(device.getId())))
                    .toList()));
        }
        model.addAttribute("networks", rows);
        model.addAttribute("settings", routing.settings(organizationId));
        model.addAttribute("allowed", routing.allowed(organizationId));
        model.addAttribute("upgradePath", rules.stream().findFirst().flatMap(KeyDeviceRules::upgradePath).orElse(null));
        model.addAttribute("stats", routing.stats(organizationId,
                clock.instant().atZone(ZoneOffset.UTC).withDayOfMonth(1).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant()));
        return "routing";
    }

    @PostMapping("/devices/routing")
    String saveRouting(@RequestParam(defaultValue = "false") boolean enabled,
                       @RequestParam(defaultValue = "ANY") RoutingService.Fallback fallback, RedirectAttributes redirect) {
        UUID organizationId = organization.currentOrganizationId();
        if (!routing.allowed(organizationId)) {
            return "redirect:/devices/routing";
        }
        routing.save(organizationId, new RoutingService.Settings(enabled, fallback));
        redirect.addFlashAttribute("noticeKey", "gw.routing.saved");
        return "redirect:/devices/routing";
    }

}
