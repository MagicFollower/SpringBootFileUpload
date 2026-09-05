package com.example.fileupload.model;

import java.util.Date;

/**
 * 文件信息模型，封装上传前后的完整信息
 */
public class FileInfo {

    /** 原始文件信息 */
    private String originalFilename;   // 原始文件名（含后缀）
    private String pureFilename;       // 纯文件名（不含后缀）
    private String suffix;             // 后缀（如 ".jpg"）
    private long originalSize;         // 原始文件大小（字节）
    private String contentType;        // MIME 类型

    /** 存储后文件信息 */
    private String storageType;        // 存储类型标识：local / ftp / oss
    private String fileKey;            // 存储路径或唯一标识（相对存储根目录，如 UUID_filename.ext）
    private String fullPath;           // 完整文件路径（本地时含盘符的绝对路径，如 C:/data/uploads/xxx）
    private String relativePath;       // 相对路径（不含盘符）
    private long storedSize;           // 实际上传后的大小
    private String md5;                // 文件 MD5 校验值

    /** 元数据 */
    private String id;                 // 系统主键
    private String uploadedBy;         // 上传人（模拟字段）
    private Date uploadTime;           // 上传时间

    public FileInfo() {}

    // ========== Getter & Setter ==========

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getOriginalFilename() { return originalFilename; }
    public void setOriginalFilename(String originalFilename) { this.originalFilename = originalFilename; }

    public String getPureFilename() { return pureFilename; }
    public void setPureFilename(String pureFilename) { this.pureFilename = pureFilename; }

    public String getSuffix() { return suffix; }
    public void setSuffix(String suffix) { this.suffix = suffix; }

    public long getOriginalSize() { return originalSize; }
    public void setOriginalSize(long originalSize) { this.originalSize = originalSize; }

    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }

    public String getStorageType() { return storageType; }
    public void setStorageType(String storageType) { this.storageType = storageType; }

    public String getFileKey() { return fileKey; }
    public void setFileKey(String fileKey) { this.fileKey = fileKey; }

    public String getFullPath() { return fullPath; }
    public void setFullPath(String fullPath) { this.fullPath = fullPath; }

    public String getRelativePath() { return relativePath; }
    public void setRelativePath(String relativePath) { this.relativePath = relativePath; }

    public long getStoredSize() { return storedSize; }
    public void setStoredSize(long storedSize) { this.storedSize = storedSize; }

    public String getMd5() { return md5; }
    public void setMd5(String md5) { this.md5 = md5; }

    public String getUploadedBy() { return uploadedBy; }
    public void setUploadedBy(String uploadedBy) { this.uploadedBy = uploadedBy; }

    public Date getUploadTime() { return uploadTime; }
    public void setUploadTime(Date uploadTime) { this.uploadTime = uploadTime; }
}
