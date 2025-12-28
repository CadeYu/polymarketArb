package com.polymarket.arb.domain;

import lombok.Builder;
import lombok.Data;
import lombok.ToString;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Data
@Builder
@ToString
public class Market {
    private String marketId;
    private String conditionId;
    private String eventId; // For NegRisk grouping
    private boolean negRisk; // True for multi-outcome events
    private String question;
    private List<String> outcomeIds; // [YES, NO] usually
    private boolean active;
    private boolean closed;
    private boolean acceptingOrders;
    private BigDecimal liquidity;
    private BigDecimal volume;
    private Instant lastUpdated;

    // Derived or fetched separately
    private OrderBook yesOrderBook;
    private OrderBook noOrderBook;
}
