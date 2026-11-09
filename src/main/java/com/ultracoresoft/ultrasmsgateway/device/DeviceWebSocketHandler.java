package com.ultracoresoft.ultrasmsgateway.device;

import com.ultracoresoft.ultrasmsgateway.config.GatewayProperties;
import com.ultracoresoft.ultrasmsgateway.message.MessageDispatcher;
import com.ultracoresoft.ultrasmsgateway.message.MessageService;
import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** Device side of the gateway: one WebSocket per phone. */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeviceWebSocketHandler extends TextWebSocketHandler {

    private final DeviceSessionRegistry sessions;
    private final DeviceService devices;
    private final MessageService messages;
    private final MessageDispatcher dispatcher;
    private final GatewayProperties properties;
    private final JsonMapper json;
    private final Clock clock;

    @Override
    public void afterConnectionEstablished(WebSocketSession webSocketSession) {
        UUID deviceId = deviceId(webSocketSession);
        DeviceSession session = new DeviceSession(deviceId, webSocketSession, json);
        sessions.register(session);
        devices.markConnected(deviceId);
        session.send(ServerFrames.hello(deviceId, clock.instant(), properties.dispatch().maxInFlightPerDevice()));
        log.info("Device {} connected", deviceId);
        dispatcher.onDeviceConnected(session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession webSocketSession, TextMessage textMessage) {
        UUID deviceId = deviceId(webSocketSession);
        DeviceFrame frame;
        try {
            frame = json.readValue(textMessage.getPayload(), DeviceFrame.class);
        } catch (JacksonException e) {
            reply(deviceId, ServerFrames.error("INVALID_FRAME", null));
            return;
        }
        String type = frame.type() == null ? "" : frame.type();
        if (!type.equals("heartbeat") && frame.id() == null) {
            reply(deviceId, ServerFrames.error("MISSING_ID", null));
            return;
        }
        switch (type) {
            case "heartbeat" -> devices.recordHeartbeat(deviceId, frame);
            case "ack" -> messages.onAccepted(deviceId, frame.id());
            case "sent" -> {
                messages.onSent(deviceId, frame.id());
                dispatcher.dispatchSoon();
            }
            case "delivered" -> messages.onDelivered(deviceId, frame.id());
            case "failed" -> {
                messages.onFailed(deviceId, frame.id(), frame.reason());
                dispatcher.dispatchSoon();
            }
            default -> reply(deviceId, ServerFrames.error("UNKNOWN_TYPE", frame.id()));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession webSocketSession, CloseStatus status) {
        UUID deviceId = deviceId(webSocketSession);
        sessions.unregister(deviceId, webSocketSession.getId());
        log.info("Device {} disconnected ({})", deviceId, status.getCode());
    }

    @Override
    public void handleTransportError(WebSocketSession webSocketSession, Throwable exception) {
        log.debug("Transport error for device {}: {}", deviceId(webSocketSession), exception.getMessage());
    }

    private void reply(UUID deviceId, Object frame) {
        sessions.get(deviceId).ifPresent(session -> session.send(frame));
    }

    private static UUID deviceId(WebSocketSession session) {
        return (UUID) session.getAttributes().get(DeviceHandshakeInterceptor.DEVICE_ID_ATTRIBUTE);
    }
}
