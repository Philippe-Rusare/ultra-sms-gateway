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
import jakarta.persistence.criteria.Predicate;
import java.time.Clock;
import java.time.Duration;
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

/** Administration pages: audit of messages, devices and API keys. */
@Controller
@RequiredArgsConstructor
class DashboardController {

    private static final int PAGE_SIZE = 50;
    private static final int MAX_NAME_LENGTH = 100;

    private final SmsMessageRepository messages;
    private final MessageEventRepository events;
    private final DeviceService devices;
    private final DeviceSessionRegistry sessions;
    private final ApiKeyService apiKeys;
    private final Clock clock;

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
        messages.countByStatusSince(clock.instant().minus(Duration.ofHours(24)))
                .forEach(row -> counts.put(row.getStatus(), row.getTotal()));
        model.addAttribute("counts", counts);
        model.addAttribute("total24h", counts.values().stream().mapToLong(Long::longValue).sum());
        model.addAttribute("queuedNow", messages.countByStatus(MessageStatus.QUEUED));
        model.addAttribute("devices", deviceViews());
        return "overview";
    }

    @GetMapping("/messages")
    String messageList(@RequestParam(required = false) MessageStatus status,
                       @RequestParam(required = false) String recipient,
                       @RequestParam(defaultValue = "0") int page,
                       Model model) {
        String recipientFilter = recipient == null ? "" : recipient.replaceAll("[^0-9+]", "");
        Page<SmsMessage> result = messages.findAll(filter(status, recipientFilter),
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
        SmsMessage message = messages.findById(id)
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
        Issued<Device> issued = devices.create(name);
        redirect.addFlashAttribute("issuedName", issued.value().getName());
        redirect.addFlashAttribute("issuedSecret", issued.secret());
        return "redirect:/devices";
    }

    @PostMapping("/devices/{id}/revoke")
    String revokeDevice(@PathVariable UUID id) {
        devices.revoke(id);
        return "redirect:/devices";
    }

    @GetMapping("/api-keys")
    String apiKeyList(Model model) {
        model.addAttribute("apiKeys", apiKeys.list());
        return "api-keys";
    }

    @PostMapping("/api-keys")
    String createApiKey(@RequestParam String name, RedirectAttributes redirect) {
        if (!validName(name, redirect)) {
            return "redirect:/api-keys";
        }
        Issued<ApiKey> issued = apiKeys.create(name);
        redirect.addFlashAttribute("issuedName", issued.value().getName());
        redirect.addFlashAttribute("issuedSecret", issued.secret());
        return "redirect:/api-keys";
    }

    @PostMapping("/api-keys/{id}/revoke")
    String revokeApiKey(@PathVariable UUID id) {
        apiKeys.revoke(id);
        return "redirect:/api-keys";
    }

    private static boolean validName(String name, RedirectAttributes redirect) {
        if (name == null || name.isBlank() || name.trim().length() > MAX_NAME_LENGTH) {
            redirect.addFlashAttribute("error", "The name is required and must be at most 100 characters.");
            return false;
        }
        return true;
    }

    private static Specification<SmsMessage> filter(MessageStatus status, String recipient) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
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
        return devices.list().stream()
                .map(device -> new DeviceView(device, sessions.isOnline(device.getId())))
                .toList();
    }

    private Map<UUID, String> apiKeyNames() {
        return apiKeys.list().stream().collect(Collectors.toMap(ApiKey::getId, ApiKey::getName));
    }

    private Map<UUID, String> deviceNames() {
        return devices.list().stream().collect(Collectors.toMap(Device::getId, Device::getName, (a, b) -> a));
    }
}
