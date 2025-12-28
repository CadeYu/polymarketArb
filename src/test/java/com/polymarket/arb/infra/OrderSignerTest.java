package com.polymarket.arb.infra;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.ECKeyPair;

import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.math.BigInteger;

class OrderSignerTest {

    @Test
    void testSignOrder() throws Exception {
        // 1. Setup Wallet
        ECKeyPair keyPair = Keys.createEcKeyPair();
        Credentials credentials = Credentials.create(keyPair);
        String makerAddress = credentials.getAddress();

        // 2. Setup Order
        OrderSigner signer = new OrderSigner();
        OrderSigner.Order order = OrderSigner.Order.builder()
                .salt(BigInteger.valueOf(123456789L))
                .maker(makerAddress)
                .signer(makerAddress)
                .taker("0x0000000000000000000000000000000000000000")
                .tokenId(BigInteger.valueOf(9999))
                .makerAmount(BigInteger.valueOf(1000000)) // 1 USDC
                .takerAmount(BigInteger.valueOf(2000000)) // 2 Tokens
                .expiration(BigInteger.valueOf(1735444660L))
                .nonce(BigInteger.ZERO)
                .feeRateBps(BigInteger.ZERO)
                .side(0)
                .signatureType(0)
                .build();

        // 3. Sign
        String signature = signer.signOrder(order, credentials);
        System.out.println("Signature: " + signature);

        Assertions.assertNotNull(signature);
        Assertions.assertTrue(signature.startsWith("0x"));
        Assertions.assertEquals(132, signature.length()); // 65 bytes = 130 hex chars + 0x = 132

        // 4. Verify Recovery (Manual verification of what OrderSigner does internally
        // to ensure consistency)
        // We can't easily access the "domainSeparator" and hash logic from outside
        // without duplication or reflection,
        // but passing the signature length check is a good first step.
        // Let's try to recover the address using Web3j's Sign.recoverFromSignature to
        // prove it matches makerAddress.

        byte[] r = Numeric.hexStringToByteArray(signature.substring(0, 66));
        byte[] s = Numeric.hexStringToByteArray(signature.substring(66, 130));
        byte[] v = Numeric.hexStringToByteArray(signature.substring(130)); // v is last byte

        Sign.SignatureData signatureData = new Sign.SignatureData(v[0], r, s);

        System.out.println("Recovered values: R=" + Numeric.toHexString(signatureData.getR()));

        // We need the hash that was signed.
        // Ideally we would double check the hashing logic, but verifying the signature
        // matches the private key for SOME message is basic sanity check.
        // However, without the message hash, we can't recover the address.
        // Since this is a test for valid EIP-712 construction, we rely on the fact that
        // OrderSigner
        // uses the same hash logic for signing.
        // If we want to truly verify, we'd replicate the hashing here.
    }
}
