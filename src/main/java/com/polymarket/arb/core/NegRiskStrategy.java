package com.polymarket.arb.core;

import com.polymarket.arb.domain.ArbitrageOpportunity;
import com.polymarket.arb.domain.Market;
import com.polymarket.arb.domain.OrderBook;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class NegRiskStrategy implements ArbitrageDetector {

    private final MarketSnapshotCache cache;
    private static final BigDecimal MIN_PROFIT_THRESHOLD = new BigDecimal("0.0001");
    private static final BigDecimal TARGET_SIZE = new BigDecimal("10.0");
    private static final BigDecimal EXECUTION_BUFFER = new BigDecimal("0.002"); // 0.2% for slippage/fees

    @Override
    public List<ArbitrageOpportunity> detect() {
        List<ArbitrageOpportunity> opportunities = new ArrayList<>();

        // Group markets by eventId where negRisk is true
        Map<String, List<Market>> negRiskEvents = cache.getAllMarkets().stream()
                .filter(Market::isNegRisk)
                .collect(Collectors.groupingBy(Market::getEventId));

        for (Map.Entry<String, List<Market>> entry : negRiskEvents.entrySet()) {
            String eventId = entry.getKey();
            List<Market> markets = entry.getValue();

            // Σ(EffectiveBid(YES_i)) > 1.0 (Short Arb)
            BigDecimal totalEffectiveBid = BigDecimal.ZERO;
            boolean allMarketsHaveLiquidity = true;
            List<ArbitrageOpportunity.OrderRequest> requests = new ArrayList<>();
            StringBuilder breakdown = new StringBuilder();

            for (Market m : markets) {
                // Production Grade health check
                if (!m.isActive() || m.isClosed() || !m.isAcceptingOrders()) {
                    allMarketsHaveLiquidity = false;
                    break;
                }

                BigDecimal effectiveBid = getEffectivePrice(m.getYesOrderBook(), TARGET_SIZE, true);
                if (effectiveBid == null || effectiveBid.compareTo(BigDecimal.ZERO) == 0) {
                    allMarketsHaveLiquidity = false;
                    break;
                }
                totalEffectiveBid = totalEffectiveBid.add(effectiveBid);
                breakdown.append(String.format("[%s: %.4f] ", m.getOutcomeIds().get(0), effectiveBid));

                requests.add(ArbitrageOpportunity.OrderRequest.builder()
                        .tokenId(m.getOutcomeIds().get(0))
                        .price(effectiveBid)
                        .size(TARGET_SIZE)
                        .side(ArbitrageOpportunity.Side.SELL)
                        .build());
            }

            if (allMarketsHaveLiquidity && totalEffectiveBid.compareTo(BigDecimal.ONE) > 0) {
                BigDecimal grossProfit = totalEffectiveBid.subtract(BigDecimal.ONE);
                BigDecimal netProfit = grossProfit.subtract(EXECUTION_BUFFER);

                String eventDisplayName = markets.isEmpty() ? eventId
                        : markets.get(0).getQuestion().split(" \\| ")[0];

                // Pre-flight simulation reporting
                log.info("📊 PRE-FLIGHT REPORT | Event: {} | Σ(Eff. Bid): {} | Buffer: {} | Net: {}",
                        eventDisplayName, totalEffectiveBid, EXECUTION_BUFFER, netProfit);
                log.info("   -> Breakdown: {}", breakdown.toString());

                if (netProfit.compareTo(MIN_PROFIT_THRESHOLD) > 0) {
                    ArbitrageOpportunity opp = ArbitrageOpportunity.builder()
                            .id(UUID.randomUUID().toString())
                            .marketId(eventId)
                            .conditionId(markets.get(0).getConditionId())
                            .outcomeCount(markets.size())
                            .type(ArbitrageOpportunity.Type.NEGRISK_SHORT_ARB)
                            .requiredOrders(requests)
                            .totalCost(BigDecimal.ONE)
                            .estimatedProfit(netProfit)
                            .detectedAt(Instant.now())
                            .build();

                    opportunities.add(opp);
                    log.info("🎯 NEGRISK SHORT ARB TRIGGERED: Profit: {} | Event: {}", netProfit, eventDisplayName);
                }
            }
        }

        return opportunities;
    }

    private BigDecimal getEffectivePrice(OrderBook book, BigDecimal targetSize, boolean isBid) {
        if (book == null)
            return null;
        List<OrderBook.OrderLevel> levels = isBid ? book.getBids() : book.getAsks();
        if (levels == null || levels.isEmpty())
            return null;

        BigDecimal totalValue = BigDecimal.ZERO;
        BigDecimal remainingSize = targetSize;

        // Bids should be sorted desc (highest first), Asks asc (lowest first).
        // BestAsk is min, BestBid is max.
        List<OrderBook.OrderLevel> sortedLevels = new ArrayList<>(levels);
        if (isBid) {
            sortedLevels.sort((a, b) -> b.getPrice().compareTo(a.getPrice()));
        } else {
            sortedLevels.sort(Comparator.comparing(OrderBook.OrderLevel::getPrice));
        }

        for (OrderBook.OrderLevel level : sortedLevels) {
            BigDecimal fillSize = remainingSize.min(level.getSize());
            totalValue = totalValue.add(fillSize.multiply(level.getPrice()));
            remainingSize = remainingSize.subtract(fillSize);
            if (remainingSize.compareTo(BigDecimal.ZERO) <= 0)
                break;
        }

        if (remainingSize.compareTo(BigDecimal.ZERO) > 0) {
            // Not enough depth
            return null;
        }

        return totalValue.divide(targetSize, 4, RoundingMode.HALF_UP);
    }
}
