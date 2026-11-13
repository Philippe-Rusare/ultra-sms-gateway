package com.ultracoresoft.ultrasmsgateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Standalone, self-hosted gateway. Everything it runs is declared by {@link GatewayCore}. */
@SpringBootApplication
public class UltraSmsGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(UltraSmsGatewayApplication.class, args);
    }

}
