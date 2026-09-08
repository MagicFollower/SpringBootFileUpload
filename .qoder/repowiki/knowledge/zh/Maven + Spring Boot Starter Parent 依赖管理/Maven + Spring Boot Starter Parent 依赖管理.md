---
kind: dependency_management
name: Maven + Spring Boot Starter Parent 依赖管理
category: dependency_management
scope:
    - '**'
source_files:
    - pom.xml
    - .gitignore
---

## 1. 使用的系统/方法

该项目使用 **Maven** 作为构建与依赖管理工具，并通过继承 `spring-boot-starter-parent`（版本 2.7.18）来复用 Spring Boot 官方提供的依赖版本管理。Java 版本通过 `<properties>` 中的 `java.version=1.8` 统一声明。

## 2. 关键文件

- `pom.xml`：唯一的依赖清单，集中声明所有第三方库及其版本。
- `.gitignore`：忽略 `target/` 等构建产物，避免将本地仓库缓存提交到版本控制。

## 3. 架构与约定

- **父 POM 管理**：项目以 `org.springframework.boot:spring-boot-starter-parent:2.7.18` 为父工程，从而继承 Spring Boot 对常用依赖（如 Spring、Jackson、Tomcat 等）的 BOM 版本管理，无需在子模块中显式指定这些传递依赖的版本。
- **直接依赖显式声明**：所有业务相关依赖均在 `<dependencies>` 中显式声明版本号，包括：
  - `spring-boot-starter-web`：Web 服务基础能力。
  - `spring-boot-starter-test` + `junit-platform-launcher:1.8.2`：测试框架，其中 JUnit Platform Launcher 单独指定了 1.8.2 版本。
  - `commons-io:2.13.0`：文件操作工具。
  - `commons-net:3.9.0`：FTP 客户端实现。
  - `io.minio:minio:8.5.7`：MinIO S3 兼容对象存储 SDK。
  - `commons-codec:1.16.0`：MD5 摘要计算（注释说明此前由 aliyun-sdk-oss 传递引入，现改为显式声明）。
- **构建插件**：仅使用 `spring-boot-maven-plugin`，用于打包可执行 JAR。
- **无私有仓库配置**：`pom.xml` 中未定义任何 `<repositories>` 或 `<pluginRepositories>`，默认使用 Maven Central。
- **无依赖锁定文件**：项目中不存在 `dependencyManagement` 子模块、`versions.properties`、`BOM` 文件或 `mvn dependency:tree` 生成的锁定文件；依赖版本全部内联于 `pom.xml`。
- **无 vendor/ 目录**：所有第三方库通过 Maven 中央仓库下载并缓存在本地 `~/.m2/repository`，未被 vendoring 进代码库。

## 4. 约定与约束

- **版本来源约定**：Spring Boot 生态相关依赖（starter、web、test 等）不显式写版本号，交由父 POM 管理；非 Spring 生态的第三方库（commons-io、commons-net、minio、commons-codec、junit-platform-launcher）必须显式声明具体版本。
- **测试依赖隔离**：测试专用依赖（`spring-boot-starter-test`、`junit-platform-launcher`）均设置 `<scope>test</scope>`，确保生产包不包含测试依赖。
- **单一依赖入口**：所有依赖集中在根级 `pom.xml`，项目为单模块结构，不存在多模块 `dependencyManagement` 共享版本。
- **版本更新方式**：升级依赖需手动编辑 `pom.xml` 对应 `<version>` 标签；未发现自动化升级脚本或 CI 任务（如 Dependabot、Renovate）。