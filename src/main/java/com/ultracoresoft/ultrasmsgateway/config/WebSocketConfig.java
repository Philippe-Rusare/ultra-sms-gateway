package com.ultracoresoft.ultrasmsgateway.config;

import com.ultracoresoft.ultrasmsgateway.device.DeviceHandshakeInterceptor;
import com.ultracoresoft.ultrasmsgateway.device.DeviceWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration(proxyBeanMethods = false)
@EnableWebSocket
@RequiredArgsConstructor
class WebSocketConfig implements WebSocketConfigurer {

    public static final String DEVICE_ENDPOINT = "/v1/device/ws";

    private final DeviceWebSocketHandler handler;
    private final DeviceHandshakeInterceptor handshakeInterceptor;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, DEVICE_ENDPOINT).addInterceptors(handshakeInterceptor);
    }
}
