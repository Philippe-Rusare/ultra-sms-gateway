package com.ultracoresoft.ultrasmsgateway.message;

import com.ultracoresoft.ultrasmsgateway.config.GatewayProperties;
import com.ultracoresoft.ultrasmsgateway.device.DeviceSession;
import com.ultracoresoft.ultrasmsgateway.device.DeviceSessionRegistry;
import com.ultracoresoft.ultrasmsgateway.device.ServerFrames;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Pushes queued messages to connected devices, at most {@code maxInFlightPerDevice} per device.
 * Runs on a timer and right away when a message is queued, a device connects or a device frees a slot.
 * Only one dispatch runs at a time; a request made during a run triggers one more run.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MessageDispatcher {

    private final DeviceSessionRegistry sessions;
    private final MessageService messages;
    private final GatewayProperties properties;

    private final ReentrantLock lock = new ReentrantLock();
    private final AtomicBoolean requested = new AtomicBoolean();

    @Scheduled(fixedDelayString = "${gateway.dispatch.interval}")
    public void tick() {
        try {
            messages.runMaintenance();
        } catch (RuntimeException e) {
            log.error("Queue maintenance failed", e);
        }
        dispatch();
    }

    /** Dispatches on a background thread, without delaying the caller. */
    public void dispatchSoon() {
        Thread.ofVirtual().name("sms-dispatch").start(this::dispatch);
    }

    /** Pushes again what the device had not acknowledged before it disconnected, then fills its free slots. */
    public void onDeviceConnected(DeviceSession session) {
        for (SmsMessage message : messages.awaitingAcknowledgement(session.deviceId())) {
            if (!session.send(ServerFrames.send(message))) {
                break;
            }
        }
        dispatchSoon();
    }

    public void dispatch() {
        requested.set(true);
        while (requested.get() && lock.tryLock()) {
            try {
                requested.set(false);
                dispatchOnce();
            } catch (RuntimeException e) {
                log.error("Dispatch failed", e);
            } finally {
                lock.unlock();
            }
        }
    }

    private void dispatchOnce() {
        int maxInFlight = properties.dispatch().maxInFlightPerDevice();
        for (DeviceSession session : sessions.all()) {
            if (!session.isOpen()) {
                continue;
            }
            int free = maxInFlight - (int) messages.inFlight(session.deviceId());
            if (free <= 0) {
                continue;
            }
            for (SmsMessage message : messages.claim(session.deviceId(), free)) {
                // On failure the message stays DISPATCHED and goes back to the queue after the ack timeout.
                if (!session.send(ServerFrames.send(message))) {
                    break;
                }
            }
        }
    }
}
