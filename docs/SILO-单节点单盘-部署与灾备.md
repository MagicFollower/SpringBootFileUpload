# SILO 单节点单盘（SNSD）生产部署与灾备指南

> 归档日期：2026-09-08
> 拓扑代号：SNSD（Single-Node Single-Drive）
> 适用版本：SILO（MinIO 兼容对象存储）
> 参考来源：[SILO 官方文档](https://silo.pgsty.com/zh/)

---

## 一、拓扑概述与适用场景

### 1.1 拓扑描述

单节点单盘（SNSD）是最简单的部署拓扑：一台主机、一块磁盘（或一个目录）。
数据目录结构：

```
/mnt/drive1/minio/
├── .minio.sys/           # 元数据（IAM、策略、桶配置）
└── my-bucket/            # 桶目录（对象以原始文件存储）
```

### 1.2 适用场景

| 场景 | 推荐度 | 说明 |
|------|--------|------|
| 开发/测试环境 | 推荐 | 快速启动，零配置 |
| 评估/实验 | 推荐 | 验证功能与兼容性 |
| 轻量级生产（可容忍停机） | 谨慎 | 需配合外部备份策略 |
| 高可用生产环境 | 不推荐 | 无冗余，单点故障即数据丢失 |

### 1.3 重要限制

- **不支持纠删码**：无数据冗余保护，磁盘故障 = 数据丢失
- **不支持存储扩展**：不能通过添加 Server Pool 扩容
- **不支持站点复制**：单节点拓扑无法配置 Site Replication

---

## 二、前置条件

### 2.1 硬件要求

| 项目 | 最低要求 | 生产建议 |
|------|---------|---------|
| CPU | 2 核 | 4 核+ |
| 内存 | 2 GB | 8 GB+ |
| 磁盘 | 任意可用空间 | SSD，独立数据盘 |
| 网络 | 千兆 | 万兆（大文件场景） |

### 2.2 软件要求

- Linux：Ubuntu 20.04+ / RHEL 8+ / 兼容发行版
- Windows：Windows Server 2019+ / Windows 10+
- SILO Server 二进制文件（从 [下载页](https://silo.pgsty.com/zh/download/) 获取）
- mcli 客户端（可选，用于管理）

---

## 三、Linux 环境部署步骤

### 3.1 安装

#### 方式一：DEB 包（Ubuntu/Debian）

```bash
# 下载
wget https://github.com/pgsty/silo/releases/download/RELEASE.2026-09-03T13-18-01Z/silo_20260903131801.0.0_amd64.deb

# 校验（可选）
sha256sum silo_20260903131801.0.0_amd64.deb

# 安装
sudo dpkg -i silo_20260903131801.0.0_amd64.deb
```

#### 方式二：RPM 包（RHEL/Rocky/Alma）

```bash
sudo rpm -ivh silo-20260903131801.0.0-1PGSTY.x86_64.rpm
```

#### 方式三：二进制直接部署

```bash
wget https://github.com/pgsty/silo/releases/download/RELEASE.2026-09-03T13-18-01Z/silo-linux-amd64
chmod +x silo-linux-amd64
sudo mv silo-linux-amd64 /usr/local/bin/minio
```

### 3.2 配置

#### 创建专用用户

```bash
sudo groupadd -r minio-user
sudo useradd -M -r -g minio-user minio-user
```

#### 创建数据目录并授权

```bash
sudo mkdir -p /mnt/drive1/minio
sudo chown -R minio-user:minio-user /mnt/drive1/minio
```

#### 配置环境变量文件

```bash
sudo vi /etc/default/minio
```

写入以下内容：

```ini
# 单节点单盘：指定单个目录路径
MINIO_VOLUMES="/mnt/drive1/minio"

# 命令行选项
MINIO_OPTS="--console-address :9001"

# 管理员凭证（生产环境务必修改）
MINIO_ROOT_USER=minioadmin
MINIO_ROOT_PASSWORD=your-strong-password-here
```

#### TLS 证书配置（生产环境必须启用）

```bash
sudo mkdir -p /opt/minio/certs
sudo chown -R minio-user:minio-user /opt/minio/certs

# 放置证书文件
sudo cp private.key /opt/minio/certs/
sudo cp public.crt /opt/minio/certs/
```

更新环境变量文件，添加证书目录：

```ini
MINIO_OPTS="--console-address :9001 --certs-dir /opt/minio/certs"
```

同时需将 `MINIO_VOLUMES` 中的协议改为 `https://`：

```ini
MINIO_VOLUMES="https://localhost:9000/mnt/drive1/minio"
```

### 3.3 启动与验证

#### systemd 服务启动

```bash
# 启用并启动服务
sudo systemctl enable minio
sudo systemctl start minio

# 检查状态
sudo systemctl status minio
```

#### 验证服务

```bash
# 检查 API 端口
curl -k https://localhost:9000/minio/health/live

# 使用 mcli 检查
mcli alias set local https://localhost:9000 minioadmin your-strong-password-here
mcli admin info local
```

浏览器访问 `https://localhost:9001` 进入 MinIO Console。

---

## 四、Windows 环境部署步骤

### 4.1 安装

1. 从 [下载页](https://silo.pgsty.com/zh/download/) 获取 Windows 归档
2. 校验并解压得到 `minio.exe`
3. 将 `minio.exe` 放置到固定目录（如 `C:\silo\`）

### 4.2 配置

#### 设置系统环境变量

```powershell
# 以管理员身份运行 PowerShell
[System.Environment]::SetEnvironmentVariable("MINIO_ROOT_USER", "minioadmin", "Machine")
[System.Environment]::SetEnvironmentVariable("MINIO_ROOT_PASSWORD", "your-strong-password-here", "Machine")
```

#### TLS 证书配置

```powershell
# 创建证书目录
New-Item -ItemType Directory -Force -Path "C:\silo\certs"

# 放置证书文件
Copy-Item private.key "C:\silo\certs\"
Copy-Item public.crt "C:\silo\certs\"
```

#### 创建数据目录

```powershell
New-Item -ItemType Directory -Force -Path "C:\minio-data"
```

### 4.3 使用 NSSM 注册为 Windows 服务

由于 Windows 没有 systemd，推荐使用 [NSSM](https://nssm.cc/) 将 SILO 注册为系统服务：

```powershell
# 下载 NSSM 并解压到 PATH 可达的位置
# 注册服务
nssm install SiloServer "C:\silo\minio.exe" "server C:\minio-data --console-address :9001 --certs-dir C:\silo\certs"

# 设置服务描述
nssm set SiloServer Description "SILO Object Storage Server"

# 设置启动目录
nssm set SiloServer AppDirectory "C:\silo"

# 启动服务
nssm start SiloServer
```

#### 验证服务

```powershell
# 检查服务状态
nssm status SiloServer

# 访问 API
Invoke-WebRequest -Uri "http://localhost:9000/minio/health/live"

# 浏览器访问 Console：http://localhost:9001
```

---

## 五、生产级备份与恢复

### 5.1 在线备份（mc mirror）

无需停服，通过 S3 协议同步对象到远端存储：

```bash
# 配置本地别名
mcli alias set local http://localhost:9000 minioadmin your-password

# 配置远端别名（以另一个 S3 兼容存储为例）
mcli alias set backup https://backup-storage.example.com backupkey backupsecret

# 执行镜像同步
mcli mirror --overwrite --remove local/my-bucket backup/my-bucket-backup
```

**特点：**
- 无需停服
- 仅同步当前对象，不含版本历史
- 支持增量同步（仅传输变更文件）
- 适合每日定时执行

**定时任务（Linux cron）：**

```bash
# 每天凌晨 2 点执行备份
0 2 * * * /usr/local/bin/mcli mirror --overwrite local/my-bucket backup/my-bucket-backup >> /var/log/minio-backup.log 2>&1
```

### 5.2 离线备份（停服压缩）

**重要：必须停服后再压缩，确保数据一致性。**

#### Linux 环境

```bash
# 1. 停止服务
sudo systemctl stop minio

# 2. 压缩整个数据目录
sudo tar -czf /backup/minio-snsd-$(date +%Y%m%d).tar.gz -C /mnt/ drive1/

# 3. 上传到云盘/远端存储
# 使用 ossutil / rclone / scp 等方式

# 4. 恢复服务
sudo systemctl start minio
```

#### Windows 环境

```powershell
# 1. 停止服务
nssm stop SiloServer

# 2. 压缩数据目录（使用 7-Zip 或 tar）
tar -czf D:\backup\minio-snsd-$(Get-Date -Format yyyyMMdd).tar.gz -C C:\ minio-data

# 3. 上传到云盘

# 4. 恢复服务
nssm start SiloServer
```

### 5.3 元数据备份

无论使用哪种备份方式，建议额外导出元数据：

```bash
# 导出桶元数据（策略、生命周期、通知等）
mcli admin cluster bucket export local --output /backup/bucket-metadata-$(date +%Y%m%d).zip

# 导出 IAM 配置（用户、组、策略、角色）
mcli admin cluster iam export local --output /backup/iam-config-$(date +%Y%m%d).zip
```

### 5.4 站点复制灾备

> **注意：SNSD 单节点单盘拓扑不支持站点复制（Site Replication）。**
> 如需站点级灾备，请升级到 SNMD 或 MNMD 拓扑。

### 5.5 故障场景与恢复流程

#### 场景 1：磁盘损坏 / 数据丢失

| 影响 | 恢复方式 |
|------|---------|
| 全部数据丢失 | 从最近的备份恢复 |

**恢复步骤：**

```bash
# 1. 停止服务
sudo systemctl stop minio

# 2. 更换磁盘，格式化挂载
sudo mkfs.xfs /dev/sdb1
sudo mount /dev/sdb1 /mnt/drive1

# 3. 解压备份
sudo tar -xzf /backup/minio-snsd-20260908.tar.gz -C /mnt/

# 4. 恢复权限
sudo chown -R minio-user:minio-user /mnt/drive1/minio

# 5. 恢复元数据（如需要）
mcli admin cluster bucket import local --input /backup/bucket-metadata-20260908.zip
mcli admin cluster iam import local --input /backup/iam-config-20260908.zip

# 6. 启动服务
sudo systemctl start minio
```

#### 场景 2：服务崩溃（数据完好）

```bash
sudo systemctl restart minio
```

#### 场景 3：整机故障

从备份恢复到新机器，修改配置指向新路径即可。

---

## 六、运维建议

### 6.1 监控

```bash
# 启用 Prometheus 指标
# 在 MINIO_OPTS 中添加：
MINIO_OPTS="--console-address :9001 --address :9000"

# Prometheus 抓取地址：http://localhost:9000/minio/v2/metrics/cluster
```

### 6.2 告警

| 告警项 | 阈值建议 | 说明 |
|--------|---------|------|
| 磁盘使用率 | > 80% | 及时清理或扩容 |
| 服务健康检查 | 连续 3 次失败 | 触发重启或通知 |
| API 响应时间 | P99 > 5s | 排查性能瓶颈 |

### 6.3 升级

```bash
# Linux
sudo systemctl stop minio
# 替换二进制文件
sudo dpkg -i silo-new-version.deb
sudo systemctl start minio
```

### 6.4 备份策略总结

| 频率 | 操作 | 方式 |
|------|------|------|
| 每日 | 对象增量备份 | `mcli mirror --overwrite` |
| 每周 | 完整快照 | 停服 → 压缩 → 上传 → 恢复服务 |
| 每月 | 元数据快照 | `mcli admin cluster bucket export` + `iam export` |

---

## 七、参考来源

- [SILO 下载与安装](https://silo.pgsty.com/zh/download/)
- [SILO 在 Ubuntu 上部署](https://silo.pgsty.com/operations/deployments/baremetal-deploy-minio-on-ubuntu-linux/)
- [SILO 在 Windows 上部署](https://silo.pgsty.com/zh/operations/deployments/baremetal-deploy-minio-on-windows/)
- [SILO mc mirror 文档](https://silo.pgsty.com/zh/reference/minio-mc/mc-mirror/)
- [SILO 核心管理概念](https://silo.pgsty.com/zh/administration/concepts/)
- [SILO 术语表 — SNSD](https://silo.pgsty.com/zh/glossary/)
