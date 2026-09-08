package com.example.fileupload.strategy;

import com.example.fileupload.model.UploadResult;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 文件上传策略接口 — 每种存储方式实现此接口
 */
public interface UploadStrategy {

    /**
     * 执行文件上传
     *
     * @param file           上传的文件对象
     * @param originalFilename 原始文件名
     * @return 上传结果（包含 storageType, fileKey, url 等）
     */
    UploadResult upload(MultipartFile file, String originalFilename) throws IOException;

    /**
     * 从字节数组执行文件上传（避免重复读取临时文件）
     *
     * @param bytes          文件字节内容
     * @param originalFilename 原始文件名
     * @return 上传结果
     */
    default UploadResult upload(byte[] bytes, String originalFilename) throws IOException {
        throw new UnsupportedOperationException("Not implemented");
    }

    /**
     * 删除文件
     *
     * @param fileKey   文件在存储系统中的唯一标识
     * @return 是否删除成功
     */
    boolean delete(String fileKey) throws IOException;

    /**
     * 返回该策略支持的上传类型
     */
    com.example.fileupload.enums.UploadType getUploadType();

    /**
     * 下载文件（按 fileKey 获取字节流）
     *
     * @param fileKey 文件唯一标识
     * @return 文件字节数组，不存在时返回 null
     */
    byte[] download(String fileKey) throws IOException;

    /**
     * 批量上传多个文件（默认实现逐个调用单文件上传）
     * <p>
     * 各策略可 override 以优化连接复用（如 FTP 复用单个 TCP 连接）。
     *
     * @param files 待上传文件列表
     * @return 各文件的上传结果
     */
    default List<UploadResult> batchUpload(List<MultipartFile> files) throws IOException {
        List<UploadResult> results = new ArrayList<>(files.size());
        for (MultipartFile file : files) {
            if (file != null && !file.isEmpty()) {
                results.add(upload(file.getBytes(), file.getOriginalFilename()));
            }
        }
        return results;
    }
}
