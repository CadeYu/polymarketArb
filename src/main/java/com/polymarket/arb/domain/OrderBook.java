package com.polymarket.arb.domain;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
public class OrderBook {
    private String marketId;
    private Side side; // BUY (BIDS) or SELL (ASKS)
    // Wait, usually an OrderBook has both Bids and Asks.
    // But in Polymarket CLOB, we might fetch them together.

    private List<OrderLevel> bids;
    private List<OrderLevel> asks;

    @Data
    @Builder
    public static class OrderLevel {
        private BigDecimal price;
        private BigDecimal size;
    }

    public enum Side {
        BUY, SELL
    }
}
