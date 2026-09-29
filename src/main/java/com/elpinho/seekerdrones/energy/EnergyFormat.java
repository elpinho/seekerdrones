package com.elpinho.seekerdrones.energy;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * Formats FE amounts in a player's chosen unit (DESIGN.md section 5.4). The short forms round to three significant
 * digits with an SI prefix ({@code 12.5 kFE}, {@code 1.25 MJ}); the exact forms keep every digit with thousands separators.
 */
public final class EnergyFormat {
    private static final String[] PREFIXES = {"", "k", "M", "G", "T", "P", "E"};
    private static final MathContext SHORT_PRECISION = new MathContext(3, RoundingMode.HALF_UP);

    /**
     * The local player's unit on the client, sent by the server on login and changed with the unit button. Screens and
     * tooltips format with it. Server-side code passes the player's unit explicitly instead.
     */
    private static volatile EnergyUnit clientUnit = EnergyUnit.AUTO;

    private EnergyFormat() {}

    public static EnergyUnit clientUnit() {
        return clientUnit;
    }

    public static void setClientUnit(EnergyUnit unit) {
        clientUnit = unit;
    }

    // --- In the client player's unit ---

    /** "12.5 kFE" */
    public static String amount(long fe) {
        return amount(fe, clientUnit);
    }

    /** "45.2 kFE / 50 kFE" */
    public static String ratio(long fe, long capacity) {
        return ratio(fe, capacity, clientUnit);
    }

    /** "1 kFE/t" */
    public static String rate(long fePerTick) {
        return amount(fePerTick) + "/t";
    }

    /** "45,210 / 50,000 FE", for advanced item tooltips. */
    public static String exactRatio(long fe, long capacity) {
        return exactRatio(fe, capacity, clientUnit);
    }

    // --- In a given unit ---

    public static String amount(long fe, EnergyUnit unit) {
        EnergyUnit resolved = unit.resolve();
        return abbreviate(resolved.fromFe(fe), resolved.symbol());
    }

    /** Each side has its own prefix, since they can differ: "950 J / 1.25 kJ". */
    public static String ratio(long fe, long capacity, EnergyUnit unit) {
        return amount(fe, unit) + " / " + amount(capacity, unit);
    }

    public static String exactRatio(long fe, long capacity, EnergyUnit unit) {
        EnergyUnit resolved = unit.resolve();
        return exact(resolved.fromFe(fe)) + " / " + exact(resolved.fromFe(capacity)) + " " + resolved.symbol();
    }

    // --- Numbers ---

    /** Three significant digits with an SI prefix on the symbol: 950 FE, 1.25 kFE, 12.5 kJ, 125 kJ, 1 MJ. */
    static String abbreviate(double value, String symbol) {
        BigDecimal rounded = new BigDecimal(value).round(SHORT_PRECISION);
        int prefix = 0;
        while (rounded.abs().compareTo(BigDecimal.valueOf(1000)) >= 0 && prefix < PREFIXES.length - 1) {
            rounded = rounded.movePointLeft(3);
            prefix++;
        }
        return rounded.stripTrailingZeros().toPlainString() + " " + PREFIXES[prefix] + symbol;
    }

    /** Every digit, with thousands separators and at most two decimals (Joules can be fractional): 45,210 or 2.5. */
    static String exact(double value) {
        // DecimalFormat isn't thread-safe, and this runs on both the client and server threads.
        return new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(Locale.ROOT)).format(value);
    }
}
