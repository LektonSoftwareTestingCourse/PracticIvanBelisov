package com.processing.apitests.support;

public final class Patterns {

    public static final String RRN = "^\\d{12}$";

    public static final String AUTH_CODE = "^[A-Z0-9]{6}$";

    public static final String UUID = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$";

    public static final String ISO_INSTANT = "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z$";

    public static final String YEAR_MONTH = "^\\d{4}-(0[1-9]|1[0-2])$";

    private Patterns() {
    }

    public static String panOfBin(String bin) {
        return "^" + bin + "\\d{10}$";
    }
}
