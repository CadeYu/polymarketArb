package com.polymarket.arb.domain;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class Position {
    private String marketId;
    private String outcomeId; // The specific token ID (YES or NO token address)
    private Side side; // YES or NO (conceptually)
    private BigDecimal balance; // Amount held
    private BigDecimal averagePrice; // Average entry price

    public enum Side {
        YES, NO
    }
}
