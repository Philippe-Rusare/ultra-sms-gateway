package com.ultracoresoft.ultrasmsgateway.config;

import com.ultracoresoft.ultrasmsgateway.apikey.ApiKeyAuthenticationFilter;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class OpenApiConfig {

    static final String API_KEY_SCHEME = "apiKey";

    @Bean
    OpenAPI gatewayOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Ultra SMS Gateway API")
                        .description("Queue SMS that are sent by Android phones connected to the gateway.")
                        .version("v1")
                        .license(new License().name("Apache-2.0").url("https://www.apache.org/licenses/LICENSE-2.0")))
                .components(new Components().addSecuritySchemes(API_KEY_SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.HEADER)
                        .name(ApiKeyAuthenticationFilter.API_KEY_HEADER)))
                .addSecurityItem(new SecurityRequirement().addList(API_KEY_SCHEME));
    }
}
