package com.ultracoresoft.ultrasmsgateway.dashboard;

import com.ultracoresoft.ultrasmsgateway.web.PageSizes;
import com.ultracoresoft.ultrasmsgateway.apikey.ApiKey;
import com.ultracoresoft.ultrasmsgateway.apikey.ApiKeyService;
import com.ultracoresoft.ultrasmsgateway.common.Issued;
import com.ultracoresoft.ultrasmsgateway.device.Device;
import com.ultracoresoft.ultrasmsgateway.device.DevicePairing;
import com.ultracoresoft.ultrasmsgateway.device.DevicePairingService;
import com.ultracoresoft.ultrasmsgateway.device.DeviceLimitReachedException;
import com.ultracoresoft.ultrasmsgateway.device.DeviceService;
import com.ultracoresoft.ultrasmsgateway.device.DeviceSessionRegistry;
import com.ultracoresoft.ultrasmsgateway.message.MessageEventRepository;
import com.ultracoresoft.ultrasmsgateway.message.MessageStatus;
import com.ultracoresoft.ultrasmsgateway.message.SmsMessage;
import com.ultracoresoft.ultrasmsgateway.message.SmsMessageRepository;
import com.ultracoresoft.ultrasmsgateway.organization.OrganizationContext;
import jakarta.persistence.criteria.Predicate;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** Administration pages: audit of messages, devices and API keys, always within the current organization. */
@Controller
@RequiredArgsConstructor
class DashboardController {

    private static final int PAGE_SIZE = PageSizes.DEFAULT;
    /** Messages of the live activity on the overview. */
    static final int RECENT_SIZE = 4;
    /** Devices and API keys per page. */
    static final int LIST_PAGE_SIZE = PageSizes.DEFAULT;
    private static final int CHART_DAYS = 14;
    private static final int MAX_NAME_LENGTH = 100;
    /** Link encoded in the QR code and understood by the Android app. See docs/device-protocol.md. */
    static final String PAIRING_SCHEME = "ultrasms://pair";

    private final SmsMessageRepository messages;
    private final MessageEventRepository events;
    private final DeviceService devices;
    private final DeviceSessionRegistry sessions;
    private final ApiKeyService apiKeys;
    private final Clock clock;
    private final Formats formats;
    private final OrganizationContext organization;
    private final DevicePairingService pairings;
    private final com.ultracoresoft.ultrasmsgateway.apikey.KeyDevices keyDevices;

    /** A phone of the list; {@code paused} when it is past the phones its organization's plan allows. */
    record DeviceView(Device device, boolean online, boolean paused) {
    }

    @GetMapping("/login")
    String login() {
        return "login";
    }

    @GetMapping("/")
    String overview(Model model, Locale locale) {
        Map<MessageStatus, Long> counts = new EnumMap<>(MessageStatus.class);
        for (MessageStatus status : MessageStatus.values()) {
            counts.put(status, 0L);
        }
        UUID organizationId = organization.currentOrganizationId();
        messages.countByStatusSince(organizationId, clock.instant().minus(Duration.ofHours(24)))
                .forEach(row -> counts.put(row.getStatus(), row.getTotal()));
        model.addAttribute("counts", counts);
        model.addAttribute("total24h", counts.values().stream().mapToLong(Long::longValue).sum());
        model.addAttribute("queuedNow", messages.countByOrganizationIdAndStatus(organizationId, MessageStatus.QUEUED));
        // The counters of the phone app: sent (sent or delivered), waiting, failed (failed or expired).
        model.addAttribute("sent24h", counts.get(MessageStatus.SENT) + counts.get(MessageStatus.DELIVERED));
        model.addAttribute("waiting", counts.get(MessageStatus.QUEUED) + counts.get(MessageStatus.DISPATCHED) + counts.get(MessageStatus.ACCEPTED));
        model.addAttribute("failed24h", counts.get(MessageStatus.FAILED) + counts.get(MessageStatus.EXPIRED));
        List<DeviceView> deviceList = deviceViews();
        List<DeviceView> activeDevices = deviceList.stream().filter(view -> view.device().isActive()).toList();
        model.addAttribute("devices", activeDevices);
        model.addAttribute("onlineCount", activeDevices.stream().filter(DeviceView::online).count());
        // How many phones the organization's plan allows, next to how many it has (no limit: nothing shown).
        model.addAttribute("allowance", devices.allowance(organization.currentOrganizationId()));
        // The last messages, as the live activity of the app.
        List<SmsMessage> recent = messages.findAll(filter(organizationId, null, ""),
                PageRequest.of(0, RECENT_SIZE, Sort.by(Sort.Direction.DESC, "createdAt"))).getContent();
        model.addAttribute("recent", recent);
        model.addAttribute("lastMessageAt", recent.isEmpty() ? null : recent.get(0).getCreatedAt());
        model.addAttribute("deviceNames", deviceNames());

        LocalDate today = LocalDate.now(clock.withZone(formats.zoneId()));
        Instant chartStart = today.minusDays(CHART_DAYS - 1L).atStartOfDay(formats.zoneId()).toInstant();
        OverviewChart chart = OverviewChart.build(
                messages.findCreatedStatusSince(organizationId, chartStart), today, formats.zoneId(), CHART_DAYS, locale);
        model.addAttribute("chart", chart);
        model.addAttribute("chartSeries", OverviewChart.Series.values());
        model.addAttribute("successRate", successRate(chart.bars().subList(CHART_DAYS - 7, CHART_DAYS)));
        return "overview";
    }

    /** Share of finished messages that left the phone (sent or delivered), or null without any. */
    static Integer successRate(List<OverviewChart.Bar> days) {
        long ok = 0;
        long finished = 0;
        for (OverviewChart.Bar day : days) {
            ok += day.sent() + day.delivered();
            finished += day.total();
        }
        return finished == 0 ? null : (int) Math.round(100.0 * ok / finished);
    }

    @GetMapping("/messages")
    String messageList(@RequestParam(required = false) MessageStatus status,
                       @RequestParam(required = false) String recipient,
                       @RequestParam(defaultValue = "0") int page,
                       @RequestParam(required = false) Integer size,
                       Model model) {
        String recipientFilter = recipient == null ? "" : recipient.replaceAll("[^0-9+]", "");
        Page<SmsMessage> result = messages.findAll(filter(organization.currentOrganizationId(), status, recipientFilter),
                PageSizes.request(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
        model.addAttribute("page", result);
        model.addAttribute("statuses", MessageStatus.values());
        model.addAttribute("status", status);
        model.addAttribute("recipient", recipientFilter);
        model.addAttribute("apiKeyNames", apiKeyNames());
        model.addAttribute("deviceNames", deviceNames());
        return "messages";
    }

    @GetMapping("/messages/{id}")
    String messageDetail(@PathVariable UUID id, Model model) {
        SmsMessage message = messages.findByIdAndOrganizationId(id, organization.currentOrganizationId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("message", message);
        model.addAttribute("events", events.findByMessageIdOrderByIdAsc(id));
        model.addAttribute("apiKeyNames", apiKeyNames());
        model.addAttribute("deviceNames", deviceNames());
        return "message";
    }

    @GetMapping("/devices")
    String deviceList(@RequestParam(defaultValue = "0") int page, @RequestParam(required = false) Integer size, Model model) {
        UUID organizationId = organization.currentOrganizationId();
        Page<Device> result = devices.page(organizationId, PageSizes.request(page, size));
        DeviceService.Allowance allowance = devices.allowance(organizationId);
        java.util.Set<UUID> receiving = allowance.limit() == null ? null : devices.receiving(organizationId);
        model.addAttribute("allowance", allowance);
        model.addAttribute("keysByDevice", keyDevices.keysByDevice(organizationId));
        model.addAttribute("page", result.map(device -> new DeviceView(device, sessions.isOnline(device.getId()),
                receiving != null && device.isActive() && !receiving.contains(device.getId()))));
        model.addAttribute("serverUrl", ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/v1/device/ws").toUriString().replaceFirst("^http", "ws"));
        return "devices";
    }

    @PostMapping("/devices")
    String createDevice(@RequestParam String name, RedirectAttributes redirect) {
        if (!validName(name, redirect)) {
            return "redirect:/devices";
        }
        Issued<Device> issued;
        try {
            issued = devices.create(organization.currentOrganizationId(), name);
        } catch (DeviceLimitReachedException e) {
            redirect.addFlashAttribute("errorKey", "gw.error.device.limit");
            return "redirect:/devices";
        }
        redirect.addFlashAttribute("issuedName", issued.value().getName());
        redirect.addFlashAttribute("issuedSecret", issued.secret());
        return "redirect:/devices";
    }

    /** Starts a pairing and shows its QR code once: the code is not stored, so the page cannot be reopened. */
    @PostMapping("/devices/pair")
    String startPairing(@RequestParam String name, RedirectAttributes redirect) {
        if (!validName(name, redirect)) {
            return "redirect:/devices";
        }
        Issued<DevicePairing> issued;
        try {
            issued = pairings.start(organization.currentOrganizationId(), name);
        } catch (DeviceLimitReachedException e) {
            redirect.addFlashAttribute("errorKey", "gw.error.device.limit");
            return "redirect:/devices";
        }
        String link = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
        redirect.addFlashAttribute("pairingLink", PAIRING_SCHEME + "?server="
                + URLEncoder.encode(link, StandardCharsets.UTF_8) + "&code=" + issued.secret());
        return "redirect:/devices/pair/" + issued.value().getId();
    }

    @GetMapping("/devices/pair/{id}")
    String pairingPage(@PathVariable UUID id, Model model) {
        DevicePairing pairing = pairings.find(organization.currentOrganizationId(), id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("pairing", pairing);
        model.addAttribute("status", pairing.statusAt(clock.instant()));
        if (model.getAttribute("pairingLink") instanceof String link) {
            model.addAttribute("qr", QrCode.of(link));
        }
        return "device-pairing";
    }

    /** Polled by the pairing page to know when the phone has scanned the code. */
    @GetMapping("/devices/pair/{id}/status")
    @ResponseBody
    Map<String, String> pairingStatus(@PathVariable UUID id) {
        DevicePairing pairing = pairings.find(organization.currentOrganizationId(), id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return Map.of("status", pairing.statusAt(clock.instant()).name());
    }

    @PostMapping("/devices/{id}/rename")
    String renameDevice(@PathVariable UUID id, @RequestParam String name, RedirectAttributes redirect) {
        if (validName(name, redirect)) {
            devices.rename(organization.currentOrganizationId(), id, name);
            redirect.addFlashAttribute("noticeKey", "gw.notice.renamed");
        }
        return "redirect:/devices";
    }

    @PostMapping("/devices/{id}/revoke")
    String revokeDevice(@PathVariable UUID id, RedirectAttributes redirect) {
        devices.revoke(organization.currentOrganizationId(), id);
        redirect.addFlashAttribute("noticeKey", "gw.notice.device.revoked");
        return "redirect:/devices";
    }

    @GetMapping("/api-keys")
    String apiKeyList(@RequestParam(defaultValue = "0") int page, @RequestParam(required = false) Integer size, Model model) {
        UUID organizationId = organization.currentOrganizationId();
        model.addAttribute("page", apiKeys.page(organizationId, PageSizes.request(page, size)));
        KeyPhonesController.addKeyPhones(model, organizationId, devices, sessions, keyDevices);
        return "api-keys";
    }

    @PostMapping("/api-keys")
    String createApiKey(@RequestParam String name, @RequestParam(required = false) List<UUID> devices,
                        RedirectAttributes redirect) {
        if (!validName(name, redirect)) {
            return "redirect:/api-keys";
        }
        Issued<ApiKey> issued = apiKeys.create(organization.currentOrganizationId(), name);
        if (devices != null && !devices.isEmpty()) {
            // The phones of the key: refused past the plan's limit, the key then uses every phone.
            try {
                keyDevices.set(organization.currentOrganizationId(), issued.value().getId(), devices);
            } catch (IllegalArgumentException e) {
                redirect.addFlashAttribute("errorKey", "gw.keys.phones.error");
            }
        }
        redirect.addFlashAttribute("issuedName", issued.value().getName());
        redirect.addFlashAttribute("issuedSecret", issued.secret());
        return "redirect:/api-keys";
    }

    @PostMapping("/api-keys/{id}/rename")
    String renameApiKey(@PathVariable UUID id, @RequestParam String name, RedirectAttributes redirect) {
        if (validName(name, redirect)) {
            apiKeys.rename(organization.currentOrganizationId(), id, name);
            redirect.addFlashAttribute("noticeKey", "gw.notice.renamed");
        }
        return "redirect:/api-keys";
    }

    @PostMapping("/api-keys/{id}/revoke")
    String revokeApiKey(@PathVariable UUID id, RedirectAttributes redirect) {
        apiKeys.revoke(organization.currentOrganizationId(), id);
        redirect.addFlashAttribute("noticeKey", "gw.notice.key.revoked");
        return "redirect:/api-keys";
    }

    private static boolean validName(String name, RedirectAttributes redirect) {
        if (name == null || name.isBlank() || name.trim().length() > MAX_NAME_LENGTH) {
            redirect.addFlashAttribute("errorKey", "gw.error.name");
            return false;
        }
        return true;
    }

    private static Specification<SmsMessage> filter(UUID organizationId, MessageStatus status, String recipient) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(builder.equal(root.get("organizationId"), organizationId));
            if (status != null) {
                predicates.add(builder.equal(root.get("status"), status));
            }
            if (!recipient.isEmpty()) {
                predicates.add(builder.like(root.get("recipient"), "%" + recipient + "%"));
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        };
    }

    private List<DeviceView> deviceViews() {
        UUID organizationId = organization.currentOrganizationId();
        java.util.Set<UUID> receiving = devices.allowance(organizationId).limit() == null ? null : devices.receiving(organizationId);
        return devices.list(organizationId).stream()
                .map(device -> new DeviceView(device, sessions.isOnline(device.getId()),
                        receiving != null && device.isActive() && !receiving.contains(device.getId())))
                .toList();
    }

    private Map<UUID, String> apiKeyNames() {
        return apiKeys.list(organization.currentOrganizationId()).stream()
                .collect(Collectors.toMap(ApiKey::getId, ApiKey::getName));
    }

    private Map<UUID, String> deviceNames() {
        return devices.list(organization.currentOrganizationId()).stream()
                .collect(Collectors.toMap(Device::getId, Device::getName, (a, b) -> a));
    }
}
