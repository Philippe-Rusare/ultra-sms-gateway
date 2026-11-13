package com.ultracoresoft.ultrasmsgateway;

import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.PropertySource;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The whole gateway as one importable configuration: API, queue, device channel, dashboard, organizations.
 *
 * <p>The standalone application picks it up by component scanning. Another Spring Boot application embeds
 * the gateway by depending on this jar and importing this class:
 *
 * <pre>
 * &#64;SpringBootApplication
 * &#64;Import(GatewayCore.class)
 * public class MyApplication { ... }
 * </pre>
 *
 * <p>It registers the gateway's components, JPA entities and repositories, and the default values of the
 * {@code gateway.*} settings ({@code ultra-sms-gateway-defaults.properties}), which the embedding
 * application overrides in its own configuration. Database migrations ({@code db/migration}), templates
 * and static files come with the jar. See docs/architecture.md, "Embedding the gateway".
 *
 * <p>An application whose own package is inside this one must also declare
 * {@code @AutoConfigurationPackage(basePackageClasses = GatewayCore.class)}: otherwise its package is
 * scanned twice and its repositories are registered twice.
 */
@Configuration(proxyBeanMethods = false)
@ComponentScan(
        basePackageClasses = GatewayCore.class,
        excludeFilters = {
                @Filter(type = FilterType.ASSIGNABLE_TYPE, classes = UltraSmsGatewayApplication.class),
                @Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
                @Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class)
        })
@AutoConfigurationPackage(basePackageClasses = GatewayCore.class)
@ConfigurationPropertiesScan(basePackageClasses = GatewayCore.class)
@PropertySource("classpath:ultra-sms-gateway-defaults.properties")
@EnableScheduling
public class GatewayCore {
}
