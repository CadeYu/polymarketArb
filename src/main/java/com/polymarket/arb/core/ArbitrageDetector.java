package com.polymarket.arb.core;

import com.polymarket.arb.domain.ArbitrageOpportunity;

import java.util.List;

public interface ArbitrageDetector {
    List<ArbitrageOpportunity> detect();
}
