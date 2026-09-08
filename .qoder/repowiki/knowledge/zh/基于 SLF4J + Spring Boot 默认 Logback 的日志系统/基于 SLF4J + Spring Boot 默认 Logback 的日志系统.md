---
kind: logging_system
name: 基于 SLF4J + Spring Boot 默认 Logback 的日志系统
category: logging_system
scope:
    - '**'
source_files:
    - src/main/resources/application.yml
    - pom.xml
    - src/main/java/com/example/fileupload/controller/FileController.java
    - src/main/java/com/example/fileupload/service/FileStorageService.java
    - src/main/java/com/example/fileupload/strategy/FtpUploadStrategy.java
---

## 1. 使用的框架与依赖

本项目采用 **SLF4J API**（`org.slf4j.Logger` / `LoggerFactory`）作为统一日志门面，未显式引入 logback、log4j2 等具体实现。由于项目继承自 `spring-boot-starter-parent:2.7.18`，Spring Boot 自动通过 `spring-boot-starter-web` 引入 **Logback** 作为默认实现，因此运行时由 Logback 负责输出。

项目中没有自定义的 `logback.xml`、`log4j2.xml` 或 `application.yml` 中的 `logging.config` 覆盖，完全依赖 Spring Boot 的默认 Logback 配置。

## 2. 关键文件与位置

- 所有业务类均通过 `private static final Logger log = LoggerFactory.getLogger(XXX.class)` 方式获取 logger：
  - `controller/FileController.java`
  - `service/FileStorageService.java`
  - `service/FtpBatchUploader.java`
  - `service/RequestUploadProcessor.java`
  - `strategy/FtpUploadStrategy.java`
  - `strategy/LocalUploadStrategy.java`
  - `strategy/OssUploadStrategy.java`
  - `strategy/UploadStrategyFactory.java`
- 日志级别在 `src/main/resources/application.yml` 中统一配置：`com.example.fileupload: DEBUG`。

## 3. 架构与约定

### 3.1 Logger 获取方式
每个类使用 **静态字段 + `LoggerFactory.getLogger(Class)`** 的方式创建 logger，而非构造器注入或方法内局部变量。这是本仓库唯一的 logger 初始化模式。

### 3.2 日志级别使用约定
- **info**：记录业务成功事件，如保存元数据、Mock 数据初始化、FTP 上传成功、Bean 销毁等。
- **warn**：记录可恢复的异常场景，如 FTP 删除失败、批量上传部分失败、目录创建失败、MD5 计算失败等，附带上下文信息（路径、文件名、FTP reply 码）。
- **error**：仅在控制器层捕获到未预期的异常时调用（上传失败、下载失败、删除失败、查询失败），并附带完整堆栈。
- 未发现 `debug` / `trace` 级别的日志调用。

### 3.3 结构化字段
日志消息采用 **字符串模板 + 参数占位符** 的形式（如 `"[FileStorage] saved: id={}, fileKey={}"`），而非拼接字符串。这符合 SLF4J 的性能最佳实践。但并未使用 MDC、JSON 结构化日志或统一的 traceId/correlationId 机制——每条日志仅包含当前操作相关的少量字段（如 id、fileKey、path、reply 码等）。

### 3.4 日志前缀约定
各模块通过方括号前缀区分来源，便于快速过滤：
- `[FileStorage]` — 元数据存储操作
- `[FTP]` — FTP 单文件操作
- `[FTP-BATCH]` — FTP 批量上传
- 其他策略类未使用前缀，直接输出业务语义消息

### 3.5 错误处理中的日志
- Controller 层：catch 块中 `log.error("...", e)` 记录异常堆栈后返回统一 `Result.error(...)` 响应体。
- Strategy 层：对底层 I/O 异常（FTP/MINIO）优先抛出带人类可读信息的 `IOException`，并在必要时用 warn 记录中间状态；不吞掉异常。

## 4. 约束与规则

- **无自定义日志配置**：`application.yml` 中仅设置了包级日志级别 `com.example.fileupload: DEBUG`，未配置 appender、pattern、rolling policy 等，全部由 Spring Boot 默认 Logback 决定输出格式与目的地（控制台）。
- **无 AOP/拦截器统一打点**：请求入口、策略切换、存储操作等流程没有通过切面统一记录访问日志，仅在各组件内部按需记录。
- **无 MDC/TraceId**：跨组件链路追踪未实现，无法通过单一 ID 关联一次上传请求产生的多条日志。
- **无日志脱敏**：配置文件中的密码、密钥等敏感信息以明文形式出现在 `application.yml` 中，且未被日志系统特殊处理。
- **测试代码未见断言日志输出**：测试类主要验证业务行为，未发现针对日志内容的断言。

## 5. 总结

该项目的日志系统是典型的 Spring Boot 默认方案：SLF4J 门面 + Logback 实现，按包名统一设置 DEBUG 级别，各组件自行持有 logger 并以 info/warn/error 三级输出结构化消息。它满足基本调试与排错需求，但缺少集中式日志收集、链路追踪、结构化 JSON 输出、日志轮转等生产环境常见能力。