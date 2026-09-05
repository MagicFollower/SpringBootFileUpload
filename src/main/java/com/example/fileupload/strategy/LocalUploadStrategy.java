package com.example.fileupload.strategy;

import org.apache.commons.codec.digest.DigestUtils;
import com.example.fileupload.model.UploadResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.util.UUID;

/**
 * 本地磁盘存储策略
 * <p>
 * 文件以 UUID + 原始文件名保存，支持上传 / 下载 / 删除。
 * <p>
 * 配置文件中的 base-path 说明：
 *   - /file-upload-storage/  → 当前盘符根目录，如 C:\file-upload-storage（Win）
 *                              或 /file-upload-storage（Linux）
 *   - data/uploads/          → 相对当前工作目录
 */
@Component
public class LocalUploadStrategy implements UploadStrategy {

    private static final Logger log = LoggerFactory.getLogger(LocalUploadStrategy.class);

    @Value("${file.upload.base-path:/file-upload-storage/}")
    private String basePathConfig;

    /** 实际使用的磁盘绝对路径（已解析好盘符） */
    private String resolvedBasePath;

    /** base-dir：用于 generateUniqueName 碰撞检查 */
    private String baseDirForCheck;

    @PostConstruct
    public void init() {
        // 1. 解析 base-path
        if (basePathConfig.startsWith("/") || basePathConfig.startsWith("\\")) {
            // 根目录相对路径：去掉前导斜杠后拼当前盘的根目录
            String relPart = basePathConfig.replaceFirst("^[\\/]+", "");
            File[] roots = File.listRoots();
            if (roots.length == 0) {
                throw new IllegalStateException("无法枚举系统磁盘根目录");
            }
            resolvedBasePath = roots[0].getPath() + java.io.File.separator + relPart;
        } else if (basePathConfig.startsWith("file:") || basePathConfig.startsWith("classPATH:")) {
            // Spring 资源前缀，交给 ClassPathResource / FileSystemResource 处理
            resolvedBasePath = basePathConfig;
        } else {
            // 相对路径（基于当前工作目录）或普通绝对路径
            resolvedBasePath = basePathConfig;
        }

        // 2. 记录日志便于排查
        log.info("[LOCAL] basePathConfig={}, resolvedAbsolutePath={}", basePathConfig, resolvedBasePath);
    }

    @Override
    public UploadResult upload(MultipartFile file, String originalFilename) throws IOException {
        return upload(file.getBytes(), originalFilename);
    }

    @Override
    public UploadResult upload(byte[] bytes, String originalFilename) throws IOException {
        // 生成不冲突的保存名，若文件名碰撞则追加序号
        String saveName = generateUniqueName(resolvedBasePath, originalFilename);
        File dest = new File(resolvedBasePath, saveName);

        // 确保目录存在
        if (!dest.getParentFile().exists()) {
            dest.getParentFile().mkdirs();
        }

        java.nio.file.Files.write(dest.toPath(), bytes);

        UploadResult result = buildResult(saveName, dest, bytes.length);
        log.info("[LOCAL] uploaded={}, saveName={}", originalFilename, saveName);
        return result;
    }

    @Override
    public boolean delete(String fileKey) throws IOException {
        if (fileKey == null || fileKey.isEmpty()) {
            return false;
        }
        File file = new File(resolvedBasePath, fileKey);
        if (file.exists()) {
            boolean deleted = file.delete();
            log.info("[LOCAL] deleted={}, success={}", fileKey, deleted);
            return deleted;
        }
        return false;
    }

    @Override
    public byte[] download(String fileKey) {
        if (fileKey == null || fileKey.isEmpty()) return null;
        File file = new File(resolvedBasePath, fileKey);
        if (!file.exists() || !file.isFile()) {
            return null;
        }
        try {
            return org.apache.commons.io.FileUtils.readFileToByteArray(file);
        } catch (IOException e) {
            log.error("[LOCAL] download failed, fileKey={}", fileKey, e);
            return null;
        }
    }

    @Override
    public com.example.fileupload.enums.UploadType getUploadType() {
        return com.example.fileupload.enums.UploadType.LOCAL;
    }

    /** 辅助方法：组装 UploadResult */
    private UploadResult buildResult(String saveName, File dest, long fileSize) throws IOException {
        UploadResult result = new UploadResult();
        result.setStorageType(com.example.fileupload.enums.UploadType.LOCAL.getCode());
        result.setFileKey(saveName);
        // fullPath: 含盘符的绝对路径，如 C:\file-upload-storage\xxx
        String absPath = dest.getAbsolutePath();
        result.setFullPath(absPath);

        // relativePath: 包含 base-path 名称的相对路径
        // 例：file-upload-storage/UUID_filename.png
        String relPath = computeRelativePath(absPath);
        result.setRelativePath(relPath);

        result.setSize(fileSize);
        result.setMd5(DigestUtils.md5Hex(org.apache.commons.io.FileUtils.readFileToByteArray(dest)));
        return result;
    }

    /**
     * 计算相对于 basePathConfig 的相对路径，保证返回格式如
     * "file-upload-storage/UUID_filename.ext"
     */
    private String computeRelativePath(String absolutePath) {
        // 剥离前导斜杠得到配置值的关键部分
        String cleanConfig = basePathConfig.replaceFirst("^[\\/]+", "");
        // 取最后一个 "/" 后的部分作为目录名，即 "file-upload-storage"
        int lastSep = Math.max(cleanConfig.lastIndexOf('/'), cleanConfig.lastIndexOf('\\'));
        String dirName = (lastSep >= 0) ? cleanConfig.substring(0, lastSep + 1) : "";
        // 去掉尾部的斜杠
        String dirNameClean = dirName.endsWith("/") ? dirName.substring(0, dirName.length() - 1)
                  : dirName.endsWith("\\") ? dirName.substring(0, dirName.length() - 1)
                  : dirName;

        // 从绝对路径中剥离 basePath 前缀，加上目录名
        String afterBase = absolutePath;
        String baseAbs = new File(resolvedBasePath).getAbsolutePath();
        if (afterBase.startsWith(baseAbs)) {
            afterBase = afterBase.substring(baseAbs.length());
        }

        String sep = java.io.File.separator;
        String normalizedAfter = afterBase.replace(sep, "/");

        if (dirNameClean.isEmpty()) {
            return normalizedAfter;
        }
        return dirNameClean + "/" + normalizedAfter;
    }

    /**
     * 生成唯一的文件名：UUID + 原始文件名。
     * 如果文件已存在（极低概率碰撞），追加序号直到唯一。
     */
    private String generateUniqueName(String basePath, String originalFilename) {
        // 从 basePath 中提取基础目录（不含最后的文件名部分），用于构造 File 对象时不带完整路径
        int lastSep = Math.max(basePath.lastIndexOf('/'), basePath.lastIndexOf('\\'));
        String baseDir = (lastSep >= 0) ? basePath.substring(0, lastSep + 1) : basePath;

        for (int i = 0; i < Integer.MAX_VALUE; i++) {
            String name = UUID.randomUUID().toString().replace("-", "")
                    + (i > 0 ? "_" + i : "")
                    + "_" + originalFilename;
            if (!new File(baseDir, name).exists()) {
                return name;
            }
        }
        throw new RuntimeException("无法生成唯一文件名: " + originalFilename);
    }
}
