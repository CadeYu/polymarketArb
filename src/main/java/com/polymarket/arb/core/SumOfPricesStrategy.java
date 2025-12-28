package com.polymarket.arb.core;

import com.polymarket.arb.domain.ArbitrageOpportunity;
import com.polymarket.arb.domain.Market;
import com.polymarket.arb.domain.OrderBook;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class SumOfPricesStrategy implements ArbitrageDetector {

    private final MarketSnapshotCache cache;
    private final com.polymarket.arb.infra.Web3Service web3Service;

    // Safety margin (e.g., covering gas if non-trivial, though Polygon gas is
    // cheap)
    // Also accounts for potential slippage if we don't assume atomic
    private static final BigDecimal MIN_PROFIT_THRESHOLD = new BigDecimal("0.0001"); // Lowered for live observation

    @Override
    public List<ArbitrageOpportunity> detect() {
        List<ArbitrageOpportunity> opportunities = new ArrayList<>();
        Collection<Market> markets = cache.getAllMarkets();
        log.debug("Strategy scanning {} markets", markets.size());

        // 1. Group NegRisk Markets by Condition ID
        Map<String, List<Market>> negRiskGroups = new HashMap<>();

        for (Market market : markets) {
            if (!isValidMarket(market))
                continue;

            if (market.isNegRisk() && market.getConditionId() != null) {
                negRiskGroups.computeIfAbsent(market.getConditionId(), k -> new ArrayList<>()).add(market);
            }

            // Binary Mirroring Logic (existing)
            if (!market.isNegRisk()) {
                detectBinaryMirroring(market, opportunities);
            }
        }

        // 2. Process NegRisk Groups
        for (Map.Entry<String, List<Market>> entry : negRiskGroups.entrySet()) {
            detectNegRiskArb(entry.getKey(), entry.getValue(), opportunities);
        }

        return opportunities;
    }

    private void detectBinaryMirroring(Market market, List<ArbitrageOpportunity> opportunities) {
        // Mirroring Logic from docs:
        // Effective Buy YES = min(YES.ask, 1 - NO.bid)
        // Effective Buy NO = min(NO.ask, 1 - YES.bid)

        BigDecimal yesAsk = getBestPrice(market.getYesOrderBook(), true); // Ask
        BigDecimal noBid = getBestPrice(market.getNoOrderBook(), false); // Bid

        BigDecimal noAsk = getBestPrice(market.getNoOrderBook(), true); // Ask
        BigDecimal yesBid = getBestPrice(market.getYesOrderBook(), false); // Bid

        if (yesAsk == null || noBid == null || noAsk == null || yesBid == null)
            return;

        BigDecimal effectiveYesCost = yesAsk.min(BigDecimal.ONE.subtract(noBid));
        BigDecimal effectiveNoCost = noAsk.min(BigDecimal.ONE.subtract(yesBid));

        BigDecimal totalCost = effectiveYesCost.add(effectiveNoCost);

        // If Cost < 1.0
        if (totalCost.compareTo(BigDecimal.ONE) < 0) {
            BigDecimal potentialProfit = BigDecimal.ONE.subtract(totalCost);

            if (potentialProfit.compareTo(MIN_PROFIT_THRESHOLD) > 0) {
                // LOG / TRACK OPPORTUNITY
                // For Binary, execution is complex (involves selling NO or buying YES).
                // MVP Focus is NegRisk, so just logging here for now.
                ArbitrageOpportunity opp = ArbitrageOpportunity.builder()
                        .id(UUID.randomUUID().toString())
                        .marketId(market.getMarketId())
                        .type(ArbitrageOpportunity.Type.SYNTHETIC_ARBITRAGE)
                        .totalCost(totalCost)
                        .estimatedProfit(potentialProfit)
                        .detectedAt(Instant.now())
                        .build();

                opportunities.add(opp);
                log.info("📈 MIRROR ARBITRAGE FOUND: Market [{}] Cost: {} Profit: {}",
                        market.getQuestion(), totalCost, potentialProfit);
            }
        }
    }

    private void detectNegRiskArb(String conditionId, List<Market> markets, List<ArbitrageOpportunity> opportunities) {
        // NegRisk Strategy: Sum(BestBid_YES) > 1.0
        // If > 1.0, we MINT (Split) 1.0 set, and SELL all YES tokens.

        BigDecimal sumOfBids = BigDecimal.ZERO;
        List<BigDecimal> bestBids = new ArrayList<>();
        List<BigDecimal> liquidityAtBestBid = new ArrayList<>();

        // Ensure we have one market per outcome or at least cover the set logic
        // For NegRisk, usually each outcome has a market (YES/NO). We focus on YES
        // token of that market.

        for (Market m : markets) {
            BigDecimal yesBid = getBestPrice(m.getYesOrderBook(), false); // Best Bid for YES
            if (yesBid == null) {
                // If any outcome has NO bid, we risk not being able to sell that leg.
                // However, sum might still be > 1.0 if others are high?
                // Wait, if we can't sell one leg, we hold it. If it loses (likely), we lose 0
                // on that leg.
                // But we paid 1.0 for the whole set.
                // Ideally we want to sell ALL or Sum(Sold) > 1.0.
                yesBid = BigDecimal.ZERO;
            }
            bestBids.add(yesBid);
            sumOfBids = sumOfBids.add(yesBid);

            // Liquidity check (simplified: take size at best bid)
            // Real logic needs to walk the book.
            BigDecimal size = getBestSize(m.getYesOrderBook(), false);
            liquidityAtBestBid.add(size);
        }

        if (sumOfBids.compareTo(BigDecimal.ONE.add(MIN_PROFIT_THRESHOLD)) > 0) {
            // Found Opportunity!

            // Calculate max executable size (limited by thinnest leg)
            BigDecimal maxSize = liquidityAtBestBid.stream().min(Comparator.naturalOrder()).orElse(BigDecimal.ZERO);

            // Cap size for safety in MVP (e.g., 10 USDC)
            BigDecimal safeSize = maxSize.min(new BigDecimal("10"));

            if (safeSize.compareTo(new BigDecimal("1")) < 0) {
                return; // Too small
            }

            BigDecimal potentialProfitPerUnit = sumOfBids.subtract(BigDecimal.ONE);
            BigDecimal totalExpectedProfit = potentialProfitPerUnit.multiply(safeSize);

            log.info("🚨 NEGRISK ARB FOUND: Condition {} | Sum(Bids)={} | Profit/Unit={} | Size={}",
                    conditionId, sumOfBids, potentialProfitPerUnit, safeSize);

            // Execute!
            // 1. Split
            web3Service.executeSplit(conditionId, safeSize.toBigInteger(), markets.size());

            // 2. Sell each leg
            for (Market m : markets) {
                BigDecimal bidPrice = getBestPrice(m.getYesOrderBook(), false);
                if (bidPrice != null && bidPrice.compareTo(BigDecimal.ZERO) > 0) {
                    // Sell YES
                    // Token ID for YES is needed. Market has `outcomeIds` [YES, NO]
                    String yesTokenId = m.getOutcomeIds().get(0);

                    web3Service.executeOrder(
                            m.getMarketId(),
                            yesTokenId,
                            safeSize.doubleValue(),
                            bidPrice.doubleValue(),
                            false, // SELL
                            true // High Priority
                    );
                }
            }

            ArbitrageOpportunity opp = ArbitrageOpportunity.builder()
                    .id(UUID.randomUUID().toString())
                    .conditionId(conditionId)
                    .outcomeCount(markets.size())
                    .type(ArbitrageOpportunity.Type.NEGRISK_SHORT_ARB)
                    .estimatedProfit(totalExpectedProfit)
                    .detectedAt(Instant.now())
                    .build();
            opportunities.add(opp);
        }
    }

    private boolean isValidMarket(Market m) {
        return m != null && m.getYesOrderBook() != null && m.getNoOrderBook() != null; // Basic check
    }

    private BigDecimal getBestPrice(OrderBook book, boolean isAsk) {
        if (book == null)
            return null;
        List<OrderBook.OrderLevel> levels = isAsk ? book.getAsks() : book.getBids();
        if (levels == null || levels.isEmpty())
            return null;

        if (isAsk) {
            return levels.stream().map(OrderBook.OrderLevel::getPrice).min(Comparator.naturalOrder()).orElse(null);
        } else {
            return levels.stream().map(OrderBook.OrderLevel::getPrice).max(Comparator.naturalOrder()).orElse(null);
        }
    }

    private BigDecimal getBestSize(OrderBook book, boolean isAsk) {
        if (book == null)
            return BigDecimal.ZERO;
        List<OrderBook.OrderLevel> levels = isAsk ? book.getAsks() : book.getBids();
        if (levels == null || levels.isEmpty())
            return BigDecimal.ZERO;

        // Assuming getBestPrice finds the first level if sorted?
        // Actually current getBestPrice sorts every time.
        // Let's just take the size of the one with best price.

        if (isAsk) {
            return levels.stream().min(Comparator.comparing(OrderBook.OrderLevel::getPrice))
                    .map(OrderBook.OrderLevel::getSize).orElse(BigDecimal.ZERO);
        } else {
            return levels.stream().max(Comparator.comparing(OrderBook.OrderLevel::getPrice))
                    .map(OrderBook.OrderLevel::getSize).orElse(BigDecimal.ZERO);
        }
    }
}
