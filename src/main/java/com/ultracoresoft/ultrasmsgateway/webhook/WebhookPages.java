package com.ultracoresoft.ultrasmsgateway.webhook;

import com.ultracoresoft.ultrasmsgateway.common.Issued;
import com.ultracoresoft.ultrasmsgateway.organization.OrganizationContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Dashboard pages of the webhooks, always within the current organization. */
@Controller
@RequiredArgsConstructor
class WebhookPages {

    static final int PAGE_SIZE = 20;

    private final WebhookService webhooks;
    private final OrganizationContext organization;

    @GetMapping("/webhooks")
    String list(@RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("page", webhooks.page(organization.currentOrganizationId(), PageRequest.of(Math.max(page, 0), PAGE_SIZE)));
        model.addAttribute("events", WebhookEvent.SUBSCRIBABLE);
        return "webhooks";
    }

    @PostMapping("/webhooks")
    String create(@RequestParam String url, @RequestParam(required = false) String description,
                  @RequestParam(name = "events", required = false) List<String> events, RedirectAttributes redirect) {
        WebhookService.Result<Issued<WebhookEndpoint>> result =
                webhooks.create(organization.currentOrganizationId(), url, description, parse(events));
        if (!result.ok()) {
            return refused(result.problem(), redirect, "redirect:/webhooks");
        }
        redirect.addFlashAttribute("issuedName", result.value().value().getUrl());
        redirect.addFlashAttribute("issuedSecret", result.value().secret());
        return "redirect:/webhooks/" + result.value().value().getId();
    }

    @GetMapping("/webhooks/{id}")
    String detail(@PathVariable UUID id, @RequestParam(defaultValue = "0") int page, Model model) {
        WebhookEndpoint endpoint = webhooks.find(organization.currentOrganizationId(), id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("endpoint", endpoint);
        model.addAttribute("events", WebhookEvent.SUBSCRIBABLE);
        model.addAttribute("page", webhooks.deliveries(id, PageRequest.of(Math.max(page, 0), PAGE_SIZE)));
        return "webhook";
    }

    @PostMapping("/webhooks/{id}")
    String update(@PathVariable UUID id, @RequestParam String url, @RequestParam(required = false) String description,
                  @RequestParam(name = "events", required = false) List<String> events,
                  @RequestParam(defaultValue = "false") boolean active, RedirectAttributes redirect) {
        WebhookService.Result<WebhookEndpoint> result =
                webhooks.update(organization.currentOrganizationId(), id, url, description, parse(events), active);
        if (!result.ok()) {
            return refused(result.problem(), redirect, "redirect:/webhooks/" + id);
        }
        redirect.addFlashAttribute("noticeKey", "gw.webhooks.saved");
        return "redirect:/webhooks/" + id;
    }

    @PostMapping("/webhooks/{id}/test")
    String test(@PathVariable UUID id, RedirectAttributes redirect) {
        redirect.addFlashAttribute(webhooks.sendTest(organization.currentOrganizationId(), id) ? "noticeKey" : "errorKey",
                webhooks.find(organization.currentOrganizationId(), id).isPresent() ? "gw.webhooks.test.queued" : "gw.webhooks.error.NOT_FOUND");
        return "redirect:/webhooks/" + id;
    }

    @PostMapping("/webhooks/{id}/secret")
    String rotate(@PathVariable UUID id, RedirectAttributes redirect) {
        Optional<String> secret = webhooks.rotateSecret(organization.currentOrganizationId(), id);
        secret.ifPresent(value -> {
            redirect.addFlashAttribute("issuedName", webhooks.find(organization.currentOrganizationId(), id)
                    .map(WebhookEndpoint::getUrl).orElse(""));
            redirect.addFlashAttribute("issuedSecret", value);
        });
        return "redirect:/webhooks/" + id;
    }

    @PostMapping("/webhooks/{id}/delete")
    String delete(@PathVariable UUID id, RedirectAttributes redirect) {
        if (webhooks.delete(organization.currentOrganizationId(), id)) {
            redirect.addFlashAttribute("noticeKey", "gw.webhooks.deleted");
        }
        return "redirect:/webhooks";
    }

    @PostMapping("/webhooks/{id}/deliveries/{deliveryId}/retry")
    String retry(@PathVariable UUID id, @PathVariable UUID deliveryId, RedirectAttributes redirect) {
        if (webhooks.retry(organization.currentOrganizationId(), deliveryId)) {
            redirect.addFlashAttribute("noticeKey", "gw.webhooks.retry.queued");
        }
        return "redirect:/webhooks/" + id;
    }

    private static List<WebhookEvent> parse(List<String> events) {
        return events == null ? List.of() : events.stream().map(WebhookEvent::ofType).flatMap(Optional::stream)
                .filter(WebhookEvent.SUBSCRIBABLE::contains).distinct().toList();
    }

    private static String refused(WebhookService.Problem problem, RedirectAttributes redirect, String target) {
        redirect.addFlashAttribute("errorKey", "gw.webhooks.error." + problem.name());
        return target;
    }
}
