# 数据库配置指南

## 1. 连接池配置

### 1.1 HikariCP 连接池

HikariCP 是目前性能最高的 JDBC 连接池实现。在 Spring Boot 项目中，默认已集成 HikariCP，无需额外依赖。

核心配置参数如下：

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
      idle-timeout: 600000
      max-lifetime: 1800000
      connection-timeout: 30000
      connection-test-query: SELECT 1
```

各参数说明：

- **maximum-pool-size**：连接池最大连接数。推荐设置为 CPU 核心数 * 2 + 磁盘数。例如 4 核服务器建议设为 10-20。过大的连接数会增加数据库负担，反而降低性能。
- **minimum-idle**：连接池最小空闲连接数。建议与 maximum-pool-size 保持合理比例，通常设为最大连接数的 1/4 到 1/3。
- **idle-timeout**：空闲连接超时时间（毫秒）。超过此时间的空闲连接将被回收，默认 600000（10 分钟）。仅在 minimum-idle < maximum-pool-size 时生效。
- **max-lifetime**：连接最大存活时间（毫秒）。建议设置为 30 分钟到 1 小时，确保连接在使用一定时间后被重建，避免数据库端长时间保持同一连接导致的潜在问题。
- **connection-timeout**：获取连接超时时间（毫秒）。如果超过此时间仍未获取到连接，将抛出 SQLException。建议设置为 30 秒。

### 1.2 连接池监控

生产环境中必须对连接池进行监控。可以通过以下方式获取连接池状态：

- **JMX 监控**：启用 HikariCP 的 JMX 支持，通过 JConsole 或 Prometheus + JMX Exporter 采集指标
- **日志监控**：设置日志级别为 DEBUG 可输出连接获取和释放的详细信息
- **健康检查**：Spring Boot Actuator 的 `/actuator/health` 端点会自动包含连接池状态

关键监控指标包括：活跃连接数（activeConnections）、空闲连接数（idleConnections）、等待获取连接的线程数（threadsAwaitingConnection）、连接获取平均时间。

当活跃连接数长期接近 maximum-pool-size 时，说明连接池可能不足，需要扩容或排查慢查询。

## 2. Redis 配置

### 2.1 单机模式配置

开发环境和简单生产部署可以使用单机模式：

```yaml
spring:
  redis:
    host: localhost
    port: 6379
    password: your_password
    database: 0
    timeout: 3000
    lettuce:
      pool:
        max-active: 8
        max-idle: 8
        min-idle: 2
        max-wait: -1
```

配置要点：

- **password**：生产环境务必设置密码，并使用足够复杂的密码。建议通过环境变量注入，不要明文写在配置文件中。
- **database**：Redis 默认有 16 个数据库（0-15），可以通过 database 参数指定使用的数据库编号。不同应用建议使用不同的 database 或 Redis 前缀进行隔离。
- **timeout**：连接超时时间（毫秒），建议设置为 3000 毫秒。过短可能导致正常请求超时，过长则影响故障感知速度。
- **连接池参数**：Lettuce 基于 Netty 实现，默认使用共享连接，max-active 控制最大活跃连接数。一般应用设为 8 即可，高并发场景可适当增大。

### 2.2 哨兵模式配置

生产环境推荐使用哨兵模式实现高可用：

```yaml
spring:
  redis:
    sentinel:
      master: mymaster
      nodes: sentinel1:26379,sentinel2:26379,sentinel3:26379
      password: sentinel_password
    password: redis_password
    database: 0
```

哨兵模式至少需要 3 个 Sentinel 节点以保证可靠性。当主节点故障时，Sentinel 会自动进行故障转移，客户端通过 Sentinel 获取新的主节点地址。

### 2.3 Redis 使用规范

- **Key 命名**：使用冒号分隔的业务前缀，例如 `user:10001:profile`、`order:20240101:count`
- **过期时间**：所有缓存必须设置过期时间（TTL），避免内存泄漏。Session 类缓存建议 30 分钟，业务数据缓存建议 1-24 小时
- **序列化**：推荐使用 JSON 序列化（Jackson），便于跨语言使用和调试排查
- **大 Key 处理**：单个 Key 的 Value 不应超过 10KB，超过时应考虑分片或压缩

## 3. 应用配置

### 3.1 多环境配置管理

Spring Boot 支持通过 Profile 机制管理多环境配置：

```
application.yml           # 公共配置
application-dev.yml       # 开发环境
application-test.yml      # 测试环境
application-prod.yml      # 生产环境
```

激活指定环境：`spring.profiles.active=prod`

### 3.2 敏感配置管理

数据库密码、API Key 等敏感信息不应硬编码在配置文件中，推荐以下方案：

- **环境变量**：通过 `${ENV_VARIABLE}` 语法引用环境变量
- **配置中心**：使用 Nacos、Apollo 等配置中心统一管理
- **Vault**：对于高安全要求场景，使用 HashiCorp Vault 管理密钥

示例：

```yaml
spring:
  datasource:
    url: ${DB_URL:jdbc:mysql://localhost:3306/mydb}
    username: ${DB_USER:root}
    password: ${DB_PASSWORD:}
```

### 3.3 日志配置

推荐使用 Logback 作为日志框架，按以下规范配置：

- **日志级别**：生产环境 root 级别设为 INFO，业务包设为 DEBUG
- **日志格式**：包含时间戳、级别、线程名、类名、日志内容
- **日志滚动**：按日期和大小滚动，保留最近 30 天日志
- **异步日志**：高并发场景使用 AsyncAppender 避免日志 IO 阻塞业务线程
