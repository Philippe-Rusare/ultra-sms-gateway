package com.ultracoresoft.ultrasmsgateway.device;

/** A phone was to be added while the organization already has as many active phones as its limit allows. */
public class DeviceLimitReachedException extends RuntimeException {

    private final int limit;

    public DeviceLimitReachedException(int limit) {
        super("The organization already has its " + limit + " phone(s).");
        this.limit = limit;
    }

    public int limit() {
        return limit;
    }
}
