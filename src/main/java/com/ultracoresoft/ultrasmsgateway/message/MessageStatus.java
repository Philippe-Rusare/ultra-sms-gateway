package com.ultracoresoft.ultrasmsgateway.message;

/**
 * Life cycle of a message.
 *
 * <pre>
 * QUEUED ──▶ DISPATCHED ──▶ ACCEPTED ──▶ SENT ──▶ DELIVERED
 *   ▲            │              │
 *   └─ack timeout┘              └──▶ FAILED
 * QUEUED / DISPATCHED ──ttl──▶ EXPIRED
 * </pre>
 */
public enum MessageStatus {
    /** Waiting for a connected device. */
    QUEUED,
    /** Pushed to a device, waiting for its acknowledgement. */
    DISPATCHED,
    /** Acknowledged by the device, which is sending it. */
    ACCEPTED,
    /** Handed to the mobile network by the device. */
    SENT,
    /** Delivery report received from the network. */
    DELIVERED,
    /** The device reported a failure, or gave no report in time, or attempts ran out. */
    FAILED,
    /** Its time to live ran out before a device accepted it. */
    EXPIRED;

    /** The text is erased once a message reaches one of these statuses. */
    public boolean erasesBody() {
        return this == SENT || this == DELIVERED || this == FAILED || this == EXPIRED;
    }
}
