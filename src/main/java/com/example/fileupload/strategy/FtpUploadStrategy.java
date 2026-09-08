package com.example.fileupload.strategy;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPReply;
import com.example.fileupload.model.UploadResult;
import com.example.fileupload.service.FtpBatchUploader;
import com.example.fileupload.service.FtpBatchUploader.FileEntry;
import com.example.fileupload.service.FtpBatchUploader.BatchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * FTP 完整实现策略
 * <p>
 * 使用 Apache Commons Net 的真实 FTPClient，解决以下常见问题：
 * <ul>
 *   <li><b>乱码</b>：通过 setControlEncoding("UTF-8") 确保文件名/目录名用 UTF-8 编码传输；
 *       同时使用 BINARY_FILE_TYPE 避免文本模式导致的字节转换。</li>
 *   <li><b>被动模式</b>：enterLocalPassiveMode() 穿透 NAT/防火墙。</li>
 *   <li><b>连接复用</b>：提供 batchUpload() 方法，同一线程内的多个文件共享同一个 FTPClient
 *       连接，减少 TCP/TLS 握手开销。</li>
 * </ul>
 */
@Component
public class FtpUploadStrategy implements UploadStrategy {

    private static final Logger log = LoggerFactory.getLogger(FtpUploadStrategy.class);

    @Value("${file.ftp.host:192.168.1.100}")
    private String host;

    @Value("${file.ftp.port:21}")
    private int port;

    @Value("${file.ftp.username:user0}")
    private String username;

    @Value("${file.ftp.password:123456789}")
    private String password;

    @Value("${file.ftp.basePath:/uploads/}")
    private String ftpBasePath;

    // ==================== 单文件上传/删除/下载（真实 FTP） ====================

    @Override
    public UploadResult upload(MultipartFile file, String originalFilename) throws IOException {
        return upload(file.getBytes(), originalFilename);
    }

    @Override
    public UploadResult upload(byte[] bytes, String originalFilename) throws IOException {
        // 单文件走真实 FTP 连接
        FTPClient client = null;
        InputStream inputStream = new java.io.ByteArrayInputStream(bytes);
        try {
            client = createAndConnectClient();
            return doUpload(client, inputStream, originalFilename, bytes.length);
        } finally {
            safeDisconnect(client);
        }
    }

    @Override
    public boolean delete(String fileKey) throws IOException {
        if (fileKey == null || fileKey.isEmpty()) {
            return false;
        }
        FTPClient client = null;
        try {
            client = createAndConnectClient();
            String fullPath = resolveRemotePath(fileKey);
            boolean deleted = client.deleteFile(fullPath);
            if (!deleted) {
                log.warn("[FTP] delete failed for {}, reply: {}", fullPath, client.getReplyString());
            }
            return deleted;
        } finally {
            safeDisconnect(client);
        }
    }

    @Override
    public byte[] download(String fileKey) throws IOException {
        if (fileKey == null || fileKey.isEmpty()) {
            return null;
        }
        FTPClient client = null;
        try {
            client = createAndConnectClient();
            String fullPath = resolveRemotePath(fileKey);
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            boolean success = client.retrieveFile(fullPath, baos);
            if (!success) {
                log.warn("[FTP] download failed for {}, reply: {}", fullPath, client.getReplyString());
                return null;
            }
            return baos.toByteArray();
        } finally {
            safeDisconnect(client);
        }
    }

    // ==================== 批量上传（复用连接） ====================

    /**
     * 批量上传多个文件，所有文件复用同一个 FTPClient 连接。
     * <p>
     * 内部创建 {@link FtpBatchUploader}，由 try-with-resources 保证最后断开。
     *
     * @param files          MultipartFile 列表（调用方已从 multipartRequest 取出）
     * @return 各文件的 UploadResult 列表
     */
    @Override
    public List<UploadResult> batchUpload(List<MultipartFile> files) throws IOException {
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("批量上传列表不能为空");
        }

        // 预计算 MD5（在流被消耗之前）
        java.util.Map<String, String> md5Map = new java.util.HashMap<>();
        for (MultipartFile file : files) {
            if (file != null && !file.isEmpty()) {
                try {
                    byte[] bytes = file.getBytes();
                    String md5 = DigestUtils.md5Hex(bytes);
                    md5Map.put(file.getOriginalFilename(), md5);
                } catch (IOException e) {
                    log.warn("[FTP-BATCH] failed to compute MD5 for: {}", file.getOriginalFilename());
                }
            }
        }

        try (FtpBatchUploader uploader = new FtpBatchUploader(host, port, username, password)) {
            // 构建 FileEntry 列表
            List<FileEntry> entries = new ArrayList<>(files.size());
            for (MultipartFile file : files) {
                if (file != null && !file.isEmpty()) {
                    entries.add(new FileEntry(ftpBasePath, file.getOriginalFilename(), file.getInputStream()));
                }
            }

            if (entries.isEmpty()) {
                throw new IllegalArgumentException("没有有效文件可上传");
            }

            // 一次性发送所有文件（同一个连接）
            BatchResult result = uploader.uploadBatch(entries);

            if (!result.allSuccess()) {
                log.warn("[FTP-BATCH] uploaded={}/{}, failures:", result.getSuccessCount(), result.getTotal());
                for (FtpBatchUploader.FtpUploadEntry e : result.getEntries()) {
                    if (!e.success) {
                        log.warn("[FTP-BATCH]   {} => {}", e.originalFilename, e.errorMessage);
                    }
                }
            }

            // 组装返回结果
            List<UploadResult> results = new ArrayList<>();
            for (int i = 0; i < files.size() && i < result.getEntries().size(); i++) {
                FtpBatchUploader.FtpUploadEntry entry = result.getEntries().get(i);
                MultipartFile mf = files.get(i);
                if (entry.success) {
                    UploadResult r = buildUploadResult(entry.savedName, mf, md5Map.get(mf.getOriginalFilename()));
                    results.add(r);
                } else {
                    // 失败时抛出异常，让调用方知道具体哪个文件失败
                    throw new IOException("FTP 批量上传中文件 '" + mf.getOriginalFilename() + "' 失败: " + entry.errorMessage);
                }
            }
            return results;
        }
    }

    // ==================== 工具方法 ====================

    @Override
    public com.example.fileupload.enums.UploadType getUploadType() {
        return com.example.fileupload.enums.UploadType.FTP;
    }

    @PreDestroy
    public void cleanup() {
        log.info("[FTP] bean destroyed, no background connections to clean");
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 创建并连接一个新的 FTPClient 实例。
     * 包含全部乱码/兼容性防护配置。
     */
    private FTPClient createAndConnectClient() throws IOException {
        FTPClient client = new FTPClient();
        try {
            // 1. TCP 连接
            client.connect(host, port);
            int replyCode = client.getReplyCode();
            if (!FTPReply.isPositiveCompletion(replyCode)) {
                client.disconnect();
                throw new IOException("FTP 连接失败，服务器返回: " + replyCode + " " + client.getReplyString());
            }

            // 2. 认证
            boolean logged = client.login(username, password);
            if (!logged) {
                client.disconnect();
                throw new IOException("FTP 登录失败，请检查用户名和密码是否正确");
            }

            // 3. 关键配置：解决乱码和连接问题
            client.setControlEncoding("UTF-8");
            client.enterLocalPassiveMode();
            client.setFileType(FTP.BINARY_FILE_TYPE);
            client.setBufferSize(8192);
            client.setRemoteVerificationEnabled(false);

            return client;
        } catch (IOException e) {
            safeDisconnect(client);
            throw e;
        }
    }

    private UploadResult doUpload(FTPClient client, java.io.InputStream inputStream,
                                  String originalFilename, long fileSize) throws IOException {
        String saveName = UUID.randomUUID().toString().replace("-", "") + "_" + originalFilename;
        String fullPath = resolveRemotePath(saveName);

        // 确保远程目录存在（权限不足会抛出异常）
        ensureRemoteDir(client, ftpBasePath.replace('\\', '/'));

        boolean success = client.storeFile(fullPath, inputStream);
        if (!success) {
            String reply = client.getReplyString();
            int replyCode = client.getReplyCode();
            String errorMsg = classifyFtpError(replyCode, reply, "上传");
            throw new IOException(errorMsg);
        }

        UploadResult result = new UploadResult();
        result.setStorageType(com.example.fileupload.enums.UploadType.FTP.getCode());
        result.setFileKey(saveName);
        result.setUrl("ftp://" + host + ":" + port + "/" + fullPath);
        result.setSize(fileSize);
        result.setFullPath(null);
        result.setRelativePath(null);
        log.info("[FTP] uploaded={}, path={}", originalFilename, fullPath);
        return result;
    }

    private UploadResult buildUploadResult(String saveName, MultipartFile file, String precomputedMd5) throws IOException {
        String fullPath = resolveRemotePath(saveName);
        UploadResult result = new UploadResult();
        result.setStorageType(com.example.fileupload.enums.UploadType.FTP.getCode());
        result.setFileKey(saveName);
        result.setUrl("ftp://" + host + ":" + port + "/" + fullPath);
        result.setSize(file.getSize());
        result.setMd5(precomputedMd5);
        result.setFullPath(null);
        result.setRelativePath(null);
        return result;
    }

    /** 解析 remote path：去掉尾部多余斜杠，加上文件名 */
    private String resolveRemotePath(String fileName) {
        String base = ftpBasePath.replace('\\', '/');
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/" + fileName;
    }

    /** 在 FTP 服务器上逐级创建目录（mkdir -p 语义） */
    private void ensureRemoteDir(FTPClient client, String dirPath) throws IOException {
        if (dirPath.endsWith("/")) {
            dirPath = dirPath.substring(0, dirPath.length() - 1);
        }
        if (dirPath.isEmpty() || "/".equals(dirPath)) return;

        String[] parts = dirPath.split("/");
        StringBuilder cur = new StringBuilder("/");
        for (int i = 1; i < parts.length; i++) {
            cur.append(parts[i]).append("/");
            try {
                if (!client.changeWorkingDirectory(cur.toString())) {
                    boolean created = client.makeDirectory(cur.toString());
                    if (!created) {
                        int replyCode = client.getReplyCode();
                        String reply = client.getReplyString();
                        // 550 = Permission denied / directory exists but can't access
                        // 553 = Requested action not taken: file name not allowed
                        if (replyCode == 550) {
                            throw new IOException("FTP 权限不足，无法创建目录 " + cur + ": " + reply);
                        } else if (replyCode == 553) {
                            throw new IOException("FTP 路径无效或文件名不允许: " + reply);
                        }
                        log.warn("[FTP] cannot create dir: {}, reply={}", cur, reply);
                    } else {
                        client.changeWorkingDirectory(cur.toString());
                    }
                }
            } catch (IOException e) {
                // 如果已经是我们的自定义异常，直接抛出
                if (e.getMessage() != null && (e.getMessage().contains("权限不足") || e.getMessage().contains("路径无效"))) {
                    throw e;
                }
                log.warn("[FTP] error creating dir: {}", cur, e);
            }
        }
        client.changeWorkingDirectory("/");
    }

    /**
     * 根据 FTP 回复码分类错误类型
     *
     * @param replyCode  FTP 回复码
     * @param reply      完整回复字符串
     * @param operation  操作名称（如"上传"、"删除"）
     * @return 用户友好的错误消息
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

    private void safeDisconnect(FTPClient client) {
        if (client != null && client.isConnected()) {
            try { client.logout(); } catch (IOException ignored) {}
            try { client.disconnect(); } catch (IOException ignored) {}
        }
    }
}
