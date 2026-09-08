package com.example.fileupload.strategy;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.OSSObject;
import org.apache.commons.codec.digest.DigestUtils;
import com.example.fileupload.model.UploadResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

/**
 * 阿里云 OSS 上传策略（真实实现）
 * <p>
 * 使用 aliyun-sdk-oss 的 OSSClient 执行 putObject / getObject / deleteObject。
 * 客户端生命周期由 Spring 管理（@PostConstruct 创建，@PreDestroy 关闭）。
 */
@Component
public class OssUploadStrategy implements UploadStrategy {

    private static final Logger log = LoggerFactory.getLogger(OssUploadStrategy.class);

    @Value("${file.oss.endpoint:https://oss-cn-hangzhou.aliyuncs.com}")
    private String endpoint;

    @Value("${file.oss.access-key-id:}")
    private String accessKeyId;

    @Value("${file.oss.access-key-secret:}")
    private String accessKeySecret;

    @Value("${file.oss.bucket-name:my-bucket}")
    private String bucketName;

    @Value("${file.oss.basePath:files/}")
    private String ossBasePath;

    private OSS ossClient;

    @PostConstruct
    public void init() {
        if (accessKeyId == null || accessKeyId.isEmpty()
                || accessKeySecret == null || accessKeySecret.isEmpty()) {
            log.warn("[OSS] access-key-id or access-key-secret is empty, OSSClient will NOT be initialized. "
                    + "Please configure file.oss.access-key-id and file.oss.access-key-secret in application.yml");
            return;
        }
        ossClient = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
        log.info("[OSS] client initialized: endpoint={}, bucket={}", endpoint, bucketName);
    }

    @PreDestroy
    public void destroy() {
        if (ossClient != null) {
            ossClient.shutdown();
            log.info("[OSS] client shut down");
        }
    }

    @Override
    public UploadResult upload(MultipartFile file, String originalFilename) throws IOException {
        return upload(file.getBytes(), originalFilename);
    }

    @Override
    public UploadResult upload(byte[] bytes, String originalFilename) throws IOException {
        ensureClientAvailable();
        String saveName = UUID.randomUUID().toString().replace("-", "") + "_" + originalFilename;
        String objectKey = ossBasePath + saveName;

        ossClient.putObject(bucketName, objectKey, new java.io.ByteArrayInputStream(bytes));

        UploadResult result = new UploadResult();
        result.setStorageType(com.example.fileupload.enums.UploadType.OSS.getCode());
        result.setFileKey(saveName);
        result.setUrl(endpoint + "/" + bucketName + "/" + objectKey);
        result.setSize(bytes.length);
        result.setMd5(DigestUtils.md5Hex(bytes));

        log.info("[OSS] uploaded={}, objectKey={}", originalFilename, objectKey);
        return result;
    }

    @Override
    public boolean delete(String fileKey) throws IOException {
        if (fileKey == null || fileKey.isEmpty()) return false;
        ensureClientAvailable();
        String objectKey = ossBasePath + fileKey;
        try {
            ossClient.deleteObject(bucketName, objectKey);
            log.info("[OSS] deleted, objectKey={}", objectKey);
            return true;
        } catch (Exception e) {
            log.error("[OSS] delete failed, objectKey={}", objectKey, e);
            return false;
        }
    }

    @Override
    public byte[] download(String fileKey) throws IOException {
        if (fileKey == null || fileKey.isEmpty()) return null;
        ensureClientAvailable();
        String objectKey = ossBasePath + fileKey;
        try {
            OSSObject obj = ossClient.getObject(bucketName, objectKey);
            try (InputStream is = obj.getObjectContent();
                 ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                byte[] buf = new byte[4096];
                int len;
                while ((len = is.read(buf)) != -1) {
                    baos.write(buf, 0, len);
                }
                log.info("[OSS] downloaded, objectKey={}", objectKey);
                return baos.toByteArray();
            }
        } catch (Exception e) {
            log.warn("[OSS] download failed, objectKey={}", objectKey, e);
            return null;
        }
    }

    @Override
    public com.example.fileupload.enums.UploadType getUploadType() {
        return com.example.fileupload.enums.UploadType.OSS;
    }

    private void ensureClientAvailable() {
        if (ossClient == null) {
            throw new IllegalStateException("OSSClient 未初始化，请检查 file.oss.access-key-id / access-key-secret 配置");
        }
    }
}
