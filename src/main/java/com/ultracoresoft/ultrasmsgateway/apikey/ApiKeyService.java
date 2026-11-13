package com.ultracoresoft.ultrasmsgateway.apikey;

import com.ultracoresoft.ultrasmsgateway.common.Issued;
import com.ultracoresoft.ultrasmsgateway.common.Secrets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ApiKeyService {

    public static final String KEY_PREFIX = "usg_";
    private static final Duration LAST_USED_RESOLUTION = Duration.ofMinutes(1);

    private final ApiKeyRepository repository;
    private final Clock clock;

    @Transactional
    public Issued<ApiKey> create(UUID organizationId, String name) {
        String secret = Secrets.generate(KEY_PREFIX);
        ApiKey key = new ApiKey();
        key.setOrganizationId(organizationId);
        key.setName(name.trim());
        key.setKeyPrefix(Secrets.displayPrefix(secret));
        key.setKeyHash(Secrets.hash(secret));
        key.setCreatedAt(clock.instant());
        return new Issued<>(repository.save(key), secret);
    }

    @Transactional
    public void revoke(UUID organizationId, UUID id) {
        repository.findByIdAndOrganizationId(id, organizationId)
                .filter(ApiKey::isActive)
                .ifPresent(key -> key.setRevokedAt(clock.instant()));
    }

    @Transactional
    public Optional<ApiKeyPrincipal> authenticate(String secret) {
        if (secret == null || !secret.startsWith(KEY_PREFIX)) {
            return Optional.empty();
        }
        return repository.findByKeyHash(Secrets.hash(secret))
                .filter(ApiKey::isActive)
                .map(key -> {
                    Instant now = clock.instant();
                    // Avoid a database write on every request.
                    if (key.getLastUsedAt() == null || key.getLastUsedAt().isBefore(now.minus(LAST_USED_RESOLUTION))) {
                        key.setLastUsedAt(now);
                    }
                    return new ApiKeyPrincipal(key.getId(), key.getOrganizationId(), key.getName());
                });
    }

    @Transactional(readOnly = true)
    public List<ApiKey> list(UUID organizationId) {
        return repository.findAllByOrganizationIdOrderByCreatedAtDesc(organizationId);
    }
}
