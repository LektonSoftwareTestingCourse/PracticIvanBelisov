package com.processing.apitests.support;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class TestData {

    public static final String BIN_ISS001 = "400000";
    public static final String BIN_ISS002 = "400001";
    public static final String BIN_ISS003 = "400002";

    public static final String UNKNOWN_BIN = "499999";

    public static final String DEFAULT_CURRENCY = "643";

    private TestData() {
    }

    public static String stan() {
        return digits(6);
    }

    public static String rrn() {
        return "9" + digits(11);
    }

    public static String uuid() {
        return UUID.randomUUID().toString();
    }

    public static String unknownPanOfKnownBin() {
        return BIN_ISS001 + "0" + digits(9);
    }

    public static String panOfUnknownBin() {
        return UNKNOWN_BIN + digits(10);
    }

    public static String merchantId() {
        return "MT" + digits(13);
    }

    public static String terminalId() {
        return "T" + digits(7);
    }

    public static String clientIp() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return "10." + random.nextInt(1, 255) + "." + random.nextInt(1, 255) + "." + random.nextInt(1, 255);
    }

    public static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS);
    }

    public static String digits(int length) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append(random.nextInt(10));
        }
        return builder.toString();
    }

    public static boolean isLuhnValid(String pan) {
        int sum = 0;
        boolean doubleDigit = false;
        for (int i = pan.length() - 1; i >= 0; i--) {
            int digit = pan.charAt(i) - '0';
            if (doubleDigit) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            doubleDigit = !doubleDigit;
        }
        return sum % 10 == 0;
    }
}
