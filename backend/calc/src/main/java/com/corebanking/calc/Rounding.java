package com.corebanking.calc;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Rounding rules a product can choose. Stored on the product, never hard-coded in a calculation.
 * Default for Indian retail lending is {@link #RUPEE_HALF_UP}.
 */
public enum Rounding {
    RUPEE_HALF_UP(0, RoundingMode.HALF_UP),
    RUPEE_DOWN(0, RoundingMode.DOWN),
    RUPEE_UP(0, RoundingMode.UP),
    PAISE_HALF_UP(2, RoundingMode.HALF_UP),
    PAISE_HALF_EVEN(2, RoundingMode.HALF_EVEN);

    private final int scale;
    private final RoundingMode mode;

    Rounding(int scale, RoundingMode mode) {
        this.scale = scale;
        this.mode = mode;
    }

    public BigDecimal apply(BigDecimal value) {
        return value.setScale(scale, mode);
    }

    public int scale() {
        return scale;
    }
}
