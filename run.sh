#!/bin/bash

# Polymarket Arbitrage Bot Runner
# Usage: ./run.sh [PRIVATE_KEY]

# Default to empty (Watch-Only)
KEY=${1:-""}

if [ -z "$KEY" ]; then
    echo "⚠️  No Private Key provided. Running in WATCH-ONLY mode."
    echo "   (Pass your private key as the first argument to enable execution)"
else
    echo "🚀 Private Key detected. REAL EXECUTION MODE ENABLED."
    echo "   Ensure you have USDC and MATIC in your wallet!"
fi

# Build first
mvn clean package -DskipTests

# Run with key passed as System Property or Env Var
# Spring Boot maps APP_PRIVATE_KEY -> app.private-key
export APP_PRIVATE_KEY="$KEY"

java -jar target/arb-system-0.0.1-SNAPSHOT.jar
