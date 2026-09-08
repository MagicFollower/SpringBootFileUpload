# SILO 单节点多盘（SNMD）生产部署与灾备指南

> 归档日期：2026-09-08
> 拓扑代号：SNMD（Single-Node Multi-Drive）
> 适用版本：SILO（MinIO 兼容对象存储）
> 参考来源：[SILO 官方文档](https://silo.pgsty.com/zh/)

---

## 一、拓扑概述与适用场景

### 1.1 拓扑描述

单节点多盘（SNMD）在一台主机上使用多块磁盘（或目录），启用纠删码提供数据冗余保护。

```
minio1.example.net
├── /mnt/drive1/minio/    # 磁盘 1
├── /mnt/drive2/minio/    # 磁盘 2
├── /mnt/drive3/minio/    # 磁盘 3
└── /mnt/drive4/minio/    # 磁盘 4
```

### 1.2 纠删码机制

SNMD 拓扑自动启用纠删码（Erasure Coding）。MinIO 将每个对象切分为数据分片和校验分片，分布存储在各磁盘上。

| 磁盘数 | 默认校验值 | 可用存储比例 | 可容忍故障磁盘数 |
|--------|-----------|-------------|----------------|
| 4 | EC:2 | 50% | 2 |
| 8 | EC:2~4 | 50%~75% | 2~4 |
| 16 | EC:4 | 75% | 4 |

### 1.3 适用场景

| 场景 | 推荐度 | 说明 |
|------|--------|------|
| 开发/测试环境 | 推荐 | 验证纠删码行为 |
| 中小规模生产 | 适用 | 可容忍单节点停机 |
| 高可用生产环境 | 不推荐 | 节点故障 = 全部数据不可用 |

### 1.4 重要限制

- **单点故障风险**：虽然磁盘级冗余存在，但节点故障时所有数据不可用
- **不支持跨节点扩展**：不能直接升级为多节点拓扑（需重新部署）
- **SILO 官方建议**：SNMD 适用于"可容忍节点停机导致的数据不可用"的较小存储工作负载

---

## 二、前置条件

### 2.1 硬件要求

| 项目 | 最低要求 | 生产建议 |
|------|---------|---------|
| CPU | 4 核 | 8 核+ |
| 内存 | 8 GB | 16 GB+ |
| 磁盘 | 4 块（任意） | 4+ 块独立物理磁盘，XFS |
| 网络 | 千兆 | 万兆 |

### 2.2 磁盘规划

**关键原则：**
- 每块磁盘使用独立物理磁盘（非分区），避免共享 I/O 瓶颈
- 使用 XFS 文件系统（MinIO 推荐）
- 每块磁盘挂载到独立挂载点

```bash
# 格式化磁盘为 XFS
sudo mkfs.xfs /dev/sdb1
sudo mkfs.xfs /dev/sdc1
sudo mkfs.xfs /dev/sdd1
sudo mkfs.xfs /dev/sde1

# 创建挂载点
sudo mkdir -p /mnt/drive{1..4}

# 挂载
sudo mount /dev/sdb1 /mnt/drive1
sudo mount /dev/sdc1 /mnt/drive2
sudo mount /dev/sdd1 /mnt/drive3
sudo mount /dev/sde1 /mnt/drive4

# 写入 fstab 实现开机自动挂载
echo '/dev/sdb1 /mnt/drive1 xfs defaults,noatime 0 2' | sudo tee -a /etc/fstab
echo '/dev/sdc1 /mnt/drive2 xfs defaults,noatime 0 2' | sudo tee -a /etc/fstab
echo '/dev/sdd1 /mnt/drive3 xfs defaults,noatime 0 2' | sudo tee -a /etc/fstab
echo '/dev/sde1 /mnt/drive4 xfs defaults,noatime 0 2' | sudo tee -a /etc/fstab
```

---

## 三、Linux 环境部署步骤

### 3.1 安装

```bash
# DEB 包安装
sudo dpkg -i silo_20260903131801.0.0_amd64.deb

# 或 RPM 包安装
sudo rpm -ivh silo-20260903131801.0.0-1PGSTY.x86_64.rpm
```

### 3.2 配置

#### 创建专用用户

```bash
sudo groupadd -r minio-user
sudo useradd -M -r -g minio-user minio-user
```

#### 创建数据目录并授权

```bash
for i in {1..4}; do
    sudo mkdir -p /mnt/drive${i}/minio
    sudo chown -R minio-user:minio-user /mnt/drive${i}/minio
done
```

#### 配置环境变量文件

```bash
sudo vi /etc/default/minio
```

写入以下内容：

```ini
# 单节点多盘：指定多个目录路径（使用扩展符号 {x...y}）
# 注意：生产环境建议启用 TLS，使用 https://
MINIO_VOLUMES="https://localhost:9000/mnt/drive{1...4}/minio"

# 命令行选项
MINIO_OPTS="--console-address :9001 --certs-dir /opt/minio/certs"

# 管理员凭证
MINIO_ROOT_USER=minioadmin
MINIO_ROOT_PASSWORD=your-strong-password-here
```

#### TLS 证书配置

```bash
sudo mkdir -p /opt/minio/certs
sudo chown -R minio-user:minio-user /opt/minio/certs
sudo cp private.key /opt/minio/certs/
sudo cp public.crt /opt/minio/certs/
```

### 3.3 启动与验证

```bash
sudo systemctl enable minio
sudo systemctl start minio
sudo systemctl status minio
```

#### 验证纠删码

```bash
mcli alias set local https://localhost:9000 minioadmin your-password
mcli admin info local
```

输出中应显示 4 个磁盘，且状态均为在线。

---

## 四、Windows 环境部署步骤

### 4.1 安装

1. 下载 Windows 归档并解压得到 `minio.exe`
2. 将 `minio.exe` 放置到固定目录（如 `C:\silo\`）

### 4.2 配置

#### 设置系统环境变量

```powershell
[System.Environment]::SetEnvironmentVariable("MINIO_ROOT_USER", "minioadmin", "Machine")
[System.Environment]::SetEnvironmentVariable("MINIO_ROOT_PASSWORD", "your-strong-password-here", "Machine")
```

#### 创建数据目录

```powershell
# 使用多个磁盘盘符
New-Item -ItemType Directory -Force -Path "D:\minio"
New-Item -ItemType Directory -Force -Path "E:\minio"
New-Item -ItemType Directory -Force -Path "F:\minio"
New-Item -ItemType Directory -Force -Path "G:\minio"
```

#### TLS 证书配置

```powershell
New-Item -ItemType Directory -Force -Path "C:\silo\certs"
Copy-Item private.key "C:\silo\certs\"
Copy-Item public.crt "C:\silo\certs\"
```

### 4.3 使用 NSSM 注册为 Windows 服务

```powershell
# 注册服务（多盘使用 {D...G} 扩展符号）
nssm install SiloServer "C:\silo\minio.exe" "server {D...G}:\minio --console-address :9001 --certs-dir C:\silo\certs"

nssm set SiloServer Description "SILO Object Storage Server (SNMD)"
nssm set SiloServer AppDirectory "C:\silo"

# 启动服务
nssm start SiloServer
```

#### 验证服务

```powershell
nssm status SiloServer
# 浏览器访问 Console：http://localhost:9001
```

---

## 五、生产级备份与恢复

### 5.1 在线备份（mc mirror）

```bash
# 配置别名
mcli alias set local https://localhost:9000 minioadmin your-password
mcli alias set backup https://backup-storage.example.com backupkey backupsecret

# 执行镜像同步
mcli mirror --overwrite --remove local/my-bucket backup/my-bucket-backup
```

**定时任务：**

```bash
# Linux cron
0 2 * * * /usr/local/bin/mcli mirror --overwrite local/my-bucket backup/my-bucket-backup >> /var/log/minio-backup.log 2>&1
```

### 5.2 离线备份（停服压缩）

**重要：停服后压缩整个数据目录（所有磁盘）。**

#### Linux 环境

```bash
# 1. 停止服务
sudo systemctl stop minio

# 2. 压缩所有磁盘数据
sudo tar -czf /backup/minio-snmd-$(date +%Y%m%d).tar.gz \
    -C /mnt/ drive1/ drive2/ drive3/ drive4/

# 3. 上传到远端

# 4. 恢复服务
sudo systemctl start minio
```

#### Windows 环境

```powershell
nssm stop SiloServer

# 压缩所有磁盘
tar -czf D:\backup\minio-snmd-$(Get-Date -Format yyyyMMdd).tar.gz D:\minio E:\minio F:\minio G:\minio

nssm start SiloServer
```

### 5.3 元数据备份

```bash
mcli admin cluster bucket export local --output /backup/bucket-metadata-$(date +%Y%m%d).zip
mcli admin cluster iam export local --output /backup/iam-config-$(date +%Y%m%d).zip
```

### 5.4 站点复制灾备

> **注意：SNMD 单节点拓扑不支持站点复制。**
> 如需站点级灾备，请升级到 MNMD 多节点拓扑。

### 5.5 故障场景与恢复流程

#### 场景 1：单磁盘故障

| 影响 | 恢复方式 |
|------|---------|
| 部分对象暂时不可用 | 纠删码自动自愈 |
| 需尽快更换磁盘 | 更换后 MinIO 自动重建数据 |

**处理步骤：**

```bash
# 1. 检查磁盘状态
mcli admin info local

# 2. 更换故障磁盘
sudo umount /mnt/drive2
# 物理更换磁盘
sudo mkfs.xfs /dev/sdc1
sudo mount /dev/sdc1 /mnt/drive2
sudo mkdir -p /mnt/drive2/minio
sudo chown -R minio-user:minio-user /mnt/drive2/minio

# 3. 重启服务触发自动重建
sudo systemctl restart minio
```

#### 场景 2：多磁盘故障（超出纠删码容忍）

| 影响 | 恢复方式 |
|------|---------|
| 数据丢失 | 从备份恢复 |

#### 场景 3：整机故障

| 影响 | 恢复方式 |
|------|---------|
| 全部数据不可用 | 从备份恢复到新机器 |

**恢复步骤：**

```bash
# 1. 在新机器上安装 SILO 并配置相同拓扑
# 2. 停止服务
sudo systemctl stop minio

# 3. 解压备份到各磁盘
sudo tar -xzf /backup/minio-snmd-20260908.tar.gz -C /mnt/

# 4. 恢复权限
sudo chown -R minio-user:minio-user /mnt/drive{1..4}/minio

# 5. 恢复元数据（如需要）
mcli admin cluster bucket import local --input /backup/bucket-metadata-20260908.zip
mcli admin cluster iam import local --input /backup/iam-config-20260908.zip

# 6. 启动服务
sudo systemctl start minio
```

---

## 六、运维建议

### 6.1 监控

```bash
# Prometheus 指标端点
# https://localhost:9000/minio/v2/metrics/cluster
```

| 关键指标 | 说明 |
|---------|------|
| `minio_node_drive_online_count` | 在线磁盘数 |
| `minio_node_drive_offline_count` | 离线磁盘数 |
| `minio_bucket_usage_total_bytes` | 存储使用量 |

### 6.2 告警

| 告警项 | 阈值建议 | 说明 |
|--------|---------|------|
| 离线磁盘数 | > 0 | 立即处理，冗余降级 |
| 磁盘使用率 | > 80% | 及时扩容 |
| 自愈进度 | 未完成 > 1h | 排查磁盘性能 |

### 6.3 纠删码校验值调优

```bash
# 在 MINIO_OPTS 中设置（启动前配置）
# 示例：4 磁盘设置 EC:2（可容忍 2 块磁盘故障）
MINIO_OPTS="--console-address :9001 --certs-dir /opt/minio/certs"
# 通过环境变量设置默认校验值
MINIO_STORAGE_CLASS_STANDARD="EC:2"
```

### 6.4 备份策略总结

| 频率 | 操作 | 方式 |
|------|------|------|
| 每日 | 对象增量备份 | `mcli mirror --overwrite` |
| 每周 | 完整快照 | 停服 → 压缩全部磁盘 → 上传 |
| 每月 | 元数据快照 | `mcli admin cluster bucket export` + `iam export` |
| 实时 | 磁盘健康监控 | Prometheus + 告警 |

---

## 七、参考来源

- [SILO 下载与安装](https://silo.pgsty.com/zh/download/)
- [SILO 在 Ubuntu 上部署](https://silo.pgsty.com/operations/deployments/baremetal-deploy-minio-on-ubuntu-linux/)
- [SILO 在 Windows 上部署](https://silo.pgsty.com/zh/operations/deployments/baremetal-deploy-minio-on-windows/)
- [SILO 纠删码](https://silo.pgsty.com/zh/operations/concepts/erasure-coding/)
- [SILO mc mirror](https://silo.pgsty.com/zh/reference/minio-mc/mc-mirror/)
- [SILO 核心管理概念](https://silo.pgsty.com/zh/administration/concepts/)
- [SILO 术语表 — SNMD](https://silo.pgsty.com/zh/glossary/)
