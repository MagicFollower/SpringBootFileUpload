package com.example.fileupload.strategy;

import org.apache.commons.codec.digest.DigestUtils;
import com.example.fileupload.model.UploadResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.UUID;

/**
 * 阿里云 OSS 上传策略（Mock 实现）
 *
 * 真实项目中使用 aliyun-sdk-oss。此处将文件内容存入内存模拟"上传到 OSS Bucket"。
 */
@Component
public class OssUploadStrategy implements UploadStrategy {

    private static final Logger log = LoggerFactory.getLogger(OssUploadStrategy.class);

    @Value("${file.oss.endpoint:https://oss-cn-hangzhou.aliyuncs.com}")
    private String endpoint;

    @Value("${file.oss.bucket-name:my-bucket}")
    private String bucketName;

    @Value("${file.oss.basePath:files/}")
    private String ossBasePath;

    // Mock 存储：key -> byte[]
    private final java.util.Map<String, byte[]> mockStore = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public UploadResult upload(MultipartFile file, String originalFilename) throws IOException {
        return upload(file.getBytes(), originalFilename);
    }

    @Override
    public UploadResult upload(byte[] bytes, String originalFilename) throws IOException {
        String saveName = UUID.randomUUID().toString().replace("-", "") + "_" + originalFilename;
        String objectKey = ossBasePath + saveName;

        mockStore.put(saveName, bytes);

        UploadResult result = new UploadResult();
        result.setStorageType(com.example.fileupload.enums.UploadType.OSS.getCode());
        result.setFileKey(saveName);
        result.setUrl(endpoint + "/" + bucketName + "/" + objectKey);
        result.setSize(bytes.length);
        result.setMd5(DigestUtils.md5Hex(bytes));

        log.info("[OSS-MOCK] uploaded={}, key={}", originalFilename, objectKey);
        return result;
    }

    @Override
    public boolean delete(String fileKey) {
        if (fileKey == null) return false;
        log.info("[OSS-MOCK] deleting={}, exists={}", fileKey, mockStore.containsKey(fileKey));
        return mockStore.remove(fileKey) != null;
    }

    @Override
    public byte[] download(String fileKey) {
        if (fileKey == null) return null;
        return mockStore.getOrDefault(fileKey, null);
    }

    @Override
    public com.example.fileupload.enums.UploadType getUploadType() {
        return com.example.fileupload.enums.UploadType.OSS;
    }

    public int getMockSize() {
        return mockStore.size();
    }
}
