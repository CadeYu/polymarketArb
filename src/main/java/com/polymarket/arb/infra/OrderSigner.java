package com.polymarket.arb.infra;

import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Hash;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.Arrays;

@Slf4j
@Component
public class OrderSigner {

    // Exchange Contract on Polygon
    private static final String EXCHANGE_ADDRESS = "0x4D97DCd97eC945f40cF65F87097ACe5EA0476045";
    private final BigInteger CHAIN_ID = BigInteger.valueOf(137);

    // EIP-712 Type Hashes
    private static final byte[] EIP712_DOMAIN_TYPEHASH = Hash.sha3(
            "EIP712Domain(string name,string version,uint256 chainId,address verifyingContract)".getBytes());

    private static final byte[] ORDER_TYPEHASH = Hash.sha3(
            "Order(uint256 salt,address maker,address signer,address taker,uint256 tokenId,uint256 makerAmount,uint256 takerAmount,uint256 expiration,uint256 nonce,uint256 feeRateBps,uint8 side,uint8 signatureType)"
                    .getBytes());

    private final byte[] domainSeparator;

    public OrderSigner() {
        this.domainSeparator = buildDomainSeparator();
    }

    @Data
    @Builder
    public static class Order {
        private BigInteger salt;
        private String maker;
        private String signer;
        private String taker;
        private BigInteger tokenId;
        private BigInteger makerAmount;
        private BigInteger takerAmount;
        private BigInteger expiration;
        private BigInteger nonce;
        private BigInteger feeRateBps;
        private int side; // 0 = BUY, 1 = SELL
        private int signatureType; // 0 = EOA
    }

    public String signOrder(Order order, Credentials credentials) {
        byte[] hashStruct = hashOrder(order);

        // Final EIP-712 Hash: keccak256("\x19\x01" ‖ domainSeparator ‖
        // hashStruct(message))
        ByteBuffer buffer = ByteBuffer.allocate(2 + 32 + 32);
        buffer.put((byte) 0x19);
        buffer.put((byte) 0x01);
        buffer.put(domainSeparator);
        buffer.put(hashStruct);

        byte[] finalHash = Hash.sha3(buffer.array());

        Sign.SignatureData signatureData = Sign.signMessage(finalHash, credentials.getEcKeyPair(), false);

        // Combine into R, S, V formatted string/bytes
        // Web3j produces V as 27 or 28, we need to ensure it fits standard if needed,
        // but standard recover works with 27/28.
        byte[] r = signatureData.getR();
        byte[] s = signatureData.getS();
        byte[] v = signatureData.getV();

        // Polymarket CLOB expects hex string of joined signature components
        // signature = r + s + v (65 bytes) -> hex
        ByteBuffer sigBuffer = ByteBuffer.allocate(65);
        sigBuffer.put(r);
        sigBuffer.put(s);
        sigBuffer.put(v);

        return Numeric.toHexString(sigBuffer.array());
    }

    private byte[] buildDomainSeparator() {
        // keccak256(abi.encode(
        // KECCAK256("EIP712Domain(string name,string version,uint256 chainId,address
        // verifyingContract)"),
        // keccak256("Polymarket CTF Exchange"),
        // keccak256("1"),
        // chainId,
        // verifyingContract
        // ));

        return Hash.sha3(concat(
                EIP712_DOMAIN_TYPEHASH,
                Hash.sha3("Polymarket CTF Exchange".getBytes()),
                Hash.sha3("1".getBytes()),
                Numeric.toBytesPadded(CHAIN_ID, 32),
                Numeric.toBytesPadded(new BigInteger(EXCHANGE_ADDRESS.substring(2), 16), 32)));
    }

    private byte[] hashOrder(Order o) {
        // keccak256(abi.encode(
        // ORDER_TYPEHASH,
        // salt,
        // maker,
        // signer,
        // taker,
        // tokenId,
        // makerAmount,
        // takerAmount,
        // expiration,
        // nonce,
        // feeRateBps,
        // side,
        // signatureType
        // ))

        return Hash.sha3(concat(
                ORDER_TYPEHASH,
                Numeric.toBytesPadded(o.salt, 32),
                Numeric.toBytesPadded(new BigInteger(o.maker.substring(2), 16), 32),
                Numeric.toBytesPadded(new BigInteger(o.signer.substring(2), 16), 32),
                Numeric.toBytesPadded(new BigInteger(o.taker.substring(2), 16), 32),
                Numeric.toBytesPadded(o.tokenId, 32),
                Numeric.toBytesPadded(o.makerAmount, 32),
                Numeric.toBytesPadded(o.takerAmount, 32),
                Numeric.toBytesPadded(o.expiration, 32),
                Numeric.toBytesPadded(o.nonce, 32),
                Numeric.toBytesPadded(o.feeRateBps, 32),
                Numeric.toBytesPadded(BigInteger.valueOf(o.side), 32), // side is unit8 but expanded in abi.encode
                Numeric.toBytesPadded(BigInteger.valueOf(o.signatureType), 32)));
    }

    private byte[] concat(byte[]... arrays) {
        int totalLength = Arrays.stream(arrays).mapToInt(a -> a.length).sum();
        ByteBuffer buffer = ByteBuffer.allocate(totalLength);
        for (byte[] array : arrays) {
            buffer.put(array);
        }
        return buffer.array();
    }
}
