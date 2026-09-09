# 文件在线预览方案（kkFileView 4.4.0 对接）

> 归档日期：2026-09-09
> 适用版本：kkFileView 4.4.0 + file-upload-service 1.0.0

---

## 方案概述

本方案将 [kkFileView 4.4.0](https://github.com/kekingcn/kkFileView) 集成到文件上传服务中，实现 LOCAL / FTP / MinIO 三种存储后端的浏览器端在线预览。采用 **前端直调模式**（方案 A），后端仅提供预览 URL 生成和文件流端点，前端直接打开 kkFileView 预览页面。新增约 100 行代码，零回归风险。

---

## 文档索引

| 文档 | 内容 |
|------|------|
| [需求分析与方案设计](./01-需求分析与方案设计.md) | 需求背景、kkFileView 原理、项目现状分析、方案对比选型（A vs B）、六维度对比表、架构流程图 |
| [实现方案与落地代码](./02-实现方案与落地代码.md) | 变更文件清单、配置说明、核心代码、接口对比、使用示例、部署指南 |

---

## 关键技术决策

| 决策点 | 选择 | 理由 |
|--------|------|------|
| 对接模式 | 前端直调（方案 A） | 最小落地（约 100 行）、性能最优、完美复用策略模式 |
| 预览 URL 编码 | Base64 | kkFileView 标准要求，避免特殊字符解析失败 |
| 文件流端点 | 新建 `/previewFile` | 与 download 语义分离：inline vs attachment，动态 Content-Type |
| 文件类型识别 | `fullfilename` 参数 | 确保 kkFileView 正确识别无后缀的下载流 |
| 水印支持 | 配置化 `watermark-txt` | 可选参数，配置为空时不拼接 |

---

## 快速开始

### 1. 启动 kkFileView

```bash
docker run -d -p 8012:8012 keking/kkfileview:4.4.0
```

### 2. 配置本应用

```yaml
preview:
  kk-file-view:
    server-address: http://127.0.0.1:8012
  app-base-url: http://127.0.0.1:8080
```

### 3. 获取预览 URL

```bash
curl "http://localhost:8080/api/files/previewUrl?id={文件ID}"
```

### 4. 浏览器打开返回的 URL 即可预览

---

## 架构流程

```
前端 → GET /previewUrl → 后端返回 kkFileView URL
  ↓
前端 window.open(kkFileView URL)
  ↓
kkFileView → GET /previewFile → 后端返回文件流（inline）
  ↓
kkFileView 转换格式 → 返回可预览 HTML → 浏览器展示
```

---

## 新增 API

| 端点 | 说明 | 调用方 |
|------|------|--------|
| `GET /api/files/previewUrl?id=xxx` | 生成 kkFileView 预览 URL | 前端 |
| `GET /api/files/previewFile?fileKey=xxx&uploadType=local&fullfilename=report.pdf` | 以 inline 方式提供文件流 | kkFileView 服务器 |

---

## 相关文档

- [kkFileView 官方文档](https://kkview.cn)
- [kkFileView GitHub](https://github.com/kekingcn/kkFileView)
- [项目主 README](../../README.md)
