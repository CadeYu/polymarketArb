# Polymarket Arbitrage System (MVP)

这是一个基于 Java Spring Boot 构建的高性能 Polymarket 套利系统。它能够实时扫描订单簿，识别并捕捉二元市场及多结果（NegRisk）市场中的数学定价错误。

## 🚀 核心功能

*   **多策略并行检测**：
    *   **二元镜像套利 (Binary Mirroring)**：通过 `min(Ask, 1-Bid)` 公式识别 YES 和 NO 代币之间的定价偏差。
    *   **NegRisk 套利 (Winner-Take-All)**：针对多结果事件，当 `Σ(YES_i) > 1.0` 时触发。
*   **高性能数据摄取**：
    *   并行抓取上百个活跃市场的订单簿。
    *   支持大批量市场分页扫描（目前上限 500 市场）。
*   **安全执行机制**：
    *   **Watch-Only 模式**：默认不加载私钥，仅在日志中记录发现的机会。
    *   **原子化模拟**：支持多订单（买入/卖出）执行流模拟。

## 🏗 技术栈

*   **后端**: Java 17, Spring Boot 3.x
*   **区块链**: Web3j (Polygon Mainnet/Amoy)
*   **API**: Polymarket Gamma API & CLOB API
*   **性能**: 并行流处理, 内存数据快照缓存

## 🛠 快速开始

### 1. 环境准备
*   JDK 17 或更高版本
*   Maven 3.6+

### 2. 编译项目
```bash
mvn clean package -DskipTests
```

### 3. 运行系统
```bash
java -jar target/arb-system-0.0.1-SNAPSHOT.jar
```

## ⚙️ 配置说明

在 `src/main/resources/application.properties` 中可以调整以下参数：

| 参数 | 说明 | 默认值 |
| :--- | :--- | :--- |
| `server.port` | 应用程序端口（0 为随机） | `0` |
| `PRIVATE_KEY` | 执行套利的钱包私钥（可选） | `null` |

## 📈 策略逻辑详解

### NegRisk 套利
针对总统大选等“赢家通吃”事件，系统会监测所有候选项 YES 代币的价格。
*   **原理**：由于最终只有一个结果为 YES (价值 $1)，所以理论上所有 YES 价格之和不应超过 $1。
*   **机会**：当 `Σ(Price_YES) > 1.0` 时，可以通过合约 `Split` 操作锁定无风险利润。

### 镜像套利 (Mirroring)
利用 Polymarket 订单簿的互补特性。
*   **原理**：`买入 YES @ 0.4` 逻辑上等同于 `卖出 NO @ 0.6`。
*   **公式**：`有效成本 = min(YES.ask, 1-NO.bid) + min(NO.ask, 1-YES.bid) < 1.0`。

## ⚖️ 免责声明

本系统仅供学习交流使用。交易数字资产存在极高风险，开发者不对任何因使用本系统导致的资金损失负责。在启用真实执行模式前，请务必进行充分的测试。
