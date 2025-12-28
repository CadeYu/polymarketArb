package com.polymarket.arb.domain;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@Builder
public class ArbitrageOpportunity {
    private String id;
    private String marketId;
    private String conditionId; // Required for SPLIT
    private int outcomeCount; // Required for SPLIT partition
    private Type type;

    // Execution details
    private java.util.List<OrderRequest> requiredOrders;

    // Summary metrics
    private BigDecimal totalCost;
    private BigDecimal estimatedProfit;
    private Instant detectedAt;

    @Data
    @Builder
    public static class OrderRequest {
        private String tokenId;
        private BigDecimal price;
        private BigDecimal size;
        private Side side; // BUY or SELL
    }

    public enum Side {
        BUY, SELL
    }

    public enum Type {
        SYNTHETIC_ARBITRAGE, // YES + NO < 1
        NEGRISK_SHORT_ARB, // Σ(YES) > 1
        SPREAD_ARBITRAGE // Cross market
    }
}
