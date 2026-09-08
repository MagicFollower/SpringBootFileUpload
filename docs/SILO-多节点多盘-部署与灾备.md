# SILO 多节点多盘（MNMD）生产部署与灾备指南

> 归档日期：2026-09-08
> 拓扑代号：MNMD（Multi-Node Multi-Drive）
> 适用版本：SILO（MinIO 兼容对象存储）
> 参考来源：[SILO 官方文档](https://silo.pgsty.com/zh/)

---

## 一、拓扑概述与适用场景

### 1.1 拓扑描述

多节点多盘（MNMD）是 SILO/MinIO **推荐的生产拓扑**，提供企业级性能、可用性和可扩展性。
每个节点配备多块独立物理磁盘，节点间通过高速网络互联，纠删码跨节点分布数据分片。

```
┌─────────────────────┐  ┌─────────────────────┐
│ minio1.example.net  │  │ minio2.example.net  │
│  /mnt/disk1/minio   │  │  /mnt/disk1/minio   │
│  /mnt/disk2/minio   │  │  /mnt/disk2/minio   │
│  /mnt/disk3/minio   │  │  /mnt/disk3/minio   │
│  /mnt/disk4/minio   │  │  /mnt/disk4/minio   │
└──────────┬──────────┘  └──────────┬──────────┘
           │                        │
┌──────────┴──────────┐  ┌─────────┴───────────┐
│ minio3.example.net  │  │ minio4.example.net  │
│  /mnt/disk1/minio   │  │  /mnt/disk1/minio   │
│  /mnt/disk2/minio   │  │  /mnt/disk2/minio   │
│  /mnt/disk3/minio   │  │  /mnt/disk3/minio   │
│  /mnt/disk4/minio   │  │  /mnt/disk4/minio   │
└─────────────────────┘  └─────────────────────┘
```

### 1.2 核心优势

| 特性 | 说明 |
|------|------|
| 高可用 | 容忍最多 N/2 个节点故障（N = 节点数） |
| 数据冗余 | 纠删码跨节点分布，单磁盘/节点故障不丢数据 |
| 可扩展 | 支持 Server Pool 在线扩容 |
| 高性能 | 读写操作并行分布在所有节点 |
| 自愈 | 自动检测并修复损坏分片 |

### 1.3 适用场景

| 场景 | 推荐度 | 说明 |
|------|--------|------|
| 生产环境 | **强烈推荐** | 官方推荐的企业级拓扑 |
| 高可用要求 | 推荐 | 满足 SLA 要求 |
| 大规模存储 | 推荐 | 支持 PB 级数据 |
| 开发/测试 | 过度 | 资源消耗大，建议使用 SNMD/SNSD |

---

## 二、前置条件

### 2.1 硬件要求

| 项目 | 每节点最低要求 | 生产建议 |
|------|--------------|---------|
| 节点数 | 4 | 4+ 偶数个节点 |
| CPU | 8 核 | 16 核+ |
| 内存 | 16 GB | 32 GB+ |
| 磁盘/节点 | 4 块 | 4+ 块独立物理磁盘 |
| 网络 | 千兆 | 万兆（节点间互联） |

### 2.2 软件与网络要求

- **操作系统**：Linux（推荐 Ubuntu 20.04+ / RHEL 8+），XFS 文件系统
- **时钟同步**：所有节点必须 NTP 同步（误差 < 15 分钟）
- **网络互通**：所有节点间 9000/9001 端口互通
- **DNS/Hosts**：所有节点可解析彼此主机名（或配置 /etc/hosts）
- **磁盘**：每块磁盘独立物理磁盘，XFS 格式化，独立挂载点

### 2.3 磁盘规划（每节点执行）

```bash
# 格式化所有数据磁盘为 XFS
for disk in b c d e; do
    sudo mkfs.xfs /dev/sd${disk}1
done

# 创建挂载点
for i in {1..4}; do
    sudo mkdir -p /mnt/disk${i}/minio
done

# 挂载
sudo mount /dev/sdb1 /mnt/disk1
sudo mount /dev/sdc1 /mnt/disk2
sudo mount /dev/sdd1 /mnt/disk3
sudo mount /dev/sde1 /mnt/disk4

# 写入 fstab
for i in {1..4}; do
    disk_letter=$(echo "bcde" | cut -c${i})
    echo "/dev/sd${disk_letter}1 /mnt/disk${i} xfs defaults,noatime 0 2" | sudo tee -a /etc/fstab
done
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
for i in {1..4}; do
    sudo mkdir -p /mnt/disk${i}/minio
    sudo chown -R minio-user:minio-user /mnt/disk${i}/minio
done
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
# 多节点多盘：使用扩展符号指定所有节点和磁盘
# 格式：https://host{1...N}:port/path/to/disks{1...M}/minio
MINIO_VOLUMES="https://minio{1...4}.example.net:9000/mnt/disk{1...4}/minio"

# 命令行选项
MINIO_OPTS="--console-address :9001 --certs-dir /opt/minio/certs"

# 管理员凭证（所有节点必须一致）
MINIO_ROOT_USER=minioadmin
MINIO_ROOT_PASSWORD=your-strong-password-here
```

#### 验证配置一致性

```bash
# 在每个节点执行，确保输出一致
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

输出应显示 4 个节点、16 块磁盘，全部在线。

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
# 每个节点使用多个目录模拟多磁盘
for ($i=1; $i -le 4; $i++) {
    New-Item -ItemType Directory -Force -Path "D:\disk${i}\minio"
}
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
# 在节点 1 上注册（其他节点类似，修改主机名）
nssm install SiloServer "C:\silo\minio.exe" "server https://minio{1...4}.example.net:9000/D:\disk{1...4}\minio --console-address :9001 --certs-dir C:\silo\certs"

nssm set SiloServer Description "SILO Object Storage Server (MNMD Node)"
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
mcli mirror --overwrite --remove mycluster/bucket1 backup/bucket1-backup
mcli mirror --overwrite --remove mycluster/bucket2 backup/bucket2-backup
```

**定时任务（在任一节点或独立管理机执行）：**

```bash
0 2 * * * /usr/local/bin/mcli mirror --overwrite mycluster/my-bucket backup/my-bucket-backup >> /var/log/minio-backup.log 2>&1
```

### 5.2 离线备份（停服压缩）

> **MNMD 拓扑通常不需要停服备份**，mc mirror 可满足大多数场景。
> 仅在需要完整文件系统快照时使用此方式。

```bash
# 滚动停止各节点（保持集群可用）
# 在节点 1 执行
sudo systemctl stop minio
sudo tar -czf /backup/minio-mnmd-node1-$(date +%Y%m%d).tar.gz -C /mnt/ disk{1..4}/
sudo systemctl start minio

# 依次对其他节点重复
```

### 5.3 元数据备份

```bash
# 导出桶元数据
mcli admin cluster bucket export mycluster --output /backup/bucket-metadata-$(date +%Y%m%d).zip

# 导出 IAM 配置
mcli admin cluster iam export mycluster --output /backup/iam-config-$(date +%Y%m%d).zip
```

### 5.4 站点复制灾备（推荐）

MNMD 拓扑支持完整的站点复制（Site Replication），实现跨数据中心的双向实时复制。

#### 配置站点复制

```bash
# 在站点 A 配置
mcli admin replicate add siteA siteB

# 验证复制状态
mcli admin replicate status siteA
```

#### 站点复制同步的内容

- 存储桶和对象的创建、修改与删除
- 存储桶与对象配置、策略、标签
- 锁定与保留配置
- IAM 用户、组、策略及映射

#### 站点自愈

```bash
# 如果某站点数据受损，从健康站点重新同步
mcli admin replicate resync siteA
```

### 5.5 故障场景与恢复流程

#### 场景 1：单磁盘故障

| 影响 | 恢复方式 |
|------|---------|
| 无数据丢失 | 纠删码自动自愈 |
| 冗余降级 | 尽快更换磁盘 |

**处理步骤：**

```bash
# 1. 检查状态
mcli admin info mycluster

# 2. 更换故障磁盘
sudo umount /mnt/disk2
sudo mkfs.xfs /dev/sdc1
sudo mount /dev/sdc1 /mnt/disk2
sudo mkdir -p /mnt/disk2/minio
sudo chown -R minio-user:minio-user /mnt/disk2/minio

# 3. 重启该节点服务
sudo systemctl restart minio

# 4. 观察自愈进度
mcli admin info mycluster
```

#### 场景 2：单节点故障

| 影响 | 恢复方式 |
|------|---------|
| 部分对象暂时不可用 | 纠删码自动从其他节点恢复 |
| 冗余降级 | 尽快恢复节点 |

**处理步骤：**

```bash
# 1. 检查集群状态
mcli admin info mycluster

# 2. 排查节点故障原因
ssh minio2.example.net
sudo systemctl status minio
sudo journalctl -u minio --since "1 hour ago"

# 3. 恢复节点
sudo systemctl restart minio

# 4. 如果节点无法恢复，从备份恢复数据到备用机器
# 按新节点部署流程操作
```

#### 场景 3：多节点故障（超出纠删码容忍）

| 影响 | 恢复方式 |
|------|---------|
| 数据不可用 | 从备份恢复 |

#### 场景 4：全集群灾难恢复

```bash
# 1. 在新环境部署相同拓扑的集群
# 2. 从 mc mirror 目标恢复数据
mcli mirror --overwrite backup/my-bucket mycluster/my-bucket

# 3. 恢复元数据
mcli admin cluster bucket import mycluster --input /backup/bucket-metadata-20260908.zip
mcli admin cluster iam import mycluster --input /backup/iam-config-20260908.zip
```

### 5.6 Server Pool 在线扩容

MNMD 支持在线添加 Server Pool 扩展存储容量：

```bash
# 1. 准备新节点（4 个节点，每节点 4 块磁盘）
# 2. 在所有现有节点的环境变量文件中追加新 Pool
MINIO_VOLUMES="https://minio{1...4}.example.net:9000/mnt/disk{1...4}/minio https://minio{5...8}.example.net:9000/mnt/disk{1...4}/minio"

# 3. 同步配置到所有节点（新旧）
# 4. 滚动重启所有节点
# 5. 验证扩容
mcli admin info mycluster
```

---

## 六、运维建议

### 6.1 监控

```bash
# Prometheus 指标端点
# https://minio1.example.net:9000/minio/v2/metrics/cluster

# Grafana Dashboard 参考
# https://github.com/minio/minio/blob/master/docs/metrics/prometheus/
```

| 关键指标 | 说明 |
|---------|------|
| `minio_node_drive_online_count` | 每节点在线磁盘数 |
| `minio_node_drive_offline_count` | 每节点离线磁盘数 |
| `minio_cluster_nodes_offline_total` | 离线节点总数 |
| `minio_bucket_usage_total_bytes` | 存储使用量 |
| `minio_s3_requests_total` | API 请求总数 |

### 6.2 告警

| 告警项 | 阈值建议 | 优先级 |
|--------|---------|--------|
| 离线节点数 | > 0 | P1 紧急 |
| 离线磁盘数 | > 0 | P2 高 |
| 磁盘使用率 | > 80% | P3 中 |
| 自愈未完成 | > 24h | P2 高 |
| 复制延迟 | > 1h | P2 高 |

### 6.3 负载均衡

每个站点应部署负载均衡器（Nginx/HAProxy），将请求分发到各节点：

```nginx
# Nginx 配置示例
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
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;

        # 大文件上传支持
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
    # 等待节点恢复在线
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
- [SILO 扩展分布式部署](https://silo.pgsty.com/zh/operations/deployments/baremetal-expand-minio-deployment/)
- [SILO mc mirror](https://silo.pgsty.com/zh/reference/minio-mc/mc-mirror/)
- [SILO 核心管理概念](https://silo.pgsty.com/zh/administration/concepts/)
- [SILO 硬件检查清单](https://silo.pgsty.com/operations/checklists/hardware/)
- [SILO 软件检查清单](https://silo.pgsty.com/operations/checklists/software/)
