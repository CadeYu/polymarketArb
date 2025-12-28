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

    // Gamma API (Markets)
    private static final String GAMMA_API_URL = "https://gamma-api.polymarket.com";
    // CLOB API (Orderbook)
    private static final String CLOB_API_URL = "https://clob.polymarket.com";

    public PolymarketApiClient(ObjectMapper objectMapper, @Value("${app.private-key:}") String privateKey) {
        this.objectMapper = objectMapper;
        this.httpClient = new OkHttpClient.Builder()
                .readTimeout(30, TimeUnit.SECONDS)
                .connectTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    public JsonNode getMarkets(int limit, String offset) {
        // Example: /markets?limit=100&active=true&closed=false
        String url = GAMMA_API_URL + "/markets?limit=" + limit + "&active=true&closed=false";
        if (offset != null && !offset.isEmpty()) {
            url += "&offset=" + offset;
        }
        return executeRequest(url);
    }

    public JsonNode getOrderBook(String token_id) {
        // Example: /book?token_id=...
        String url = CLOB_API_URL + "/book?token_id=" + token_id;
        return executeRequest(url);
    }

    /**
     * Submits a signed order to the CLOB.
     * 
     * @param order     The EIP-712 order object
     * @param signature The hex signature string
     */
    public void submitOrder(OrderSigner.Order order, String signature) {
        // Construct Payload:
        // {
        // "order": { ... },
        // "owner": "0x...",
        // "orderType": "GTC", // Good Till Cancelled
        // "signature": "0x..."
        // }
        // Note: CLOB API expects the order fields to be strings for uint256 usually,
        // but let's check standard.
        // Assuming standard JSON structure.

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
            payload.put("orderType", "GTC"); // or FOK
            payload.put("signature", signature);

            String jsonPayload = objectMapper.writeValueAsString(payload);

            Request request = new Request.Builder()
                    .url(CLOB_API_URL + "/order") // Verify this endpoint
                    .post(okhttp3.RequestBody.create(jsonPayload, okhttp3.MediaType.parse("application/json")))
                    .header("User-Agent", "PolymarketArbBot/1.0")
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String body = response.body() != null ? response.body().string() : "null";
                    log.error("[REAL-EXECUTION] Order Submission Failed: {} {}", response.code(), body);
                    // Don't throw for now, just log error to avoid crashing loop
                } else {
                    log.info("[REAL-EXECUTION] Order Submitted Successfully! Response: {}", response.body().string());
                }
            }
        } catch (Exception e) {
            log.error("[REAL-EXECUTION] Failed to submit order", e);
        }
    }

    private JsonNode executeRequest(String url) {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "PolymarketArbBot/1.0")
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new RuntimeException("API Request failed: " + response.code() + " " + response.message());
            }
            if (response.body() == null) {
                return null;
            }
            return objectMapper.readTree(response.body().string());
        } catch (IOException e) {
            throw new RuntimeException("Failed to call API: " + url, e);
        }
    }
}
