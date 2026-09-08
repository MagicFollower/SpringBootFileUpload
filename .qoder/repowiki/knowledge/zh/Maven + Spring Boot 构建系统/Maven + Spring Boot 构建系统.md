---
kind: build_system
name: Maven + Spring Boot 构建系统
category: build_system
scope:
    - '**'
source_files:
    - pom.xml
---

## 1. 使用的构建系统

本项目采用 **Maven** 作为唯一构建与依赖管理工具，基于 `spring-boot-starter-parent`（版本 2.7.18）作为父 POM，使用 `spring-boot-maven-plugin` 打包为可执行的 Spring Boot Fat Jar。Java 目标版本固定为 1.8。

## 2. 关键文件

- `pom.xml`：项目唯一构建描述文件，声明所有依赖、插件及构建配置。
- `src/main/resources/application.yml`：运行时配置文件（由 Spring Boot 自动加载）。
- `target/`：Maven 默认构建输出目录，存放编译产物与打包结果。

## 3. 架构与约定

### 依赖管理
- 通过继承 `spring-boot-starter-parent` 获得统一的依赖版本管理与默认插件行为。
- 核心依赖包括：`spring-boot-starter-web`（Web 服务）、`spring-boot-starter-test` + `junit-platform-launcher`（测试）、`commons-io`、`commons-net`（FTP）、`minio`（对象存储）、`commons-codec`（MD5 摘要）。
- 所有第三方库版本号在 `<dependencies>` 中显式声明，未使用 BOM 或属性集中管理。

### 构建生命周期
- 标准 Maven 生命周期：`mvn compile` → `mvn test` → `mvn package`。
- `spring-boot-maven-plugin` 负责将应用打包为可执行 jar（包含所有依赖），可通过 `java -jar target/file-upload-service-1.0.0-SNAPSHOT.jar` 直接运行。
- 无自定义 `<goals>`、`<profiles>` 或多模块结构，构建配置保持极简。

### 测试集成
- 测试类位于 `src/test/java`，遵循 Maven 默认约定。
- 使用 JUnit Platform（`junit-platform-launcher` 1.8.2）运行测试，可通过 `mvn test` 触发。

## 4. 约定与约束

- **Java 版本约束**：`<properties><java.version>1.8</java.version></properties>` 强制要求 JDK 1.8 进行编译与运行。
- **包名与坐标**：groupId=`com.example`，artifactId=`file-upload-service`，version=`1.0.0-SNAPSHOT`，遵循 Maven 坐标规范。
- **无 CI/CD 流水线**：仓库根目录未发现 `.github/workflows`、`.gitlab-ci.yml`、`Jenkinsfile`、`Dockerfile`、`Makefile` 等 CI/CD 或容器化配置，构建完全依赖本地 Maven。
- **无多环境配置**：仅存在单一 `application.yml`，未使用 Spring Profile 或外部化配置策略。
- **版本策略**：采用语义化版本前缀 `1.0.0-SNAPSHOT`，快照版本用于开发阶段，发布时需手动修改为 Release 版本。
- **插件最小化**：仅启用 `spring-boot-maven-plugin`，未引入代码覆盖率、静态检查、源码打包等额外插件。

## 5. 适用性说明

该构建系统简单直接，适合小型单体 Spring Boot 项目；对于需要多环境部署、自动化发布或容器化的场景，当前仓库尚未提供相应支持。