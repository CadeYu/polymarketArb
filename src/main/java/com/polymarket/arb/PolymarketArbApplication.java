package com.polymarket.arb;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PolymarketArbApplication {

    public static void main(String[] args) {
        System.setProperty("java.net.preferIPv4Stack", "true"); // Often helpful for OkHttp/Netty
        SpringApplication.run(PolymarketArbApplication.class, args);
    }

}
