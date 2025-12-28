package com.polymarket.arb.core;

import com.polymarket.arb.domain.ArbitrageOpportunity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ArbitrageOrchestrator {

    private final List<ArbitrageDetector> detectors;
    private final ExecutionEngine executionEngine;

    // Run frequently to catch opportunities as soon as cache updates
    @Scheduled(fixedDelay = 5000) // Log heartbeat every 5s
    public void runLoop() {
        // Show heartbeat with actual count from detector's perspective
        // Since we can't easily count from here without adding a method to detector,
        // we'll just log that the loop is active.
        log.info("Arb Detector Heartbeat: Scanning markets... [Loop Active]"); // but okay for MVP log

        // 1. Detect from all strategies
        for (ArbitrageDetector detector : detectors) {
            try {
                List<ArbitrageOpportunity> opportunities = detector.detect();
                if (!opportunities.isEmpty()) {
                    log.info("Found {} opportunities using strategy: {}", opportunities.size(),
                            detector.getClass().getSimpleName());
                    processOpportunities(opportunities);
                }
            } catch (Exception e) {
                log.error("Error in detector strategy: {}", detector.getClass().getSimpleName(), e);
            }
        }
    }

    private void processOpportunities(List<ArbitrageOpportunity> opportunities) {
        // 2. Execute
        for (ArbitrageOpportunity opp : opportunities) {
            try {
                executionEngine.execute(opp);
            } catch (Exception e) {
                log.error("Failed to execute opportunity {}", opp.getId(), e);
            }
        }
    }
}
