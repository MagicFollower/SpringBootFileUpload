# SILO 多节点单盘（MNSD）生产部署与灾备指南

> 归档日期：2026-09-08
> 拓扑代号：MNSD（Multi-Node Single-Drive）
> 适用版本：SILO（MinIO 兼容对象存储）
> 参考来源：[SILO 官方文档](https://silo.pgsty.com/zh/)

---

## 一、拓扑概述与适用场景

### 1.1 拓扑描述

多节点单盘（MNSD）是一种特殊的分布式拓扑：多个节点参与集群，但每个节点仅提供一块磁盘（或一个目录）。
数据通过纠删码跨节点分布，容忍部分节点故障。

```
┌──────────────────────┐  ┌──────────────────────┐
│ minio1.example.net   │  │ minio2.example.net   │
│  /mnt/data/minio     │  │  /mnt/data/minio     │
│  (单块磁盘)          │  │  (单块磁盘)          │
└──────────┬───────────┘  └──────────┬───────────┘
           │                         │
┌──────────┴───────────┐  ┌─────────┴────────────┐
│ minio3.example.net   │  │ minio4.example.net   │
│  /mnt/data/minio     │  │  /mnt/data/minio     │
│  (单块磁盘)          │  │  (单块磁盘)          │
└──────────────────────┘  └──────────────────────┘
```

### 1.2 纠删码行为

MNSD 拓扑启用纠删码，数据分片跨节点分布：

| 节点数 | 默认校验值 | 可容忍故障节点数 | 可用存储比例 |
|--------|-----------|----------------|-------------|
| 4 | EC:2 | 2 | 50% |
| 8 | EC:4 | 4 | 50% |
| 16 | EC:4 | 4 | 75% |

### 1.3 适用场景

| 场景 | 推荐度 | 说明 |
|------|--------|------|
| 分布式验证 | 适用 | 测试跨节点纠删码 |
| 低成本高可用 | 适用 | 利用现有单盘机器组建集群 |
| 边缘/分支节点 | 适用 | 多站点各一台机器 |
| 大规模生产 | 不推荐 | 单盘节点扩展性差，建议 MNMD |

### 1.4 与 MNMD 的对比

| 特性 | MNSD（多节点单盘） | MNMD（多节点多盘） |
|------|-------------------|-------------------|
| 每节点磁盘 | 1 块 | 多块 |
| 纠删码粒度 | 节点级 | 磁盘级 |
| 扩展性 | 差（需整节点添加） | 好（可加 Pool） |
| 单节点故障影响 | 丢失 1 个分片 | 丢失 N 个分片 |
| 推荐度 | 特殊场景 | 生产首选 |

---

## 二、前置条件

### 2.1 硬件要求

| 项目 | 每节点要求 | 说明 |
|------|-----------|------|
| 节点数 | 4+ | 至少 4 个节点才能启用纠删码 |
| CPU | 4 核 | 8 核+ 推荐 |
| 内存 | 8 GB | 16 GB+ 推荐 |
| 磁盘 | 1 块/节点 | 独立物理磁盘，XFS |
| 网络 | 千兆 | 万兆推荐（节点间流量大） |

### 2.2 软件与网络要求

- **操作系统**：Linux（推荐 Ubuntu 20.04+ / RHEL 8+）
- **时钟同步**：所有节点 NTP 同步（误差 < 15 分钟）
- **网络互通**：所有节点间 9000/9001 端口互通
- **DNS/Hosts**：所有节点可解析彼此主机名
- **磁盘**：XFS 格式化，独立挂载点

### 2.3 磁盘准备（每节点执行）

```bash
# 格式化数据磁盘为 XFS
sudo mkfs.xfs /dev/sdb1

# 创建挂载点
sudo mkdir -p /mnt/data/minio

# 挂载
sudo mount /dev/sdb1 /mnt/data

# 写入 fstab
echo '/dev/sdb1 /mnt/data xfs defaults,noatime 0 2' | sudo tee -a /etc/fstab
```

---

## 三、Linux 环境部署步骤

### 3.1 安装（每个节点执行）

```bash
# DEB 包
sudo dpkg -i silo_20260903131801.0.0_amd64.deb

# 或 RPM 包
sudo rpm -ivh silo-20260903131801.0.0-1PGSTY.x86_64.rpm
```

### 3.2 配置（每个节点执行）

#### 创建专用用户

```bash
sudo groupadd -r minio-user
sudo useradd -M -r -g minio-user minio-user
```

#### 创建数据目录并授权

```bash
sudo mkdir -p /mnt/data/minio
sudo chown -R minio-user:minio-user /mnt/data/minio
```

#### TLS 证书配置

```bash
sudo mkdir -p /opt/minio/certs
sudo chown -R minio-user:minio-user /opt/minio/certs
sudo cp private.key /opt/minio/certs/
sudo cp public.crt /opt/minio/certs/
```

#### 配置环境变量文件

**所有节点必须使用完全相同的 `/etc/default/minio` 文件。**

```bash
sudo vi /etc/default/minio
```

写入以下内容：

```ini
# 多节点单盘：每个节点提供 1 个目录路径
# 使用扩展符号 {x...y} 列出所有节点
MINIO_VOLUMES="https://minio{1...4}.example.net:9000/mnt/data/minio"

# 命令行选项
MINIO_OPTS="--console-address :9001 --certs-dir /opt/minio/certs"

# 管理员凭证（所有节点必须一致）
MINIO_ROOT_USER=minioadmin
MINIO_ROOT_PASSWORD=your-strong-password-here
```

#### 验证配置一致性

```bash
# 在每个节点执行，确保哈希值一致
shasum -a 256 /etc/default/minio
```

#### /etc/hosts 配置

```bash
# 在每个节点的 /etc/hosts 中添加
192.168.1.101 minio1.example.net
192.168.1.102 minio2.example.net
192.168.1.103 minio3.example.net
192.168.1.104 minio4.example.net
```

### 3.3 启动与验证

```bash
# 在每个节点执行
sudo systemctl enable minio
sudo systemctl start minio
sudo systemctl status minio
```

#### 验证集群

```bash
mcli alias set mycluster https://minio1.example.net:9000 minioadmin your-password
mcli admin info mycluster
```

输出应显示 4 个节点、4 块磁盘，全部在线。

---

## 四、Windows 环境部署步骤

### 4.1 重要说明

> **SILO 官方未验证 Windows 上的多节点分布式配置。**
> Windows 部署仅推荐用于开发和评估环境。
> 生产环境强烈建议使用 Linux。

### 4.2 安装（每个节点执行）

1. 下载 Windows 归档并解压得到 `minio.exe`
2. 将 `minio.exe` 放置到固定目录（如 `C:\silo\`）

### 4.3 配置

#### 设置系统环境变量

```powershell
[System.Environment]::SetEnvironmentVariable("MINIO_ROOT_USER", "minioadmin", "Machine")
[System.Environment]::SetEnvironmentVariable("MINIO_ROOT_PASSWORD", "your-strong-password-here", "Machine")
```

#### 创建数据目录

```powershell
New-Item -ItemType Directory -Force -Path "D:\minio-data\minio"
```

#### TLS 证书配置

```powershell
New-Item -ItemType Directory -Force -Path "C:\silo\certs"
Copy-Item private.key "C:\silo\certs\"
Copy-Item public.crt "C:\silo\certs\"
```

#### 配置 hosts 文件

```powershell
# 以管理员身份运行
Add-Content -Path "C:\Windows\System32\drivers\etc\hosts" -Value "`n192.168.1.101 minio1.example.net`n192.168.1.102 minio2.example.net`n192.168.1.103 minio3.example.net`n192.168.1.104 minio4.example.net"
```

### 4.4 使用 NSSM 注册为 Windows 服务

```powershell
# 在节点 1 上注册（其他节点类似）
nssm install SiloServer "C:\silo\minio.exe" "server https://minio{1...4}.example.net:9000/D:\minio-data\minio --console-address :9001 --certs-dir C:\silo\certs"

nssm set SiloServer Description "SILO Object Storage Server (MNSD)"
nssm set SiloServer AppDirectory "C:\silo"

nssm start SiloServer
```

---

## 五、生产级备份与恢复

### 5.1 在线备份（mc mirror）

```bash
# 配置别名
mcli alias set mycluster https://minio1.example.net:9000 minioadmin your-password
mcli alias set backup https://backup-storage.example.com backupkey backupsecret

# 逐桶镜像同步
mcli mirror --overwrite --remove mycluster/my-bucket backup/my-bucket-backup
```

**定时任务：**

```bash
0 2 * * * /usr/local/bin/mcli mirror --overwrite mycluster/my-bucket backup/my-bucket-backup >> /var/log/minio-backup.log 2>&1
```

### 5.2 离线备份（停服压缩）

> **MNSD 拓扑通常不需要停服备份**，mc mirror 可满足大多数场景。
> 仅在需要完整文件系统快照时使用此方式。

```bash
# 滚动停止各节点（保持集群可用）
for node in minio1 minio2 minio3 minio4; do
    ssh ${node}.example.net "sudo systemctl stop minio"
    ssh ${node}.example.net "sudo tar -czf /backup/minio-mnsd-${node}-$(date +%Y%m%d).tar.gz -C /mnt/ data/"
    ssh ${node}.example.net "sudo systemctl start minio"
    sleep 30
done
```

### 5.3 元数据备份

```bash
# 导出桶元数据
mcli admin cluster bucket export mycluster --output /backup/bucket-metadata-$(date +%Y%m%d).zip

# 导出 IAM 配置
mcli admin cluster iam export mycluster --output /backup/iam-config-$(date +%Y%m%d).zip
```

### 5.4 站点复制灾备

MNSD 拓扑支持站点复制（Site Replication），实现跨数据中心的双向实时复制。

#### 配置站点复制

```bash
# 配置两个站点之间的复制
mcli admin replicate add siteA siteB

# 验证复制状态
mcli admin replicate status siteA
```

#### 站点自愈

```bash
# 如果某站点数据受损，从健康站点重新同步
mcli admin replicate resync siteA
```

### 5.5 故障场景与恢复流程

#### 场景 1：单节点故障

| 影响 | 恢复方式 |
|------|---------|
| 部分对象暂时不可用 | 纠删码自动从其他节点恢复 |
| 冗余降级 | 尽快恢复节点 |

**处理步骤：**

```bash
# 1. 检查集群状态
mcli admin info mycluster

# 2. 排查故障节点
ssh minio2.example.net
sudo systemctl status minio
sudo journalctl -u minio --since "1 hour ago"

# 3. 恢复节点
sudo systemctl restart minio

# 4. 如果节点无法恢复，部署备用节点
# 在新机器上安装 SILO，配置相同拓扑
# 使用相同的主机名或更新 MINIO_VOLUMES
```

#### 场景 2：多节点故障（超出纠删码容忍）

| 影响 | 恢复方式 |
|------|---------|
| 数据不可用 | 从备份恢复 |

#### 场景 3：全集群灾难恢复

```bash
# 1. 在新环境部署相同拓扑的集群
# 2. 从 mc mirror 目标恢复数据
mcli mirror --overwrite backup/my-bucket mycluster/my-bucket

# 3. 恢复元数据
mcli admin cluster bucket import mycluster --input /backup/bucket-metadata-20260908.zip
mcli admin cluster iam import mycluster --input /backup/iam-config-20260908.zip
```

#### 场景 4：单节点磁盘故障

由于每节点仅一块磁盘，磁盘故障等同于节点故障：

```bash
# 1. 更换磁盘
sudo umount /mnt/data
sudo mkfs.xfs /dev/sdb1
sudo mount /dev/sdb1 /mnt/data
sudo mkdir -p /mnt/data/minio
sudo chown -R minio-user:minio-user /mnt/data/minio

# 2. 重启服务
sudo systemctl restart minio

# 3. 观察自愈进度
mcli admin info mycluster
```

---

## 六、运维建议

### 6.1 监控

```bash
# Prometheus 指标端点
# https://minio1.example.net:9000/minio/v2/metrics/cluster
```

| 关键指标 | 说明 |
|---------|------|
| `minio_node_drive_online_count` | 每节点在线磁盘数 |
| `minio_cluster_nodes_offline_total` | 离线节点总数 |
| `minio_bucket_usage_total_bytes` | 存储使用量 |

### 6.2 告警

| 告警项 | 阈值建议 | 优先级 |
|--------|---------|--------|
| 离线节点数 | > 0 | P1 紧急 |
| 磁盘使用率 | > 80% | P3 中 |
| 自愈未完成 | > 24h | P2 高 |
| 复制延迟 | > 1h | P2 高 |

### 6.3 负载均衡

每个站点应部署负载均衡器：

```nginx
upstream minio_cluster {
    least_conn;
    server minio1.example.net:9000;
    server minio2.example.net:9000;
    server minio3.example.net:9000;
    server minio4.example.net:9000;
}

server {
    listen 443 ssl;
    server_name storage.example.net;

    ssl_certificate /path/to/public.crt;
    ssl_certificate_key /path/to/private.key;

    location / {
        proxy_pass https://minio_cluster;
        proxy_set_header Host $http_host;
        proxy_set_header X-Real-IP $remote_addr;
        client_max_body_size 0;
        chunked_transfer_encoding on;
    }
}
```

### 6.4 升级

```bash
# 滚动升级（逐个节点）
for node in minio1 minio2 minio3 minio4; do
    ssh ${node}.example.net "sudo systemctl stop minio"
    ssh ${node}.example.net "sudo dpkg -i silo-new-version.deb"
    ssh ${node}.example.net "sudo systemctl start minio"
    sleep 30
done
```

### 6.5 备份策略总结

| 频率 | 操作 | 方式 |
|------|------|------|
| 实时 | 站点复制 | Site Replication（跨数据中心） |
| 每日 | 对象增量备份 | `mcli mirror --overwrite` |
| 每周 | 元数据快照 | `mcli admin cluster bucket export` + `iam export` |
| 每月 | 完整审计 | 检查纠删码健康状态 + 备份验证 |

---

## 七、参考来源

- [SILO 下载与安装](https://silo.pgsty.com/zh/download/)
- [SILO 在 Ubuntu 上部署](https://silo.pgsty.com/operations/deployments/baremetal-deploy-minio-on-ubuntu-linux/)
- [SILO 在 Windows 上部署](https://silo.pgsty.com/zh/operations/deployments/baremetal-deploy-minio-on-windows/)
- [SILO 站点复制](https://silo.pgsty.com/zh/operations/replication/multi-site-replication/)
- [SILO 纠删码](https://silo.pgsty.com/zh/operations/concepts/erasure-coding/)
- [SILO mc mirror](https://silo.pgsty.com/zh/reference/minio-mc/mc-mirror/)
- [SILO 核心管理概念](https://silo.pgsty.com/zh/administration/concepts/)
