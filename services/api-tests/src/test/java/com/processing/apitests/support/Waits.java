package com.processing.apitests.support;

import java.time.Duration;

public final class Waits {

    public static final Duration EFFECT_TIMEOUT = Duration.ofSeconds(10);

    public static final Duration POLL_INTERVAL = Duration.ofMillis(250);

    public static final Duration ABSENCE_WINDOW = Duration.ofSeconds(3);

    public static final Duration ABSENCE_TIMEOUT = ABSENCE_WINDOW.plusSeconds(2);

    public static final Duration ABSENCE_POLL_INTERVAL = Duration.ofMillis(500);

    private Waits() {
    }
}
