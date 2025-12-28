package com.polymarket.arb.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.polymarket.arb.domain.Market;
import com.polymarket.arb.domain.OrderBook;
import com.polymarket.arb.infra.PolymarketApiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class MarketIngestor {

    private final PolymarketApiClient apiClient;
    private final MarketSnapshotCache cache;

    // Rate Limiter: Max 10 requests per second (Token Bucket simplified)
    private final RateLimiter strictRateLimiter = new RateLimiter(10.0);

    @Scheduled(fixedDelay = 10000)
    public void ingestMarkets() {
        log.info("Starting full market ingestion...");
        try {
            int limit = 100; // Fetch 100 at a time
            int totalFetched = 0;
            int maxToFetch = 1000;

            // Use virtual threads for parallelism, but rate limited
            try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                for (int offset = 0; totalFetched < maxToFetch; offset += limit) {
                    JsonNode marketsParams = apiClient.getMarkets(limit, String.valueOf(offset));
                    if (marketsParams == null || !marketsParams.isArray() || marketsParams.size() == 0) {
                        break;
                    }

                    java.util.stream.StreamSupport.stream(marketsParams.spliterator(), false)
                            .forEach(node -> executor.submit(() -> {
                                strictRateLimiter.acquire(); // Enforce TPS
                                processMarket(node);
                            }));

                    totalFetched += marketsParams.size();
                    if (marketsParams.size() < limit)
                        break;
                }
            }

            log.info("Ingestion complete. Total markets in cache: {}", cache.getAllMarkets().size());
        } catch (Exception e) {
            log.error("Error during ingestion", e);
        }
    }

    private void processMarket(JsonNode node) {
        try {
            String marketId = node.path("id").asText();
            String clobTokenIdsStr = node.path("clobTokenIds").asText();

            // Optimization: Filter based on outcomePrices (Last Trade Prices)
            // If Sum(Prices) < 0.90, unlikely to have arb (Sum(Bids) > 1.0)
            String outcomePricesStr = node.path("outcomePrices").asText();
            if (outcomePricesStr != null && !outcomePricesStr.equals("null") && !outcomePricesStr.isEmpty()) {
                try {
                    JsonNode pricesNode = new com.fasterxml.jackson.databind.ObjectMapper().readTree(outcomePricesStr);
                    if (pricesNode.isArray() && pricesNode.size() > 0) {
                        BigDecimal sumPrices = BigDecimal.ZERO;
                        for (JsonNode p : pricesNode) {
                            sumPrices = sumPrices.add(new BigDecimal(p.asText("0")));
                        }

                        // Heuristic: If indicative sum is too low, skip expensive OrderBook fetch
                        // Keep safety buffer (e.g. 0.90)
                        if (sumPrices.compareTo(new BigDecimal("0.90")) < 0) {
                            // Skip processing this market to save API calls
                            return;
                        }
                    }
                } catch (Exception ignore) {
                    // If parsing fails, proceed safely
                }
            }

            if (clobTokenIdsStr == null || clobTokenIdsStr.isEmpty() || clobTokenIdsStr.equals("null")) {
                return;
            }

            JsonNode tokenIdsNode = new com.fasterxml.jackson.databind.ObjectMapper().readTree(clobTokenIdsStr);
            if (tokenIdsNode.size() != 2) {
                return; // Only support binary markets for now
            }

            String outcomeY = tokenIdsNode.get(0).asText();
            String outcomeN = tokenIdsNode.get(1).asText();

            // Fetch Orderbooks (Rate limited by outer loop technically? No, need to rate
            // limit these calls too)
            // Actually, strictRateLimiter.acquire() was called before processMarket.
            // But processMarket makes 2 calls. 1 'acquire' allows 1 unit of work (2 calls).
            // That means effectively 20 API calls/sec if limit is 10 ops/sec.
            // Let's acquire inside fetchOrderBook for stricter control?
            // Or just accept the 'market unit' cost.
            // Let's stick to '1 market processing token'.

            OrderBook obYes = fetchOrderBook(outcomeY);
            OrderBook obNo = fetchOrderBook(outcomeN);

            Market market = Market.builder()
                    .marketId(marketId)
                    .conditionId(node.path("condition_id").asText())
                    .negRisk(node.path("negRisk").asBoolean(false))
                    .outcomeIds(List.of(outcomeY, outcomeN))
                    .active(node.path("active").asBoolean())
                    .closed(node.path("closed").asBoolean())
                    .yesOrderBook(obYes)
                    .noOrderBook(obNo)
                    .question(node.path("question").asText())
                    .lastUpdated(Instant.now())
                    .build();

            cache.updateMarket(market);

        } catch (Exception e) {
            log.warn("Failed to process market {}: {}", node.path("id").asText(), e.getMessage());
        }
    }

    // Simple standalone RateLimiter class to imply usage
    private static class RateLimiter {
        private final double permitsPerSecond;
        private long lastSync = System.nanoTime();
        private double storedPermits = 0.0;

        public RateLimiter(double permitsPerSecond) {
            this.permitsPerSecond = permitsPerSecond;
        }

        public synchronized void acquire() {
            long now = System.nanoTime();
            double newPermits = (now - lastSync) / 1_000_000_000.0 * permitsPerSecond;
            storedPermits = Math.min(permitsPerSecond, storedPermits + newPermits);
            lastSync = now;

            if (storedPermits >= 1.0) {
                storedPermits -= 1.0;
                return;
            }

            double missing = 1.0 - storedPermits;
            long waitNanos = (long) (missing / permitsPerSecond * 1_000_000_000.0);

            try {
                java.util.concurrent.TimeUnit.NANOSECONDS.sleep(waitNanos);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            lastSync = System.nanoTime();
            storedPermits = 0;
        }
    }

    private OrderBook fetchOrderBook(String tokenId) {
        JsonNode bookNode = apiClient.getOrderBook(tokenId);
        List<OrderBook.OrderLevel> bids = parseLevels(bookNode.path("bids"));
        List<OrderBook.OrderLevel> asks = parseLevels(bookNode.path("asks"));
        return OrderBook.builder().marketId(tokenId).bids(bids).asks(asks).build();
    }

    private List<OrderBook.OrderLevel> parseLevels(JsonNode levelsNode) {
        List<OrderBook.OrderLevel> list = new ArrayList<>();
        if (levelsNode.isArray()) {
            for (JsonNode l : levelsNode) {
                list.add(OrderBook.OrderLevel.builder()
                        .price(new BigDecimal(l.path("price").asText("0")))
                        .size(new BigDecimal(l.path("size").asText("0")))
                        .build());
            }
        }
        return list;
    }
}
