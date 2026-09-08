---
kind: configuration_system
name: Spring Boot 基于 application.yml + @Value 的轻量配置体系
category: configuration_system
scope:
    - '**'
source_files:
    - src/main/resources/application.yml
    - src/main/java/com/example/fileupload/FileUploadApplication.java
    - src/main/java/com/example/fileupload/controller/FileController.java
    - src/main/java/com/example/fileupload/strategy/LocalUploadStrategy.java
    - src/main/java/com/example/fileupload/strategy/FtpUploadStrategy.java
    - src/main/java/com/example/fileupload/strategy/OssUploadStrategy.java
---

## 1. 系统/框架
本项目采用 Spring Boot 2.7.18 内置的配置机制，未引入任何第三方配置中心或外部化配置库。所有运行时参数通过 `src/main/resources/application.yml` 集中声明，由 Spring 容器在启动时自动加载。

## 2. 关键文件
- `src/main/resources/application.yml`：唯一的外部化配置文件，定义 server、multipart、file（local/ftp/oss）、logging 等分组。
- `pom.xml`：声明 Spring Boot parent 与依赖，无额外配置插件。
- `FileUploadApplication.java`：仅含 `@SpringBootApplication`，不做自定义配置类。
- 策略组件：`LocalUploadStrategy`、`FtpUploadStrategy`、`OssUploadStrategy` 均通过 `@Component` 注册并由 Spring 注入配置。
- `FileController.java`：使用 `@Value` 注入本地存储路径。

## 3. 架构与约定
### 3.1 配置来源与加载顺序
- 单一 YAML 源：`application.yml`，按 Spring Boot 默认顺序加载，未被覆盖时提供全部默认值。
- 每个后端策略组件独立声明自己的 `@Value` 字段，形成“配置即组件”的扁平结构——没有统一的 `@ConfigurationProperties` POJO，也没有独立的 `Config` 类。

### 3.2 配置分组与键命名
- `server.*`：端口。
- `spring.servlet.multipart.*`：启用 multipart、限制 `max-file-size` 与 `max-request-size` 为 50MB。
- `file.upload.base-path`：本地磁盘根目录，支持以 `/` 开头表示当前盘符根（如 `C:\file-upload-storage`）。
- `file.ftp.*`：host、port、username、password、basePath。
- `file.oss.*`：endpoint、access-key、secret-key、bucket-name、base-path、url-prefix。
- `logging.level.com.example.fileupload`：设为 DEBUG。

### 3.3 默认值与回退策略
所有 `@Value` 都带默认值，保证服务在无配置环境下仍可启动：
- `file.upload.base-path` → `/testPath/`
- `file.oss.endpoint` → `http://127.0.0.1:9000`；`url-prefix` 通过 `${file.oss.endpoint}` 引用实现回退。
- `file.ftp.host` → `192.168.1.100`；`port` → `21`；`basePath` → `/uploads/`。
- `LocalUploadStrategy` 在 `@PostConstruct` 中解析 base-path：若以 `/` 或 `\` 开头则拼接系统根盘符；否则作为相对路径或绝对路径直接使用。

### 3.4 运行时校验与降级
- `OssUploadStrategy.init()`：若 `access-key` 或 `secret-key` 为空，记录警告并跳过 MinIO 客户端初始化；后续调用 `ensureClientAvailable()` 会抛出 `IllegalStateException`。
- `FtpUploadStrategy.createAndConnectClient()`：连接失败、登录失败、权限不足（550/553）等错误被分类并转换为带中文提示的 `IOException`。
- 批量上传失败时直接抛异常，由上层统一捕获返回错误响应。

### 3.5 多环境扩展点
项目未包含 `application-{profile}.yml` 或 `bootstrap.yml`，但遵循 Spring Boot 标准扩展方式：可通过命令行参数 `--file.oss.access-key=...`、环境变量 `FILE_OSS_ACCESS_KEY` 或外部 `-D` 属性覆盖 `application.yml` 中的值，因为 Spring Boot 对 `@Value` 和 `application.yml` 均支持这些覆盖机制。

## 4. 约定与约束
- **配置位置**：所有业务相关配置集中在 `application.yml` 的 `file.*` 分组下，不分散到 properties 文件或数据库。
- **读取方式**：策略组件与 Controller 一律使用 `@Value("${...}")` 注入，不使用 `Environment` 或 `@ConfigurationProperties`。
- **默认值必须存在**：每个 `@Value` 表达式必须提供默认值，确保开发机开箱可用。
- **敏感信息**：OSS 的 `access-key`/`secret-key`、FTP 的 `password` 以明文形式出现在 `application.yml` 中；代码注释明确建议将 `url-prefix` 指向反向代理地址，但未实现加密或外部密钥管理。
- **路径规范**：本地存储 base-path 支持三种写法（盘符根、工作目录相对路径、绝对路径），由 `LocalUploadStrategy` 在启动时解析；OSS 的 basePath 会被规范化为以 `/` 开头且不以 `/` 结尾的目录前缀。
- **Multipart 限制**：通过 `spring.servlet.multipart.max-file-size` 与 `max-request-size` 统一限制单文件与单次请求大小均为 50MB，无需自定义解析器。
- **日志级别**：应用包 `com.example.fileupload` 默认开启 DEBUG，便于排查上传/下载/删除流程。