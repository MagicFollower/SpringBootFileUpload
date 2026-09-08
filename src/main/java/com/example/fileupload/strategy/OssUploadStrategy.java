package com.example.fileupload.strategy;

import com.example.fileupload.enums.UploadType;
import com.example.fileupload.model.UploadResult;
import io.minio.*;
import org.apache.commons.codec.digest.DigestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

/**
 * MinIO 对象存储上传策略
 * <p>
 * 使用 MinIO Java SDK 的 MinioClient 执行 putObject / getObject / removeObject。
 * 客户端生命周期由 Spring 管理（@PostConstruct 创建，@PreDestroy 关闭）。
 * <p>
 * URL 格式：urlPrefix + "/" + objectKey（不含 bucketName，呈现为正常路径格式）。
 * 若需外部可直接访问该 URL，请将 urlPrefix 配置为反向代理地址（如 Nginx 代理到 MinIO bucket）。
 */
@Component
public class OssUploadStrategy implements UploadStrategy {

    private static final Logger log = LoggerFactory.getLogger(OssUploadStrategy.class);

    @Value("${file.oss.endpoint:http://127.0.0.1:9000}")
    private String endpoint;

    @Value("${file.oss.access-key:minioadmin}")
    private String accessKey;

    @Value("${file.oss.secret-key:minioadmin}")
    private String secretKey;

    @Value("${file.oss.bucket-name:my-bucket}")
    private String bucketName;

    @Value("${file.oss.base-path:files/}")
    private String basePath;

    @Value("${file.oss.url-prefix:${file.oss.endpoint}}")
    private String urlPrefix;

    private MinioClient minioClient;

    @PostConstruct
    public void init() {
        if (accessKey == null || accessKey.isEmpty()
                || secretKey == null || secretKey.isEmpty()) {
            log.warn("[MinIO] access-key or secret-key is empty, MinioClient will NOT be initialized. "
                    + "Please configure file.oss.access-key and file.oss.secret-key in application.yml");
            return;
        }
        minioClient = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
        log.info("[MinIO] client initialized: endpoint={}, bucket={}, urlPrefix={}", endpoint, bucketName, urlPrefix);

        // 启动时自动创建 bucket（若不存在）
        ensureBucketExists();
    }

    @PreDestroy
    public void destroy() {
        // MinioClient 无需显式关闭（内部使用 OkHttp，随 JVM 回收）
        log.info("[MinIO] bean destroyed");
    }

    @Override
    public UploadResult upload(MultipartFile file, String originalFilename) throws IOException {
        return upload(file.getBytes(), originalFilename);
    }

    @Override
    public UploadResult upload(byte[] bytes, String originalFilename) throws IOException {
        ensureClientAvailable();
        String saveName = UUID.randomUUID().toString().replace("-", "") + "_" + originalFilename;
        String objectKey = normalizeBasePath() + saveName;

        try (InputStream is = new ByteArrayInputStream(bytes)) {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectKey)
                            .stream(is, bytes.length, -1)
                            .build()
            );
        } catch (Exception e) {
            throw new IOException("[MinIO] upload failed: " + e.getMessage(), e);
        }

        UploadResult result = new UploadResult();
        result.setStorageType(UploadType.OSS.getCode());
        result.setFileKey(saveName);
        result.setUrl(buildUrl(objectKey));
        result.setSize(bytes.length);
        result.setMd5(DigestUtils.md5Hex(bytes));

        log.info("[MinIO] uploaded={}, objectKey={}", originalFilename, objectKey);
        return result;
    }

    @Override
    public boolean delete(String fileKey) throws IOException {
        if (fileKey == null || fileKey.isEmpty()) return false;
        ensureClientAvailable();
        String objectKey = normalizeBasePath() + fileKey;
        try {
            minioClient.removeObject(
                    RemoveObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectKey)
                            .build()
            );
            log.info("[MinIO] deleted, objectKey={}", objectKey);
            return true;
        } catch (Exception e) {
            log.error("[MinIO] delete failed, objectKey={}", objectKey, e);
            return false;
        }
    }

    @Override
    public byte[] download(String fileKey) throws IOException {
        if (fileKey == null || fileKey.isEmpty()) return null;
        ensureClientAvailable();
        String objectKey = normalizeBasePath() + fileKey;
        try (InputStream is = minioClient.getObject(
                GetObjectArgs.builder()
                        .bucket(bucketName)
                        .object(objectKey)
                        .build());
             java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int len;
            while ((len = is.read(buf)) != -1) {
                baos.write(buf, 0, len);
            }
            log.info("[MinIO] downloaded, objectKey={}", objectKey);
            return baos.toByteArray();
        } catch (Exception e) {
            log.warn("[MinIO] download failed, objectKey={}", objectKey, e);
            return null;
        }
    }

    @Override
    public UploadType getUploadType() {
        return UploadType.OSS;
    }

    // ==================== 私有辅助方法 ====================

    private void ensureClientAvailable() {
        if (minioClient == null) {
            throw new IllegalStateException("MinioClient 未初始化，请检查 file.oss.access-key / secret-key 配置");
        }
    }

    /**
     * 规范化 basePath：确保以 "/" 开头且不以 "/" 结尾
     * 例如 "files/" → "/files"，"/files/" → "/files"，"/files" → "/files"
     */
    private String normalizeBasePath() {
        String bp = basePath;
        if (bp == null || bp.isEmpty()) return "/";
        if (!bp.startsWith("/")) bp = "/" + bp;
        if (bp.endsWith("/")) bp = bp.substring(0, bp.length() - 1);
        return bp + "/";
    }

    /**
     * 构建不含 bucketName 的文件访问 URL
     * 格式：urlPrefix/objectKey
     */
    private String buildUrl(String objectKey) {
        String prefix = urlPrefix;
        if (prefix.endsWith("/")) prefix = prefix.substring(0, prefix.length() - 1);
        return prefix + objectKey;
    }

    /**
     * 启动时检查 bucket 是否存在，不存在则自动创建
     */
    private void ensureBucketExists() {
        try {
            boolean exists = minioClient.bucketExists(
                    BucketExistsArgs.builder()
                            .bucket(bucketName)
                            .build()
            );
            if (!exists) {
                minioClient.makeBucket(
                        MakeBucketArgs.builder()
                                .bucket(bucketName)
                                .build()
                );
                log.info("[MinIO] bucket '{}' created", bucketName);
            } else {
                log.info("[MinIO] bucket '{}' already exists", bucketName);
            }
        } catch (Exception e) {
            log.warn("[MinIO] failed to check/create bucket '{}': {}. "
                    + "MinIO server may not be running — operations will fail until it is available.", bucketName, e.getMessage());
        }
    }
}
