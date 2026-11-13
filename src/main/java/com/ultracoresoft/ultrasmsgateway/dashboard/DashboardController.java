package com.ultracoresoft.ultrasmsgateway.dashboard;

import com.ultracoresoft.ultrasmsgateway.apikey.ApiKey;
import com.ultracoresoft.ultrasmsgateway.apikey.ApiKeyService;
import com.ultracoresoft.ultrasmsgateway.common.Issued;
import com.ultracoresoft.ultrasmsgateway.device.Device;
import com.ultracoresoft.ultrasmsgateway.device.DeviceService;
import com.ultracoresoft.ultrasmsgateway.device.DeviceSessionRegistry;
import com.ultracoresoft.ultrasmsgateway.message.MessageEventRepository;
import com.ultracoresoft.ultrasmsgateway.message.MessageStatus;
import com.ultracoresoft.ultrasmsgateway.message.SmsMessage;
import com.ultracoresoft.ultrasmsgateway.message.SmsMessageRepository;
import com.ultracoresoft.ultrasmsgateway.organization.OrganizationContext;
import jakarta.persistence.criteria.Predicate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
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
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** Administration pages: audit of messages, devices and API keys, always within the current organization. */
@Controller
@RequiredArgsConstructor
class DashboardController {

    private static final int PAGE_SIZE = 50;
    private static final int CHART_DAYS = 14;
    private static final int MAX_NAME_LENGTH = 100;

    private final SmsMessageRepository messages;
    private final MessageEventRepository events;
    private final DeviceService devices;
    private final DeviceSessionRegistry sessions;
    private final ApiKeyService apiKeys;
    private final Clock clock;
    private final Formats formats;
    private final OrganizationContext organization;

    record DeviceView(Device device, boolean online) {
    }

    @GetMapping("/login")
    String login() {
        return "login";
    }

    @GetMapping("/")
    String overview(Model model) {
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
        model.addAttribute("devices", deviceViews());

        LocalDate today = LocalDate.now(clock.withZone(formats.zoneId()));
        Instant chartStart = today.minusDays(CHART_DAYS - 1L).atStartOfDay(formats.zoneId()).toInstant();
        OverviewChart chart = OverviewChart.build(
                messages.findCreatedStatusSince(organizationId, chartStart), today, formats.zoneId(), CHART_DAYS);
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
                       Model model) {
        String recipientFilter = recipient == null ? "" : recipient.replaceAll("[^0-9+]", "");
        Page<SmsMessage> result = messages.findAll(filter(organization.currentOrganizationId(), status, recipientFilter),
                PageRequest.of(Math.max(page, 0), PAGE_SIZE, Sort.by(Sort.Direction.DESC, "createdAt")));
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
    String deviceList(Model model) {
        model.addAttribute("devices", deviceViews());
        model.addAttribute("serverUrl", ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/v1/device/ws").toUriString().replaceFirst("^http", "ws"));
        return "devices";
    }

    @PostMapping("/devices")
    String createDevice(@RequestParam String name, RedirectAttributes redirect) {
        if (!validName(name, redirect)) {
            return "redirect:/devices";
        }
        Issued<Device> issued = devices.create(organization.currentOrganizationId(), name);
        redirect.addFlashAttribute("issuedName", issued.value().getName());
        redirect.addFlashAttribute("issuedSecret", issued.secret());
        return "redirect:/devices";
    }

    @PostMapping("/devices/{id}/revoke")
    String revokeDevice(@PathVariable UUID id) {
        devices.revoke(organization.currentOrganizationId(), id);
        return "redirect:/devices";
    }

    @GetMapping("/api-keys")
    String apiKeyList(Model model) {
        model.addAttribute("apiKeys", apiKeys.list(organization.currentOrganizationId()));
        return "api-keys";
    }

    @PostMapping("/api-keys")
    String createApiKey(@RequestParam String name, RedirectAttributes redirect) {
        if (!validName(name, redirect)) {
            return "redirect:/api-keys";
        }
        Issued<ApiKey> issued = apiKeys.create(organization.currentOrganizationId(), name);
        redirect.addFlashAttribute("issuedName", issued.value().getName());
        redirect.addFlashAttribute("issuedSecret", issued.secret());
        return "redirect:/api-keys";
    }

    @PostMapping("/api-keys/{id}/revoke")
    String revokeApiKey(@PathVariable UUID id) {
        apiKeys.revoke(organization.currentOrganizationId(), id);
        return "redirect:/api-keys";
    }

    private static boolean validName(String name, RedirectAttributes redirect) {
        if (name == null || name.isBlank() || name.trim().length() > MAX_NAME_LENGTH) {
            redirect.addFlashAttribute("error", "The name is required and must be at most 100 characters.");
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
        return devices.list(organization.currentOrganizationId()).stream()
                .map(device -> new DeviceView(device, sessions.isOnline(device.getId())))
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
