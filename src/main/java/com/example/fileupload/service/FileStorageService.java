package com.example.fileupload.service;

import com.example.fileupload.model.FileInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 文件元数据存储（内存 Mock）
 * <p>
 * 真实项目中应替换为 MySQL / MongoDB 持久化层。
 * 此处使用 ConcurrentHashMap 模拟，支持 CRUD + 简单搜索。
 */
@Service
public class FileStorageService {

    private static final Logger log = LoggerFactory.getLogger(FileStorageService.class);

    /** id -> FileInfo */
    private final Map<String, FileInfo> store = new ConcurrentHashMap<>();

    // ==================== 增删查 ====================

    /**
     * 保存文件信息（上传后调用）
     */
    public FileInfo save(FileInfo info) {
        if (info.getId() == null || info.getId().isEmpty()) {
            info.setId(UUID.randomUUID().toString().replace("-", ""));
        }
        store.put(info.getId(), info);
        log.info("[FileStorage] saved: id={}, fileKey={}", info.getId(), info.getFileKey());
        return info;
    }

    /**
     * 按 ID 查找
     */
    public Optional<FileInfo> findById(String id) {
        return Optional.ofNullable(store.get(id));
    }

    /**
     * 删除（按 ID）
     */
    public boolean deleteById(String id) {
        return store.remove(id) != null;
    }

    /**
     * 查询所有文件
     */
    public List<FileInfo> findAll() {
        return new ArrayList<>(store.values());
    }

    /**
     * 模糊搜索文件名（包含 pureFilename 和 originalFilename）
     */
    public List<FileInfo> searchByName(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return findAll();
        }
        String lower = keyword.toLowerCase();
        return store.values().stream()
                .filter(f -> f.getOriginalFilename() != null && f.getOriginalFilename().toLowerCase().contains(lower)
                        || f.getPureFilename() != null && f.getPureFilename().toLowerCase().contains(lower))
                .collect(Collectors.toList());
    }

    /**
     * 按存储类型过滤
     */
    public List<FileInfo> findByStorageType(String storageType) {
        return store.values().stream()
                .filter(f -> storageType.equalsIgnoreCase(f.getStorageType()))
                .collect(Collectors.toList());
    }

    /**
     * 按后缀过滤
     */
    public List<FileInfo> findBySuffix(String suffix) {
        if (suffix == null || suffix.isEmpty()) {
            return findAll();
        }
        String s = suffix.toLowerCase();
        return store.values().stream()
                .filter(f -> f.getSuffix() != null && f.getSuffix().equalsIgnoreCase(s))
                .collect(Collectors.toList());
    }

    /**
     * 清除所有数据（用于测试）
     */
    public void clear() {
        store.clear();
    }

    public int size() {
        return store.size();
    }

    // ==================== 初始化 Mock 数据 ====================

    @PostConstruct
    public void initMockData() {
        if (!store.isEmpty()) {
            return; // 避免重复初始化
        }

        log.info("Initializing mock data...");

        // Mock 字节数组（仅用于演示，实际不会在此使用）

        store.put("mock-001", buildMockInfo("report_2024.pdf", "pdf", "local", "/uploads/mock-001_report_2024.pdf"));
        store.put("mock-002", buildMockInfo("banner.png", "png", "ftp", "FTP/uploads/mock-002_banner.png"));
        store.put("mock-003", buildMockInfo("photo_vacation.jpg", "jpg", "oss", "files/mock-003_photo_vacation.jpg"));
        store.put("mock-004", buildMockInfo("budget.xlsx", "xlsx", "local", "/uploads/mock-004_budget.xlsx"));
        store.put("mock-005", buildMockInfo("logo_white.png", "png", "ftp", "FTP/uploads/mock-005_logo_white.png"));
        store.put("mock-006", buildMockInfo("contract.docx", "docx", "oss", "files/mock-006_contract.docx"));
        store.put("mock-007", buildMockInfo("archive.zip", "zip", "local", "/uploads/mock-007_archive.zip"));
        store.put("mock-008", buildMockInfo("avatar.bmp", "bmp", "ftp", "FTP/uploads/mock-008_avatar.bmp"));

        log.info("Mock data initialized: {} records", store.size());
    }

    private FileInfo buildMockInfo(String filename, String ext, String storageType, String fileKey) {
        FileInfo info = new FileInfo();
        info.setId("mock-" + UUID.randomUUID().toString().substring(0, 8).replace("-", ""));
        info.setOriginalFilename(filename);
        info.setPureFilename(extractName(filename, ext));
        info.setSuffix("." + ext);
        info.setOriginalSize((long) (new Random().nextInt(500000) + 10000));
        info.setContentType(getContentType(ext));
        info.setStorageType(storageType);
        info.setFileKey(fileKey);
        // fullPath 和 relativePath：mock 数据中仅本地存储有实际磁盘路径，其余留空
        if ("local".equalsIgnoreCase(storageType)) {
            String sep = java.io.File.separator;
            String dirName = "file-upload-storage";
            String fullPath = "C:" + sep + dirName + sep + fileKey;
            info.setFullPath(fullPath.replace('\\', '/'));
            // relativePath 需要包含 base-path 目录名
            info.setRelativePath(dirName + "/" + fileKey);
        }
        info.setStoredSize(info.getOriginalSize());
        info.setMd5("md5_" + UUID.randomUUID().toString().substring(0, 16));
        info.setUploadedBy(new String[]{"zhangsan", "lisi", "wangwu"}[new Random().nextInt(3)]);
        info.setUploadTime(new Date(System.currentTimeMillis() - new Random().nextInt(2592000)));
        return info;
    }

    private String extractName(String filename, String ext) {
        int dot = filename.lastIndexOf('.');
        return (dot > 0) ? filename.substring(0, dot) : filename;
    }

    private String getContentType(String ext) {
        switch (ext.toLowerCase()) {
            case "pdf": return "application/pdf";
            case "png": return "image/png";
            case "jpg":
            case "jpeg": return "image/jpeg";
            case "gif": return "image/gif";
            case "bmp": return "image/bmp";
            case "xls":
            case "xlsx": return "application/vnd.ms-excel";
            case "doc":
            case "docx": return "application/msword";
            case "txt": return "text/plain";
            case "csv": return "text/csv";
            case "zip": return "application/zip";
            case "rar": return "application/x-rar-compressed";
            default: return "application/octet-stream";
        }
    }
}
