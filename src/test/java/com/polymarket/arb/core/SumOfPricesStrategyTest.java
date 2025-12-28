package com.polymarket.arb.core;

import com.polymarket.arb.domain.ArbitrageOpportunity;
import com.polymarket.arb.domain.Market;
import com.polymarket.arb.domain.OrderBook;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SumOfPricesStrategyTest {

        @Test
        void testDetectionLogic() {
                // 1. 准备假数据：创建一个价格之和小于 1 的市场
                MarketSnapshotCache cache = new MarketSnapshotCache();

                OrderBook yesBook = OrderBook.builder()
                                .asks(List.of(OrderBook.OrderLevel.builder().price(new BigDecimal("0.40"))
                                                .size(new BigDecimal("100"))
                                                .build()))
                                .bids(List.of(OrderBook.OrderLevel.builder().price(new BigDecimal("0.39"))
                                                .size(new BigDecimal("100")).build()))
                                .build();
                OrderBook noBook = OrderBook.builder()
                                .asks(List.of(OrderBook.OrderLevel.builder().price(new BigDecimal("0.50"))
                                                .size(new BigDecimal("100"))
                                                .build()))
                                .bids(List.of(OrderBook.OrderLevel.builder().price(new BigDecimal("0.49"))
                                                .size(new BigDecimal("100")).build()))
                                .build();

                Market mockMarket = Market.builder()
                                .marketId("test-market-1")
                                .yesOrderBook(yesBook)
                                .noOrderBook(noBook)
                                .build();

                cache.updateMarket(mockMarket);

                // 2. 运行策略
                com.polymarket.arb.infra.Web3Service mockWeb3Service = mock(com.polymarket.arb.infra.Web3Service.class);
                SumOfPricesStrategy strategy = new SumOfPricesStrategy(cache, mockWeb3Service);
                List<ArbitrageOpportunity> opportunities = strategy.detect();

                // 3. 验证结果
                assertFalse(opportunities.isEmpty(), "应当检测到套利机会");
                ArbitrageOpportunity opp = opportunities.get(0);
                assertEquals(new BigDecimal("0.90"), opp.getTotalCost());
                System.out.println("✅ 测试通过！成功检测到套利机会，利润: " + opp.getEstimatedProfit());
        }

        @Test
        void testNegRiskDetection() {
                // 1. Setup NegRisk Markets
                MarketSnapshotCache cache = new MarketSnapshotCache();

                // Sum = 0.60 + 0.50 = 1.10 > 1.0 -> Arb!
                String conditionId = "0xCondition123";

                OrderBook yesBookA = OrderBook.builder()
                                .bids(List.of(OrderBook.OrderLevel.builder().price(new BigDecimal("0.60"))
                                                .size(new BigDecimal("100")).build()))
                                .build();
                Market marketA = Market.builder()
                                .marketId("mkt-A")
                                .conditionId(conditionId)
                                .negRisk(true)
                                .outcomeIds(List.of("TokenA", "NoTokenA"))
                                .yesOrderBook(yesBookA)
                                .noOrderBook(OrderBook.builder().build())
                                .question("Candidate A")
                                .build();

                OrderBook yesBookB = OrderBook.builder()
                                .bids(List.of(OrderBook.OrderLevel.builder().price(new BigDecimal("0.50"))
                                                .size(new BigDecimal("100")).build()))
                                .build();
                Market marketB = Market.builder()
                                .marketId("mkt-B")
                                .conditionId(conditionId)
                                .negRisk(true)
                                .outcomeIds(List.of("TokenB", "NoTokenB"))
                                .yesOrderBook(yesBookB)
                                .noOrderBook(OrderBook.builder().build())
                                .question("Candidate B")
                                .build();

                cache.updateMarket(marketA);
                cache.updateMarket(marketB);

                // 2. Run Strategy
                com.polymarket.arb.infra.Web3Service mockWeb3Service = mock(com.polymarket.arb.infra.Web3Service.class);
                SumOfPricesStrategy strategy = new SumOfPricesStrategy(cache, mockWeb3Service);
                List<ArbitrageOpportunity> opportunities = strategy.detect();

                // 3. Verify
                assertFalse(opportunities.isEmpty(), "Should detect NegRisk opportunity");
                ArbitrageOpportunity opp = opportunities.get(0);
                assertTrue(opp.getEstimatedProfit().compareTo(BigDecimal.ZERO) > 0);
                assertEquals(ArbitrageOpportunity.Type.NEGRISK_SHORT_ARB, opp.getType());

                // Verify Execution Calls
                verify(mockWeb3Service, times(1)).executeSplit(eq(conditionId), any(), eq(2));
                verify(mockWeb3Service, times(1)).executeOrder(eq("mkt-A"), eq("TokenA"), anyDouble(), eq(0.60),
                                eq(false), eq(true));
                verify(mockWeb3Service, times(1)).executeOrder(eq("mkt-B"), eq("TokenB"), anyDouble(), eq(0.50),
                                eq(false), eq(true));

                System.out.println("✅ NegRisk Test Passed! Profit: " + opp.getEstimatedProfit());
        }
}
