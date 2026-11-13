package com.ultracoresoft.ultrasmsgateway.organization;

import java.util.UUID;

/**
 * Tells the dashboard which organization the current administrator is working in.
 *
 * <p>Extension point: the default implementation always answers the single default organization.
 * An edition with several organizations provides its own bean, marked {@code @Primary}, that resolves
 * the organization of the signed-in user.
 */
public interface OrganizationContext {

    UUID currentOrganizationId();
}
