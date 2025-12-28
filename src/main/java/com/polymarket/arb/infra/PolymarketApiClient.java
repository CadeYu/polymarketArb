package com.polymarket.arb.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;

@Slf4j
@Service
public class PolymarketApiClient {

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    private static final String GAMMA_API_URL = "https://gamma-api.polymarket.com";
    private static final String CLOB_API_URL = "https://clob.polymarket.com";

    // Mimic Chrome User-Agent
    private static final String USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    public PolymarketApiClient(ObjectMapper objectMapper, @Value("${app.private-key:}") String privateKey) {
        this.objectMapper = objectMapper;
        // Use COMPATIBLE_TLS to ensure handshake success with some strict servers
        okhttp3.ConnectionSpec spec = new okhttp3.ConnectionSpec.Builder(okhttp3.ConnectionSpec.MODERN_TLS)
                .allEnabledTlsVersions()
                .allEnabledCipherSuites()
                .build();

        this.httpClient = new OkHttpClient.Builder()
                .connectionSpecs(java.util.Arrays.asList(spec, okhttp3.ConnectionSpec.CLEARTEXT))
                .readTimeout(60, TimeUnit.SECONDS) // Liberal timeout
                .connectTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
    }

    public JsonNode getMarkets(int limit, String offset) {
        String url = GAMMA_API_URL + "/markets?limit=" + limit + "&active=true&closed=false";
        if (offset != null && !offset.isEmpty()) {
            url += "&offset=" + offset;
        }
        return executeRequest(url);
    }

    public JsonNode getOrderBook(String token_id) {
        String url = CLOB_API_URL + "/book?token_id=" + token_id;
        return executeRequest(url);
    }

    public void submitOrder(OrderSigner.Order order, String signature) {
        // ... (keep existing implementation, omitted for brevity but technically this
        // tool replaces blocks)
        // Wait, I cannot omit if I'm replacing the whole block.
        // I will implement submitOrder fully again to be safe.

        try {
            var orderNode = objectMapper.createObjectNode();
            orderNode.put("salt", order.getSalt());
            orderNode.put("maker", order.getMaker());
            orderNode.put("signer", order.getSigner());
            orderNode.put("taker", order.getTaker());
            orderNode.put("tokenId", order.getTokenId());
            orderNode.put("makerAmount", order.getMakerAmount());
            orderNode.put("takerAmount", order.getTakerAmount());
            orderNode.put("expiration", order.getExpiration());
            orderNode.put("nonce", order.getNonce());
            orderNode.put("feeRateBps", order.getFeeRateBps());
            orderNode.put("side", order.getSide() == 0 ? "BUY" : "SELL");
            orderNode.put("signatureType", order.getSignatureType());

            var payload = objectMapper.createObjectNode();
            payload.set("order", orderNode);
            payload.put("owner", order.getMaker());
            payload.put("orderType", "GTC");
            payload.put("signature", signature);

            String jsonPayload = objectMapper.writeValueAsString(payload);

            Request request = new Request.Builder()
                    .url(CLOB_API_URL + "/order")
                    .post(okhttp3.RequestBody.create(jsonPayload, okhttp3.MediaType.parse("application/json")))
                    .header("User-Agent", USER_AGENT)
                    .header("Origin", "https://polymarket.com") // Make it look like official site
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String body = response.body() != null ? response.body().string() : "null";
                    log.error("[REAL-EXECUTION] Order Submission Failed: {} {}", response.code(), body);
                } else {
                    log.info("[REAL-EXECUTION] Order Submitted Successfully! Response: {}", response.body().string());
                }
            }
        } catch (Exception e) {
            log.error("[REAL-EXECUTION] Failed to submit order", e);
        }
    }

    // Global Rate Limiter: 4 requests per second
    private final RateLimiter rateLimiter = new RateLimiter(4.0);

    private JsonNode executeRequest(String url) {
        rateLimiter.acquire();

        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .header("Origin", "https://polymarket.com")
                .header("Referer", "https://polymarket.com/")
                .build();

        int retries = 3;
        for (int i = 0; i < retries; i++) {
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    if (response.code() == 429 && i < retries - 1) {
                        // Backoff for 429
                        try {
                            Thread.sleep(1000 * (i + 1));
                        } catch (InterruptedException ignored) {
                        }
                        continue;
                    }
                    throw new RuntimeException("API Request failed: " + response.code() + " " + response.message());
                }
                if (response.body() == null)
                    return null;
                return objectMapper.readTree(response.body().string());
            } catch (IOException e) {
                if (i == retries - 1) {
                    throw new RuntimeException("Failed to call API after retries: " + url, e);
                }
                // Transient network error, wait and retry
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ignored) {
                }
            }
        }
        return null; // Should not reach here
    }

    /**
     * Simple Token Bucket Rate Limiter
     */
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
            storedPermits = Math.min(1.0, storedPermits + newPermits); // Max burst 1.0
            lastSync = now;

            if (storedPermits >= 1.0) {
                storedPermits -= 1.0;
                return;
            }

            double missing = 1.0 - storedPermits;
            long waitNanos = (long) (missing / permitsPerSecond * 1_000_000_000.0);

            try {
                TimeUnit.NANOSECONDS.sleep(waitNanos);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            lastSync = System.nanoTime();
            storedPermits = 0;
        }
    }
}
