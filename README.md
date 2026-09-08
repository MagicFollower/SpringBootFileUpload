# 文件上传方案（File Upload Service）

基于 Spring Boot 2.7.18 + Java 8 的多存储后端文件上传/下载/查询/删除服务，采用**策略模式 + 工厂模式 + 门面模式**架构设计，支持本地磁盘、FTP、OSS 三种存储方式。

---

## 技术栈

| 组件 | 版本 | 说明 |
|------|------|------|
| Spring Boot | 2.7.18 | Web 框架 |
| Java | 1.8 | 运行环境 |
| Apache Commons Net | 3.9.0 | 真实 FTP 客户端 |
| Apache Commons IO | 2.13.0 | 文件工具类 |
| Apache HttpClient | 4.5.14 | HTTP 客户端（OSS Mock） |
| JUnit 5 + Spring Test | - | 集成测试 |

---

## 需求分析

### 核心功能

1. **多存储后端支持**：同一套 API 支持本地磁盘、FTP、阿里云 OSS 三种存储方式，通过 `uploadType` 参数路由
2. **文件安全校验**：魔数验证（PNG/JPG/GIF/BMP/PDF/ZIP/RAR 文件头）+ 后缀白名单（图片/文档/压缩包等 17 种），防止恶意文件上传
3. **唯一文件名生成**：UUID + 原始文件名，自动处理碰撞重试
4. **批量上传**：FTP 场景下复用单个 TCP 连接，减少握手开销
5. **完整 CRUD**：上传、下载、删除、分页查询、关键词搜索、多维度过滤
6. **Mock 数据**：内存级 `FileStorageService` 提供 8 条默认测试数据，无需数据库

### 非功能性需求

- **跨平台路径解析**：`/file-upload-storage/` 在 Windows 上自动映射为 `C:\file-upload-storage\`，Linux 上映射为 `/file-upload-storage`
- **UTF-8 编码保护**：FTP 控制命令使用 UTF-8，二进制传输模式，避免中文文件名乱码
- **被动模式穿透**：FTP 使用 `enterLocalPassiveMode()` 兼容 NAT/防火墙环境
- **Windows 临时文件锁修复**：一次性读取 `MultipartFile` 字节数组，避免 Tomcat 重复锁定临时文件导致删除失败
- **细粒度错误提示**：根据 FTP 回复码（550/553/421/425/426/552）分类返回用户友好的错误消息

---

## 架构设计

### 设计模式

```
┌─────────────────────────────────────────────────┐
│              FileController (REST API)           │
│  POST /upload, POST /upload/batch, GET /download │
│  DELETE /{id}, GET /, GET /{id}                  │
└──────────────┬──────────────────────────────────┘
               │
               ▼
┌─────────────────────────────────────────────────┐
│        RequestUploadProcessor (Facade)          │
│  请求解析 → 类型校验 → 策略路由 → 结果组装        │
└──────────────┬──────────────────────────────────┘
               │
               ▼
┌─────────────────────────────────────────────────┐
│       UploadStrategyFactory (Factory)           │
│  @PostConstruct 自动注册所有 @Component 策略     │
│  getStrategy(UploadType) → 返回对应实现          │
└──────────────┬──────────────────────────────────┘
               │
       ┌───────┼────────┐
       ▼       ▼        ▼
┌──────────┐ ┌──────┐ ┌──────────┐
│  Local   │ │ FTP  │ │   OSS    │
│ Strategy │ │Strat.│ │ Strategy │
└──────────┘ └──┬───┘ └──────────┘
                │
                ▼
        ┌──────────────┐
        │FtpBatchUploader│
        │ ThreadLocal      │
        │ Connection Pool  │
        │ 单文件/批量/目录 │
        └──────────────┘
```

### 核心组件

| 组件 | 职责 |
|------|------|
| `UploadStrategy` | 策略接口，定义 `upload(MultipartFile)/upload(byte[])/download/delete/getUploadType` |
| `LocalUploadStrategy` | 本地磁盘存储，`@PostConstruct` 解析盘符根目录 |
| `FtpUploadStrategy` | FTP 存储，单文件走真实连接，批量复用 `FtpBatchUploader` |
| `OssUploadStrategy` | OSS Mock 存储（内存 Map），可扩展为真实阿里云 SDK |
| `UploadStrategyFactory` | 工厂类，自动收集所有 `UploadStrategy` Bean，按 `UploadType` 分发 |
| `RequestUploadProcessor` | 门面层，封装完整的上传流程：解析→校验→路由→组装 |
| `FileTypeValidator` | 魔数校验（PNG/JPG/GIF/BMP/PDF/ZIP/RAR）+ 后缀白名单（17 种），防止 `.exe` 伪装成 `.png` |
| `FileStorageService` | 内存级元数据存储（ConcurrentHashMap），替代数据库 |
| `FtpBatchUploader` | FTP 批量上传工具，ThreadLocal 连接池，支持单文件/批量/目录递归上传 |

### 关键设计决策

1. **路径语义**：配置中 `base-path` 以 `/` 开头表示应用所在盘符根目录（Windows: `C:\`，Linux: `/`），不以 `/` 开头则为相对当前工作目录
2. **FileInfo 字段**：移除 `url`，新增 `fullPath`（含盘符绝对路径）和 `relativePath`（含 base-path 目录名的相对路径）
3. **字节数组优先**：`RequestUploadProcessor` 一次性调用 `file.getBytes()`，后续校验和上传都基于内存字节，避免 Windows 下 Tomcat 临时文件双重锁定；`UploadStrategy` 接口提供 `upload(byte[], originalFilename)` 默认方法
4. **FTP 错误分类**：统一通过 `classifyFtpError(replyCode, reply, operation)` 将 FTP 原始回复码转换为用户友好的中文提示

---

## 快速开始

### 前置条件

- JDK 1.8+
- Maven 3.6+

### 构建与运行

```bash
# 编译
mvn clean package -DskipTests

# 运行
java -jar target/file-upload-service-1.0.0-SNAPSHOT.jar

# 或直接启动（开发模式）
mvn spring-boot:run
```

服务启动后监听 `http://localhost:8080`

---

## API 使用示例

### 1. 单文件上传

```bash
# 上传到本地磁盘
curl -X POST "http://localhost:8080/api/files/upload?uploadType=local&uploadedBy=admin" \
  -F "file=@test.png"

# 上传到 FTP
curl -X POST "http://localhost:8080/api/files/upload?uploadType=ftp" \
  -F "file=@photo.jpg"

# 上传到 OSS
curl -X POST "http://localhost:8080/api/files/upload?uploadType=oss" \
  -F "file=@archive.zip"
```

**响应示例：**
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "id": "a1b2c3d4e5f6...",
    "originalFilename": "test.png",
    "pureFilename": "test",
    "suffix": ".png",
    "storageType": "local",
    "fileKey": "f2289cc437bd4401ae9096c19d709666_test.png",
    "fullPath": "C:\\file-upload-storage\\f2289cc437bd4401ae9096c19d709666_test.png",
    "relativePath": "file-upload-storage/f2289cc437bd4401ae9096c19d709666_test.png",
    "storedSize": 1024,
    "md5": "d41d8cd98f00b204e9800998ecf8427e",
    "uploadedBy": "admin",
    "uploadTime": "2026-09-05T12:00:00.000+00:00"
  }
}
```

### 2. 批量上传（FTP 复用连接）

```bash
curl -X POST "http://localhost:8080/api/files/upload/batch?uploadType=ftp" \
  -F "files=@image1.jpg" \
  -F "files=@image2.jpg" \
  -F "files=@image3.jpg"
```

### 3. 下载文件

```bash
curl -O -J "http://localhost:8080/api/files/download?fileKey=f2289cc437bd4401ae9096c19d709666_test.png&uploadType=local"
```

### 4. 删除文件

```bash
curl -X DELETE "http://localhost:8080/api/files/a1b2c3d4e5f6...?uploadType=local"
```

### 5. 查询文件列表

```bash
# 分页查询
curl "http://localhost:8080/api/files?page=1&size=10"

# 按关键词搜索
curl "http://localhost:8080/api/files?keyword=banner"

# 按存储类型过滤
curl "http://localhost:8080/api/files?storageType=local"

# 按后缀过滤
curl "http://localhost:8080/api/files?suffix=.png"

# 组合查询
curl "http://localhost:8080/api/files?keyword=test&storageType=local&suffix=.png&page=1&size=20"
```

### 6. 按 ID 查询单条

```bash
curl "http://localhost:8080/api/files/a1b2c3d4e5f6..."
```

### 7. Mock 数据管理

```bash
# 重新初始化 Mock 数据（8 条）
curl -X POST "http://localhost:8080/api/files/mock/init"

# 查看当前数据量
curl "http://localhost:8080/api/files/mock/count"
```

---

## 配置说明

### application.yml

```yaml
server:
  port: 8080

spring:
  servlet:
    multipart:
      enabled: true
      max-file-size: 50MB      # 单文件最大 50MB
      max-request-size: 50MB   # 请求总大小最大 50MB

file:
  upload:
    base-path: /file-upload-storage/   # /开头 = 盘符根目录；否则为相对路径
  ftp:
    host: 127.0.0.1
    port: 21
    username: user0
    password: 123456789
    basePath: /                        # FTP 远程存储根目录
  oss:
    endpoint: https://oss-cn-hangzhou.aliyuncs.com
    access-key-id: LTAI5xxx
    access-key-secret: xxx
    bucket-name: my-bucket
    basePath: files/

logging:
  level:
    com.example.fileupload: DEBUG      # 开启 DEBUG 查看详细日志
```

### 路径语义说明

| 配置值 | Windows 解析结果 | Linux 解析结果 |
|--------|-----------------|----------------|
| `/file-upload-storage/` | `C:\file-upload-storage\` | `/file-upload-storage` |
| `data/uploads/` | `<项目目录>\data\uploads\` | `<项目目录>/data/uploads/` |
| `/tmp/uploads/` | `C:\tmp\uploads\` | `/tmp/uploads` |

---

## 错误码与提示

### HTTP 状态码

| 状态码 | 含义 |
|--------|------|
| 200 | 成功 |
| 400 | 请求参数错误（非法后缀、魔数不匹配、未知 uploadType） |
| 404 | 文件不存在 |
| 500 | 服务器内部错误（FTP 连接失败、权限不足等） |

### FTP 专用错误提示

| FTP 回复码 | 用户提示 |
|-----------|----------|
| 550 | `FTP 权限不足或文件不存在，操作失败: {reply}` |
| 553 | `FTP 路径无效或文件名不允许，操作失败: {reply}` |
| 421 | `FTP 服务不可用或连接超时，操作失败: {reply}` |
| 425 | `FTP 数据连接建立失败，请检查防火墙/被动模式配置: {reply}` |
| 426 | `FTP 数据传输中断，操作失败: {reply}` |
| 552 | `FTP 磁盘空间不足，操作失败: {reply}` |

---

## 测试

```bash
# 运行全部测试
mvn test

# 测试结果
Tests run: 48, Failures: 0, Errors: 0, Skipped: 2
BUILD SUCCESS
```

**跳过说明：**
- `should_upload_to_ftp_mock` — 需要真实 FTP 服务器，单文件上传现在走真实连接
- `ftp_strategy_lifecycle` — 需要真实 FTP 服务器进行完整生命周期测试

### 测试覆盖

| 测试类 | 测试数 | 覆盖范围 |
|--------|--------|----------|
| `FileControllerIntegrationTest` | 16 | REST API 全链路（上传/下载/删除/查询/Mock） |
| `FileStorageServiceTest` | 10 | 内存存储 CRUD、搜索、分页 |
| `FileTypeValidatorTest` | 13 | 魔数校验（PNG/JPG/GIF/BMP/PDF/ZIP/RAR）、后缀白名单（17 种）、边界情况 |
| `StrategyIntegrationTest` | 6 | Local/OSS 策略生命周期（FTP 已禁用） |
| `UploadTypeTest` | 3 | 枚举转换、fromCode 容错 |

---

## 扩展指南

### 添加新的存储后端

1. 创建新策略类实现 `UploadStrategy` 接口
2. 在 `UploadType` 枚举中添加新类型
3. 添加 `@Component` 注解，工厂会自动注册
4. 可选：在 `application.yml` 中添加对应配置

```java
@Component
public class S3UploadStrategy implements UploadStrategy {
    @Override
    public UploadResult upload(MultipartFile file, String originalFilename) { ... }
    
    @Override
    public boolean delete(String fileKey) { ... }
    
    @Override
    public byte[] download(String fileKey) { ... }
    
    @Override
    public UploadType getUploadType() { return UploadType.S3; }
}
```

### 替换 OSS Mock 为真实实现

修改 `OssUploadStrategy`，引入阿里云 OSS SDK：

```xml
<dependency>
    <groupId>com.aliyun.oss</groupId>
    <artifactId>aliyun-sdk-oss</artifactId>
    <version>3.17.1</version>
</dependency>
```

然后在 `upload()` 中使用 `OSSClient.putObject()` 替代内存 Map。

---

## 常见问题

### Q: Windows 下上传后提示 "Cannot delete temporary file"？

**A:** 已修复。`RequestUploadProcessor` 改为一次性读取 `file.getBytes()`，避免 Tomcat 重复锁定临时文件。

### Q: FTP 上传中文文件名乱码？

**A:** 已配置 `client.setControlEncoding("UTF-8")` + `BINARY_FILE_TYPE`，确保文件名和内容均无乱码。

### Q: FTP 批量上传速度慢？

**A:** `FtpBatchUploader` 使用 ThreadLocal 连接池，同一线程内多个文件复用单个 TCP 连接，避免重复握手。

### Q: 如何切换到真实 FTP 服务器？

**A:** 修改 `application.yml` 中的 `file.ftp.host/port/username/password/basePath` 为实际值即可。单文件和批量上传都会使用真实连接。

---

## 许可证

MIT License
