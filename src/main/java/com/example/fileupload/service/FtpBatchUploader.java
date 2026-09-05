package com.example.fileupload.service;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * FTP 批量上传工具类
 * <p>
 * 核心设计：使用 ThreadLocal 保证同线程的多文件操作复用同一个 FTPClient 连接，
 * 减少 TCP/TLS 握手开销。单文件操作无需此工具，直接调用策略即可。
 */
public class FtpBatchUploader implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(FtpBatchUploader.class);

    /** ThreadLocal 绑定当前线程的 FTPClient 实例，保证复用 */
    private final ThreadLocal<FTPClient> ftpHolder = ThreadLocal.withInitial(FTPClient::new);
    private boolean connected;

    // ==================== 连接管理 ====================

    /**
     * 获取（或创建）FTPClient 实例
     */
    private FTPClient getFtpClient() {
        FTPClient client = ftpHolder.get();
        if (!connected || !client.isConnected()) {
            connect(client);
        }
        return client;
    }

    /**
     * 连接到 FTP 服务器
     */
    public void connect(FTPClient client) {
        try {
            if (connected && client.isConnected()) {
                return; // 已连接则跳过
            }
            client.connect(host, port);
            int replyCode = client.getReplyCode();
            if (!FTPReply.isPositiveCompletion(replyCode)) {
                client.disconnect();
                throw new IOException("FTP 连接失败，服务器返回: " + replyCode + " " + client.getReplyString());
            }
            boolean logged = client.login(username, password);
            if (!logged) {
                client.disconnect();
                throw new IOException("FTP 登录失败，请检查用户名和密码是否正确");
            }

            // ========== 关键配置：解决乱码和连接问题 ==========
            // 1. 控制命令使用 UTF-8 编码（文件名、目录名含中文时必设）
            client.setControlEncoding("UTF-8");
            // 2. 使用被动模式（穿透 NAT/防火墙）
            client.enterLocalPassiveMode();
            // 3. 文件类型设为二进制（避免 txt/bin 混用导致图片损坏或文本换行错乱）
            client.setFileType(FTP.BINARY_FILE_TYPE);
            // 4. 禁用 UTF8 文件名特性协商（兼容性更好，由 setControlEncoding 处理）
            client.setRemoteVerificationEnabled(false);
            // 5. 设置缓冲区大小（提升大文件传输性能）
            client.setBufferSize(8192);

            connected = true;
            log.info("[FTP] connected to {}:{}, user={}", host, port, username);
        } catch (IOException e) {
            throw new RuntimeException("FTP 连接失败: " + host + ":" + port, e);
        }
    }

    // ==================== 上传接口 ====================

    /**
     * 单个文件上传（内部复用 ThreadLocal 连接）
     *
     * @param remotePath       远程目录路径（如 /uploads/data/）
     * @param originalFilename 原始文件名
     * @return 保存的文件唯一标识（UUID_filename）
     */
    public String uploadSingle(InputStream inputStream, String remotePath, String originalFilename) throws IOException {
        FTPClient client = getFtpClient();
        return uploadInternal(client, remotePath, originalFilename, inputStream);
    }

    /**
     * 批量文件上传（完全复用同一个连接和同一个 control encoding）
     *
     * @param fileEntries      待上传文件列表
     * @return 批量结果（包含成功/失败统计）
     */
    public BatchResult uploadBatch(List<FileEntry> fileEntries) throws IOException {
        if (fileEntries == null || fileEntries.isEmpty()) {
            return new BatchResult(new ArrayList<>());
        }

        FTPClient client = getFtpClient();
        List<FtpUploadEntry> entries = new ArrayList<>(fileEntries.size());

        for (FileEntry entry : fileEntries) {
            try {
                String saveName = uploadInternal(client, entry.remoteDir, entry.originalFilename, entry.inputStream);
                entries.add(new FtpUploadEntry(entry.originalFilename, saveName, true, null));
            } catch (Exception ex) {
                log.warn("[FTP] batch upload failed: {}", entry.originalFilename, ex);
                entries.add(new FtpUploadEntry(entry.originalFilename, null, false, ex.getMessage()));
            } finally {
                safeClose(entry.inputStream);
            }
        }

        return new BatchResult(entries);
    }

    /**
     * 上传目录下的全部文件（递归列出本地目录中的所有文件并上传）
     *
     * @param remotePath  远程存储路径前缀
     * @param localDirs   需要扫描上传的本地目录
     * @return 上传结果汇总
     */
    public BatchResult uploadDirectories(String remotePath, List<String> localDirs) throws IOException {
        FTPClient client = getFtpClient();
        List<FtpUploadEntry> results = new ArrayList<>();

        for (String dir : localDirs) {
            java.io.File sourceDir = new java.io.File(dir);
            if (!sourceDir.isDirectory()) {
                log.warn("[FTP] skip non-directory: {}", dir);
                continue;
            }

            // 递归遍历目录下所有文件
            FileWalker.walk(sourceDir, file -> {
                try {
                    String relPath = getRelativePath(sourceDir, file);
                    String targetDir = remotePath + "/" + (relPath.isEmpty() ? "" : relPath.substring(0, relPath.lastIndexOf('/') + 1));
                    createRemoteDirs(client, targetDir);
                    String saveName = UUID.randomUUID().toString().replace("-", "") + "_" + file.getName();
                    try (InputStream is = new java.io.FileInputStream(file)) {
                        boolean ok = client.storeFile(targetDir + saveName, is);
                        results.add(new FtpUploadEntry(file.getName(), saveName, ok, null));
                    }
                } catch (IOException e) {
                    log.warn("[FTP] failed to upload: {}", file.getName(), e);
                    results.add(new FtpUploadEntry(file.getName(), null, false, e.getMessage()));
                }
            });
        }

        return new BatchResult(results);
    }

    // ==================== 私有辅助方法 ====================

    private String uploadInternal(FTPClient client, String remotePath, String originalFilename, InputStream inputStream) throws IOException {
        String saveName = UUID.randomUUID().toString().replace("-", "") + "_" + originalFilename;

        // 确保路径以 / 开头、不重复尾斜杠，拼接成 /uploads/UUID_filename
        String cleanBase = remotePath.replace('\\', '/');
        if (cleanBase.endsWith("/")) {
            cleanBase = cleanBase.substring(0, cleanBase.length() - 1);
        }
        if (!cleanBase.startsWith("/")) {
            cleanBase = "/" + cleanBase;
        }
        String fullRemotePath = cleanBase + "/" + saveName;

        // 确保远程目录存在（权限不足会抛出异常）
        createRemoteDirs(client, cleanBase);

        boolean success = client.storeFile(fullRemotePath, inputStream);
        if (!success) {
            int replyCode = client.getReplyCode();
            String reply = client.getReplyString();
            throw new IOException(classifyFtpError(replyCode, reply, "上传"));
        }
        return saveName;
    }

    /**
     * 根据 FTP 回复码分类错误类型
     */
    private String classifyFtpError(int replyCode, String reply, String operation) {
        switch (replyCode) {
            case 550:
                return "FTP 权限不足或文件不存在，" + operation + "失败: " + reply;
            case 553:
                return "FTP 路径无效或文件名不允许，" + operation + "失败: " + reply;
            case 421:
                return "FTP 服务不可用或连接超时，" + operation + "失败: " + reply;
            case 425:
                return "FTP 数据连接建立失败，请检查防火墙/被动模式配置: " + reply;
            case 426:
                return "FTP 数据传输中断，" + operation + "失败: " + reply;
            case 552:
                return "FTP 磁盘空间不足，" + operation + "失败: " + reply;
            default:
                return "FTP " + operation + "失败 (" + replyCode + "): " + reply;
        }
    }

    /**
     * 在 FTP 服务器上创建完整路径（逐层创建目录）
     */
    private void createRemoteDirs(FTPClient client, String path) throws IOException {
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (path.isEmpty() || "/".equals(path)) {
            return; // 根目录已存在
        }

        String[] parts = path.split("/");
        StringBuilder builder = new StringBuilder();
        builder.append("/");
        for (int i = 1; i < parts.length; i++) { // 跳过第一个空串（leading /）
            builder.append(parts[i]).append("/");
            try {
                if (!client.changeWorkingDirectory(builder.toString())) {
                    boolean created = client.makeDirectory(builder.toString());
                    if (!created) {
                        int replyCode = client.getReplyCode();
                        String reply = client.getReplyString();
                        if (replyCode == 550) {
                            throw new IOException("FTP 权限不足，无法创建目录 " + builder + ": " + reply);
                        } else if (replyCode == 553) {
                            throw new IOException("FTP 路径无效或文件名不允许: " + reply);
                        }
                        log.warn("[FTP] could not create dir: {}, reply={}", builder, reply);
                    } else {
                        client.changeWorkingDirectory(builder.toString());
                    }
                }
            } catch (IOException e) {
                // 如果已经是我们的自定义异常，直接抛出
                if (e.getMessage() != null && (e.getMessage().contains("权限不足") || e.getMessage().contains("路径无效"))) {
                    throw e;
                }
                log.warn("[FTP] error creating dir: {}", builder, e);
            }
        }
        client.changeWorkingDirectory("/"); // 回到根目录
    }

    private static void safeClose(InputStream is) {
        try {
            if (is != null) is.close();
        } catch (IOException ignored) {
        }
    }

    // ==================== Getter（用于测试） ====================

    boolean isConnected() {
        return connected;
    }

    // ==================== 内部数据类 ====================

    /** 单文件输入条目 */
    public static class FileEntry {
        public final String remoteDir;
        public final String originalFilename;
        public final InputStream inputStream;
        public FileEntry(String remoteDir, String originalFilename, InputStream inputStream) {
            this.remoteDir = remoteDir;
            this.originalFilename = originalFilename;
            this.inputStream = inputStream;
        }
    }

    /** 上传结果条目 */
    public static class FtpUploadEntry {
        public final String originalFilename;
        public final String savedName;
        public final boolean success;
        public final String errorMessage;

        public FtpUploadEntry(String originalFilename, String savedName, boolean success, String errorMessage) {
            this.originalFilename = originalFilename;
            this.savedName = savedName;
            this.success = success;
            this.errorMessage = errorMessage;
        }
    }

    /** 批量上传结果汇总 */
    public static class BatchResult {
        private final List<FtpUploadEntry> entries;

        public BatchResult(List<FtpUploadEntry> entries) {
            this.entries = entries;
        }

        public int getTotal() { return entries.size(); }
        public int getSuccessCount() { return (int) entries.stream().filter(e -> e.success).count(); }
        public int getFailCount() { return (int) entries.stream().filter(e -> !e.success).count(); }

        public List<FtpUploadEntry> getEntries() { return entries; }
        public boolean allSuccess() { return getFailCount() == 0; }
    }

    /** 简单递归文件遍历器 */
    private static class FileWalker {
        static void walk(java.io.File dir, FileCallback callback) {
            java.io.File[] children = dir.listFiles();
            if (children == null) return;
            for (java.io.File child : children) {
                if (child.isFile()) {
                    callback.accept(child);
                } else if (child.isDirectory()) {
                    walk(child, callback);
                }
            }
        }

        interface FileCallback {
            void accept(java.io.File file);
        }
    }

    private static String getRelativePath(java.io.File baseDir, java.io.File file) {
        String base = baseDir.getAbsolutePath().replace('\\', '/');
        String fullPath = file.getAbsolutePath().replace('\\', '/');
        if (fullPath.startsWith(base + "/")) {
            return fullPath.substring(base.length() + 1);
        }
        return "";
    }

    // ==================== 生命周期 ====================

    /** 关闭所有 ThreadLocal 绑定的连接 */
    @Override
    public void close() {
        FTPClient client = ftpHolder.get();
        if (client != null && connected && client.isConnected()) {
            try { client.logout(); } catch (IOException ignored) {}
            try { client.disconnect(); } catch (IOException ignored) {}
            connected = false;
            log.info("[FTP] disconnected");
        }
        ftpHolder.remove();
    }

    // ==================== 构造（保留字段引用以便连接时读取） ====================

    private final String host;
    private final int port;
    private final String username;
    private final String password;

    public FtpBatchUploader(String host, int port, String username, String password) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
    }
}
