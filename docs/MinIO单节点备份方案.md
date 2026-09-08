# MinIO 单节点单磁盘定期备份方案

> 归档日期：2026-09-08
> 适用环境：SILO（MinIO 兼容对象存储）单节点单磁盘部署
> 参考来源：[SILO 官方文档](https://silo.pgsty.com/zh/)、MinIO GitHub Issues

---

## 一、问题描述

### 1.1 背景

项目使用 SILO（<https://silo.pgsty.com>）作为本地 MinIO 兼容对象存储服务，采用**单节点单磁盘**部署模式。
数据目录结构如下：

```
data/
├── .minio.sys/           # 元数据目录（IAM、策略、桶配置等）
│   ├── buckets/
│   ├── config/
│   └── ...
├── my-bucket/            # 桶目录（对象以原始文件存储）
│   ├── files/
│   │   ├── uuid_file1.png
│   │   └── uuid_file2.jpg
│   └── ...
└── another-bucket/
```

### 1.2 核心问题

1. 如何将当前节点数据**定期备份到云盘**？
2. **直接压缩 data 目录上传到云盘，之后解压恢复**，这种方式是否可行？

---

## 二、分析与思考

### 2.1 官方文档的关键约束

SILO 官方文档（继承自 MinIO）在多处明确强调**磁盘独占访问**原则：

> **磁盘独占访问**
>
> MinIO 要求对用于对象存储的磁盘或卷拥有**独占**访问权限。
> 任何其他进程、软件、脚本或人员都不应直接对提供给 MinIO 的磁盘或卷，
> 或 MinIO 在其上放置的对象或文件执行**任何**操作。
>
> 除非得到 MinIO Engineering 的明确指示，否则不要使用脚本或工具直接修改、
> 删除或移动这些磁盘上的任何数据分片、校验分片或元数据文件，包括在磁盘或节点
> 之间迁移这些文件。这类操作极有可能导致大范围损坏和数据丢失，超出 MinIO 的自愈能力。
>
> —— 来源：[SILO 核心管理概念](https://silo.pgsty.com/zh/administration/concepts/) / [纠删码](https://silo.pgsty.com/zh/operations/concepts/erasure-coding/)

这意味着：**在 MinIO 运行时直接压缩 data 目录存在一致性风险**。

### 2.2 单节点单磁盘的特殊性

单节点单磁盘模式下，MinIO/SILO 的行为与分布式部署有本质区别：

| 特性 | 单节点单磁盘 | 分布式多磁盘 |
|------|------------|------------|
| 纠删码 | **不启用** | 启用（数据分片 + 校验分片） |
| 对象存储方式 | 原始文件直接存储在桶目录 | 切分为多个分片分布在各磁盘 |
| 文件系统级备份 | **可行**（对象是完整文件） | **不可行**（分片无法独立使用） |
| `.minio.sys` | 包含 IAM、策略、桶配置 | 还包含纠删码元数据 |

因此，对于单节点单磁盘场景，文件系统级备份在技术上是可行的——但必须保证一致性。

### 2.3 SILO 官方提供的备份方式

根据 SILO 官方文档，MinIO 提供以下三种数据复制/备份机制：

#### （1）mc mirror —— 在线镜像同步

```
mc mirror 命令用于将内容同步到 MinIO 部署，类似于 rsync 工具。
mc mirror 仅同步当前对象，不包含任何版本信息或元数据。
```

来源：[SILO mc mirror 文档](https://silo.pgsty.com/zh/reference/minio-mc/mc-mirror/)

#### （2）存储桶复制（Bucket Replication）

通过 `mc replicate` 配置，支持：
- 单向（主动-被动）复制，适用于归档场景
- 双向（主动-主动）复制，保持两个存储桶同步
- **包含版本历史和元数据**

#### （3）站点复制（Site Replication）

以双向、主动-主动复制方式运行，保持多个数据中心彼此同步。

#### （4）元数据导出/导入

```bash
mc admin cluster bucket export   # 导出桶元数据
mc admin cluster iam export      # 导出 IAM 配置
```

### 2.4 思考：压缩 data 目录的可行性判定

综合以上分析：

| 场景 | 是否可行 | 原因 |
|------|---------|------|
| 运行中直接压缩 | **不推荐** | 写入操作可能导致 `.minio.sys` 与实际对象不一致 |
| 停服后压缩 | **可行** | 文件系统处于一致状态，对象是完整文件 |
| 恢复时解压到任意路径 | **可行** | SILO 启动时自动识别目录结构 |
| 仅恢复部分桶 | **不推荐** | `.minio.sys` 中的元数据可能与实际桶不匹配 |

---

## 三、最终答案

### 3.1 直接压缩 data 目录上传后解压恢复——可以，但必须满足两个条件

1. **压缩前停止 SILO 服务**（确保数据一致性）
2. **恢复时完整还原**（data 目录 + `.minio.sys` 一起解压，不能只还原部分桶）

如果希望**零停机备份**，应使用 `mc mirror` 通过 S3 协议在线同步。

### 3.2 三种备份方案详细对比

| 方案 | 一致性保证 | 是否需停服 | 备份版本/元数据 | 适用场景 |
|------|-----------|-----------|---------------|---------|
| **方案 A：停服压缩** | 强一致 | 是 | 完整（含 .minio.sys） | 数据量小、允许短暂停机 |
| **方案 B：mc mirror** | 最终一致 | 否 | 仅当前对象，不含版本 | 在线备份、增量同步 |
| **方案 C：存储桶复制** | 强一致 | 否 | 完整（含版本+元数据） | 需要完整灾备 |

---

## 四、操作指南

### 4.1 方案 A：停服压缩（文件系统级备份）

#### 备份步骤

```bash
# 1. 停止 SILO 服务
systemctl stop silo        # 或你的服务名

# 2. 压缩整个 data 目录（含 .minio.sys）
tar -czf minio-backup-$(date +%Y%m%d).tar.gz -C /path/to/ data/

# 3. 上传到云盘（阿里云 OSS / 网盘等）
# 使用 ossutil / rclone / 手动上传

# 4. 恢复服务
systemctl start silo
```

#### 恢复步骤

```bash
# 1. 停止服务
systemctl stop silo

# 2. 解压到新的 data 目录
tar -xzf minio-backup-20260908.tar.gz -C /new/path/to/

# 3. 修改启动配置指向新目录（如需要）
# 4. 启动服务
systemctl start silo
```

#### 注意事项

- 必须停服后再压缩，否则运行中的写入操作可能导致压缩包里 `.minio.sys` 元数据与实际对象不一致
- `.minio.sys` 目录必须一起备份，它包含 IAM 用户、策略、桶配置等关键元数据
- 恢复时目录路径可以与原路径不同，SILO 启动时会自动识别

### 4.2 方案 B：mc mirror 在线备份（推荐日常使用）

```bash
# 配置本地 SILO 别名
mcli alias set local http://127.0.0.1:9000 minioadmin minioadmin

# 配置远程云存储别名（以另一个 S3 兼容存储为例）
mcli alias set cloud https://cloud-storage-endpoint accesskey secretkey

# 执行镜像同步（仅同步当前对象，不含版本历史）
mcli mirror --overwrite --remove local/my-bucket cloud/backup-bucket
```

关键参数说明：

| 参数 | 作用 |
|------|------|
| `--overwrite` | 覆盖目标中已存在的同名对象 |
| `--remove` | 删除目标中源端已不存在的对象 |
| `--newer-than` | 仅同步指定时间之后的对象 |
| `--dry-run` | 模拟执行，不实际传输 |
| `--watch` | 持续监视变更并实时同步 |

### 4.3 方案 C：存储桶复制（完整灾备）

```bash
# 启用版本控制
mcli version enable local/my-bucket
mcli version enable remote/my-bucket

# 配置复制规则
mcli replicate add local/my-bucket --remote-bucket remote/my-bucket

# 全量重新同步（首次）
mcli replicate resync local/my-bucket
```

### 4.4 补充：元数据备份

无论选择哪种方案，建议额外导出桶元数据和 IAM 配置：

```bash
# 导出桶元数据
mcli admin cluster bucket export local --output bucket-metadata.zip

# 导出 IAM 配置（用户、策略、角色等）
mcli admin cluster iam export local --output iam-config.zip
```

恢复时可用对应 import 命令导入。

---

## 五、推荐备份策略

针对本项目 SILO 单节点单磁盘部署（`127.0.0.1:9000`，bucket `my-bucket`），推荐组合策略：

| 频率 | 操作 | 方式 |
|------|------|------|
| **每日** | 对象增量备份 | `mcli mirror --overwrite local/my-bucket cloud/backup` |
| **每周** | 完整快照 | 停服 → 压缩 data → 上传云盘 → 恢复服务 |
| **每月** | 元数据快照 | `mcli admin cluster bucket export` + `iam export` |

---

## 六、参考来源

- [SILO 核心管理概念 — 备份与恢复](https://silo.pgsty.com/zh/administration/concepts/#如何在-minio-上备份和恢复对象)
- [SILO mc mirror 文档](https://silo.pgsty.com/zh/reference/minio-mc/mc-mirror/)
- [SILO 纠删码 — 磁盘独占访问](https://silo.pgsty.com/zh/operations/concepts/erasure-coding/)
- [SILO 站点复制概览](https://silo.pgsty.com/zh/operations/replication/multi-site-replication/)
- [MinIO GitHub Issue #4398 — Taking Backups of MinIO](https://github.com/minio/minio/issues/4398)
