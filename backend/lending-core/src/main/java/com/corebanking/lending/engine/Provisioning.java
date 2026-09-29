package com.corebanking.lending.engine;

import com.corebanking.lending.engine.Delinquency.AssetClass;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.EnumMap;
import java.util.Map;

/**
 * Provision required per loan (US-042). Rates are tenant configuration (provisioning table); the starter values
 * follow the RBI IRACP minimums for NBFCs and must be confirmed by the client (decision D-08).
 */
public final class Provisioning {

    public record Rates(Map<AssetClass, BigDecimal> secured, Map<AssetClass, BigDecimal> unsecured) {}

    private Provisioning() {}

    /** @param securedPortion part of the exposure covered by realisable security (0 for unsecured loans) */
    public static BigDecimal required(BigDecimal exposure, BigDecimal securedPortion, AssetClass assetClass, Rates rates) {
        BigDecimal secured = securedPortion.min(exposure).max(BigDecimal.ZERO);
        BigDecimal unsecured = exposure.subtract(secured);
        BigDecimal r1 = rate(rates.secured(), assetClass);
        BigDecimal r2 = rate(rates.unsecured(), assetClass);
        return secured.multiply(r1).add(unsecured.multiply(r2))
                .divide(BigDecimal.valueOf(100), MathContext.DECIMAL128).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal rate(Map<AssetClass, BigDecimal> m, AssetClass c) {
        BigDecimal r = m.get(c);
        if (r == null) throw new IllegalArgumentException("no provisioning rate for " + c);
        return r;
    }

    /** Starter table (to confirm, D-08). SMA classes are standard assets for provisioning. */
    public static Rates starter() {
        Map<AssetClass, BigDecimal> s = new EnumMap<>(AssetClass.class);
        Map<AssetClass, BigDecimal> u = new EnumMap<>(AssetClass.class);
        for (AssetClass c : AssetClass.values()) {
            BigDecimal secured = switch (c) {
                case STANDARD, SMA0, SMA1, SMA2 -> new BigDecimal("0.40");
                case SUBSTANDARD -> BigDecimal.TEN;
                case DOUBTFUL1 -> BigDecimal.valueOf(20);
                case DOUBTFUL2 -> BigDecimal.valueOf(30);
                case DOUBTFUL3 -> BigDecimal.valueOf(50);
                case LOSS -> BigDecimal.valueOf(100);
            };
            s.put(c, secured);
            u.put(c, c.ordinal() >= AssetClass.DOUBTFUL1.ordinal() ? BigDecimal.valueOf(100) : secured);
        }
        return new Rates(s, u);
    }
}
