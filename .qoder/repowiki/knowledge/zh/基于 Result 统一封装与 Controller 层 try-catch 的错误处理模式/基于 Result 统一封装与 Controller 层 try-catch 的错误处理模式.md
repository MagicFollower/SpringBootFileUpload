---
kind: error_handling
name: 基于 Result 统一封装与 Controller 层 try-catch 的错误处理模式
category: error_handling
scope:
    - '**'
source_files:
    - src/main/java/com/example/fileupload/model/Result.java
    - src/main/java/com/example/fileupload/controller/FileController.java
    - src/main/java/com/example/fileupload/enums/UploadType.java
    - src/main/java/com/example/fileupload/strategy/UploadStrategyFactory.java
    - src/main/java/com/example/fileupload/util/FileTypeValidator.java
    - src/main/java/com/example/fileupload/service/FtpBatchUploader.java
---

## 1. 采用的系统/方法

该项目是一个 Spring Boot REST 服务，**没有使用全局异常处理器（`@ControllerAdvice` + `@ExceptionHandler`）**，也没有定义自定义业务异常类型或统一的错误码枚举。错误处理采用以下组合方式：

- **统一响应体**：所有接口返回 `Result<T>` 对象（`code` + `message` + `data`），成功走 `Result.success(...)`，失败走 `Result.error(code, message)`。
- **Controller 内 try-catch**：每个请求处理方法内部用 `try { ... } catch (IllegalArgumentException e) / catch (Exception e)` 捕获异常并转换为 `Result.error(...)` 或 `ResponseEntity`。
- **工具类抛出 `IllegalArgumentException`**：参数校验、策略路由等位置通过抛 `IllegalArgumentException` 让上层统一捕获。
- **I/O 异常直接上抛**：FTP/OSS 等底层 I/O 操作抛出 `IOException`，由调用方（通常是 Controller）的 `catch (IOException e)` 分支处理。
- **日志记录**：所有异常路径均通过 `slf4j` 的 `log.error(...)` 记录堆栈，再返回用户友好的消息。

## 2. 关键文件与包

| 文件 | 作用 |
|---|---|
| `src/main/java/com/example/fileupload/model/Result.java` | 统一响应封装，提供 `success()` / `error(int, String)` 静态工厂 |
| `src/main/java/com/example/fileupload/controller/FileController.java` | 所有 REST 端点；集中进行 try-catch、异常→Result 转换、下载端点直接返回 `ResponseEntity` |
| `src/main/java/com/example/fileupload/enums/UploadType.java` | `fromCode()` 找不到时抛 `IllegalArgumentException` |
| `src/main/java/com/example/fileupload/strategy/UploadStrategyFactory.java` | 重复策略注册抛 `IllegalStateException`；未找到策略抛 `IllegalArgumentException` |
| `src/main/java/com/example/fileupload/util/FileTypeValidator.java` | 白名单/魔数校验失败抛 `IllegalArgumentException` |
| `src/main/java/com/example/fileupload/service/FtpBatchUploader.java` | FTP 连接/上传失败抛 `IOException`，并包装为带中文提示的消息 |

## 3. 架构与约定

- **无全局异常映射**：项目中不存在任何 `@ControllerAdvice`、`@ExceptionHandler`、`GlobalException`、`ErrorCode` 等文件，因此错误处理是**分散在 Controller 各方法内的局部 try-catch**。
- **分层职责**：
  - 工具/枚举/策略层只负责**抛出明确语义的异常**（如 `IllegalArgumentException`、`IOException`、`IllegalStateException`），不关心 HTTP 状态码。
  - Controller 层负责**将异常翻译为 HTTP 响应**：参数非法 → 400；资源缺失 → 404；未知异常 → 500；下载失败 → `ResponseEntity.internalServerError()`。
- **下载端点的特例**：`download` 方法不使用 `Result`，而是直接返回 `ResponseEntity<byte[]>`，因为它是二进制流响应，无法套用 JSON 统一结构。
- **错误码约定**：`Result` 中硬编码了少量状态码（200 成功、400 参数错误、404 未找到、500 服务器错误），没有集中枚举，新增错误码需在各处手动填写。
- **日志即兜底**：所有 catch 分支都先 `log.error("...", e)` 再返回用户消息，保证生产环境可追踪。

## 4. 约定与约束

- **参数校验失败**：通过 `UploadType.fromCode()`、`FileTypeValidator.validate()`、`UploadStrategyFactory.getStrategy()` 抛出 `IllegalArgumentException`，Controller 统一捕获并返回 `Result.error(400, e.getMessage())`。
- **资源不存在**：删除/查询时若找不到记录，直接返回 `Result.error(404, "文件不存在: " + id)`。
- **I/O 异常**：FTP/OSS 等底层异常以 `IOException` 形式向上传播，Controller 捕获后记录日志并返回 500。
- **重复策略注册**：启动阶段 `UploadStrategyFactory.buildStrategyMap()` 检测到重复 `UploadType` 会抛 `IllegalStateException`，属于启动期强约束。
- **无全局统一错误码表**：错误码散落在各处字符串中，新增业务错误码需要修改对应 Controller 分支，没有强制的枚举约束。
- **无 panic/recover 等价物**：Java 侧未使用 `try-finally` 做资源恢复以外的 recover 逻辑，仅依赖 `finally` 风格的资源关闭（由框架/库管理）。