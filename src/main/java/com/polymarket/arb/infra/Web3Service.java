package com.polymarket.arb.infra;

import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.DynamicArray;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;
import org.web3j.tx.RawTransactionManager;
import org.web3j.tx.TransactionManager;
import org.web3j.tx.gas.StaticGasProvider;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;

@Slf4j
@Service
public class Web3Service {

    private final Web3j web3j;
    private final Credentials credentials;
    private final PolymarketApiClient apiClient;
    private final OrderSigner orderSigner;

    // Polygon RPC
    private static final String POLYGON_RPC = "https://polygon-rpc.com";

    // Contract Addresses
    private static final String NEGRISK_ADAPTER = "0xd91E80cF2E7be2e162c6513ceD06f1dD0dA35296";
    private static final String USDC_TOKEN = "0x2791Bca1f2de4661ED88A30C99A7a9449Aa84174";

    public Web3Service(@Value("${app.private-key:}") String privateKey,
            PolymarketApiClient apiClient,
            OrderSigner orderSigner) {
        this.web3j = Web3j.build(new HttpService(POLYGON_RPC));
        this.apiClient = apiClient;
        this.orderSigner = orderSigner;

        if (privateKey != null && !privateKey.isEmpty()) {
            this.credentials = Credentials.create(privateKey);
            log.info("Wallet loaded: {}", credentials.getAddress());
        } else {
            this.credentials = null;
            log.warn("No Private Key provided. Execution will be in WATCH-ONLY mode.");
        }
    }

    public void executeOrder(String marketId, String tokenId, Double amount, Double price, boolean isBuy,
            boolean highPriority) {
        if (credentials == null) {
            log.info("[WATCH-ONLY] Would {} Token {} in Market {} for {} units @ {} | Priority={}",
                    isBuy ? "BUY" : "SELL", tokenId, marketId, amount, price, highPriority);
            return;
        }

        try {
            // Amount is in units of the asset being sold/bought?
            // If BUY: We provide USDC (MakerAmount) to get Tokens (TakerAmount).
            // If SELL: We provide Tokens (MakerAmount) to get USDC (TakerAmount).

            // NOTE: This logic depends heavily on CLOB conventions for "Maker" vs "Taker"
            // roles.
            // A "Maker" order usually sits on the book. A "Taker" order crosses.
            // Polymarket CLOB accepts "Maker" orders that can be FOK/IOC matches
            // (effectively Taker).
            // However, the fields are `makerAmount` and `takerAmount`.

            long makerAmountRaw;
            long takerAmountRaw;

            if (isBuy) {
                // BUY: We are paying USDC to get Tokens.
                // Maker = USDC, Taker = Tokens
                long amountTokens = (long) (amount * 1_000_000); // Wanted
                long amountUSDC = (long) (amount * price * 1_000_000); // Offered
                makerAmountRaw = amountUSDC;
                takerAmountRaw = amountTokens;
            } else {
                // SELL: We are paying Tokens to get USDC.
                // Maker = Tokens, Taker = USDC
                long amountTokens = (long) (amount * 1_000_000); // Offered
                long amountUSDC = (long) (amount * price * 1_000_000); // Wanted
                makerAmountRaw = amountTokens;
                takerAmountRaw = amountUSDC;
            }

            if (makerAmountRaw <= 0 || takerAmountRaw <= 0) {
                log.warn("Skipping INVALID order: Maker={}, Taker={}", makerAmountRaw, takerAmountRaw);
                return;
            }

            OrderSigner.Order order = OrderSigner.Order.builder()
                    .salt(BigInteger.valueOf(System.currentTimeMillis()))
                    .maker(credentials.getAddress())
                    .signer(credentials.getAddress())
                    .taker("0x0000000000000000000000000000000000000000")
                    .tokenId(new BigInteger(tokenId))
                    .makerAmount(BigInteger.valueOf(makerAmountRaw))
                    .takerAmount(BigInteger.valueOf(takerAmountRaw))
                    .expiration(BigInteger.valueOf(System.currentTimeMillis() / 1000 + 300)) // 5 mins
                    .nonce(BigInteger.ZERO)
                    .feeRateBps(BigInteger.ZERO)
                    .side(isBuy ? 0 : 1) // 0=BUY, 1=SELL
                    .signatureType(0) // EOA
                    .build();

            String signature = orderSigner.signOrder(order, credentials);

            log.info("[REAL-EXECUTION] Submitting {} order: {} Tokens @ {} USDC (Total {})",
                    isBuy ? "BUY" : "SELL", amount, price, amount * price);
            apiClient.submitOrder(order, signature);

        } catch (Exception e) {
            log.error("Failed to execute order", e);
        }
    }

    public void executeSplit(String conditionId, BigInteger amount, int outcomeCount) {
        if (credentials == null) {
            log.info("[WATCH-ONLY] Would execute SPLIT for condition {} amount {}", conditionId, amount);
            return;
        }

        log.info("[REAL-EXECUTION] Initiating on-chain SPLIT for condition {} with {} outcomes...", conditionId,
                outcomeCount);

        try {
            // 1. Construct Partition [1, 2, 4, ...]
            Uint256[] partitionArray = new Uint256[outcomeCount];
            for (int i = 0; i < outcomeCount; i++) {
                partitionArray[i] = new Uint256(BigInteger.valueOf(1).shiftLeft(i));
            }

            // 2. Prepare function: split(address collateralToken, bytes32
            // parentCollectionId, bytes32 conditionId, uint256[] partition, uint256 amount)
            Function function = new Function(
                    "split",
                    Arrays.asList(
                            new Address(USDC_TOKEN),
                            new Bytes32(new byte[32]), // parentCollectionId = 0
                            new Bytes32(org.web3j.utils.Numeric.hexStringToByteArray(conditionId)),
                            new DynamicArray<>(Uint256.class, Arrays.asList(partitionArray)),
                            new Uint256(amount)),
                    Collections.emptyList());

            String encodedFunction = FunctionEncoder.encode(function);

            // 3. Send Transaction
            TransactionManager txManager = new RawTransactionManager(web3j, credentials, 137); // Polygon Chain ID
            StaticGasProvider gasProvider = new StaticGasProvider(
                    BigInteger.valueOf(100).multiply(BigInteger.valueOf(1000000000L)), // 100 Gwei base price
                    BigInteger.valueOf(500000) // Gas Limit for split
            );

            log.info("[REAL-EXECUTION] Sending SPLIT transaction...");
            String txHash = txManager.sendTransaction(
                    gasProvider.getGasPrice("split"),
                    gasProvider.getGasLimit("split"),
                    NEGRISK_ADAPTER,
                    encodedFunction,
                    BigInteger.ZERO).getTransactionHash();

            log.info("[REAL-EXECUTION] SPLIT Transaction Sent! Hash: {}", txHash);

        } catch (Exception e) {
            log.error("[REAL-EXECUTION] FATAL ERROR during SPLIT execution", e);
            throw new RuntimeException("Split execution failed", e);
        }
    }
}
