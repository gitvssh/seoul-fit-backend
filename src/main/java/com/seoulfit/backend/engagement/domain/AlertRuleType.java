package com.seoulfit.backend.engagement.domain;

public enum AlertRuleType {
    AIR_QUALITY("AIR_QUALITY"),
    EXTREME_HEAT("TEMPERATURE"),
    HEAVY_RAIN("HEAVY_RAIN"),
    BIKE_SHORTAGE("LOCATION_BIKE_SHARE"),
    BIKE_FULL("LOCATION_BIKE_SHARE"),
    CULTURAL_EVENT("CULTURAL_EVENT"),
    RESERVATION_OPEN("CULTURAL_EVENT");

    private final String triggerStrategy;

    AlertRuleType(String triggerStrategy) {
        this.triggerStrategy = triggerStrategy;
    }

    public String triggerStrategy() {
        return triggerStrategy;
    }
}
