package com.ultracoresoft.ultrasmsgateway.organization;

import java.util.UUID;
import org.springframework.stereotype.Component;

/** Single-organization gateway: everything belongs to {@link Organization#DEFAULT_ID}. */
@Component
class DefaultOrganizationContext implements OrganizationContext {

    @Override
    public UUID currentOrganizationId() {
        return Organization.DEFAULT_ID;
    }
}
