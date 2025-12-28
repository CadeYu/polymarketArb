package com.polymarket.arb.core;

import com.polymarket.arb.domain.Market;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class MarketSnapshotCache {

    private final ConcurrentHashMap<String, Market> cache = new ConcurrentHashMap<>();

    public void updateMarket(Market market) {
        cache.put(market.getMarketId(), market);
    }

    public Market getMarket(String marketId) {
        return cache.get(marketId);
    }

    public Collection<Market> getAllMarkets() {
        return cache.values();
    }

    public java.util.List<Market> getMarketsByEventId(String eventId) {
        return cache.values().stream()
                .filter(m -> eventId.equals(m.getEventId()))
                .toList();
    }

    public void clear() {
        cache.clear();
    }
}
