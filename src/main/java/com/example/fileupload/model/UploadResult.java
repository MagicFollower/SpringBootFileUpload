package com.example.fileupload.model;

/**
 * 上传策略执行后返回的通用结果，供处理器组装 FileInfo
 */
public class UploadResult {

    private String storageType;   // 存储类型标识
    private String fileKey;       // 存储路径或唯一标识
    private String url;           // 可访问 URL（FTP/OSS 有效）
    private String fullPath;      // 完整文件路径（本地时含盘符的绝对路径）
    private String relativePath;  // 相对路径（不含盘符）
    private long size;            // 文件大小（字节）
    private String md5;           // 文件 MD5 校验值

    public UploadResult() {}

    public String getStorageType() { return storageType; }
    public void setStorageType(String storageType) { this.storageType = storageType; }

    public String getFileKey() { return fileKey; }
    public void setFileKey(String fileKey) { this.fileKey = fileKey; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getFullPath() { return fullPath; }
    public void setFullPath(String fullPath) { this.fullPath = fullPath; }

    public String getRelativePath() { return relativePath; }
    public void setRelativePath(String relativePath) { this.relativePath = relativePath; }

    public long getSize() { return size; }
    public void setSize(long size) { this.size = size; }

    public String getMd5() { return md5; }
    public void setMd5(String md5) { this.md5 = md5; }
}
