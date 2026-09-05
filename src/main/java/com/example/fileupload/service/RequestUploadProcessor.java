package com.example.fileupload.service;

import com.example.fileupload.enums.UploadType;
import com.example.fileupload.model.FileInfo;
import com.example.fileupload.model.UploadResult;
import com.example.fileupload.strategy.UploadStrategy;
import com.example.fileupload.strategy.UploadStrategyFactory;
import com.example.fileupload.util.FileTypeValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 文件上传统一处理器（门面模式）
 * <p>
 * 封装：请求解析 → 类型校验 → 策略路由 → 结果组装 全流程。
 * Controller 只需调用此类的 upload() / delete() 方法即可。
 */
@Component
public class RequestUploadProcessor {

    private static final Logger log = LoggerFactory.getLogger(RequestUploadProcessor.class);

    private final UploadStrategyFactory strategyFactory;

    public RequestUploadProcessor(UploadStrategyFactory strategyFactory) {
        this.strategyFactory = strategyFactory;
    }

    // ==================== 公共接口 ====================

    /**
     * 处理多文件上传请求
     *
     * @param multipartRequest Spring MVC 的 multipart 请求
     * @param fieldNames       文件字段名列表（支持单文件时传长度为 1 的列表）
     * @param uploadType       上传类型枚举
     * @return FileInfo 列表（与传入的 fieldNames 一一对应，空字段被跳过）
     */
    public List<FileInfo> process(org.springframework.web.multipart.MultipartHttpServletRequest multipartRequest,
                                  List<String> fieldNames,
                                  UploadType uploadType) throws Exception {
        List<FileInfo> results = new ArrayList<>();

        for (String fieldName : fieldNames) {
            MultipartFile file = multipartRequest.getFile(fieldName);
            if (file == null || file.isEmpty()) {
                log.warn("Field '{}' is empty or missing, skipping", fieldName);
                continue;
            }

            String originalFilename = file.getOriginalFilename();
            log.debug("Processing upload: filename={}, size={}, type={}", originalFilename, file.getSize(), uploadType.getCode());

            // 一次性读取字节内容（避免 Windows + Tomcat 下临时文件被重复锁定无法删除）
            byte[] bytes = file.getBytes();

            // 1. 读取文件头字节用于魔数校验
            byte[] headerBytes = new byte[16];
            java.io.ByteArrayInputStream bis = new java.io.ByteArrayInputStream(bytes);
            int readCount = bis.read(headerBytes, 0, Math.min(bis.available(), headerBytes.length));
            if (bytes.length < 2 || readCount < 2) {
                throw new IllegalArgumentException("文件内容为空或太小: " + originalFilename);
            }
            bis.reset();
            FileTypeValidator.validate(originalFilename, bis);

            // 2. 获取策略并执行上传（使用字节数组，不触碰 Tomcat 临时文件）
            UploadStrategy strategy = strategyFactory.getStrategy(uploadType);
            UploadResult uploadResult = strategy.upload(bytes, originalFilename);

            // 3. 封装 FileInfo
            FileInfo fileInfo = buildFileInfo(originalFilename, file, uploadType, uploadResult);
            results.add(fileInfo);
        }

        if (results.isEmpty()) {
            throw new IllegalArgumentException("没有接收到任何有效文件");
        }
        return results;
    }

//    /**
//     * 单文件上传快捷方法
//     */
//    public FileInfo processSingle(org.springframework.web.multipart.MultipartHttpServletRequest multipartRequest,
//                                  String fieldName,
//                                  UploadType uploadType) throws Exception {
//        List<String> fields = new ArrayList<>(1);
//        fields.add(fieldName);
//        List<FileInfo> results = process(multipartRequest, fields, uploadType);
//        if (results.isEmpty()) {
//            throw new IllegalArgumentException("未找到字段 '" + fieldName + "' 的有效文件");
//        }
//        return results.get(0);
//    }

    /**
     * 删除文件
     *
     * @param uploadType 存储类型
     * @param fileKey    文件唯一标识
     * @return 是否删除成功
     */
    public boolean delete(UploadType uploadType, String fileKey) throws IOException {
        UploadStrategy strategy = strategyFactory.getStrategy(uploadType);
        return strategy.delete(fileKey);
    }

    /**
     * 下载文件（按策略路由到对应实现）
     *
     * @param uploadType 存储类型
     * @param fileKey    文件唯一标识
     * @return 文件字节数组，不存在返回 null
     */
    public byte[] download(UploadType uploadType, String fileKey) throws IOException {
        UploadStrategy strategy = strategyFactory.getStrategy(uploadType);
        byte[] data = strategy.download(fileKey);
        if (data == null) {
            log.warn("[DOWNLOAD] file not found: fileKey={}, type={}", fileKey, uploadType.getCode());
        }
        return data;
    }

    // ==================== 私有辅助方法 ====================

    private FileInfo buildFileInfo(String originalFilename, MultipartFile file,
                                   UploadType uploadType, UploadResult uploadResult) {
        FileInfo info = new FileInfo();
        info.setId(UUID.randomUUID().toString().replace("-", ""));
        info.setOriginalFilename(originalFilename);
        info.setPureFilename(FileTypeValidator.getPureFilename(originalFilename));
        info.setSuffix(FileTypeValidator.getSuffix(originalFilename));
        info.setOriginalSize(file.getSize());
        info.setContentType(file.getContentType());
        info.setStorageType(uploadType.getCode());
        info.setFileKey(uploadResult.getFileKey());
        info.setFullPath(uploadResult.getFullPath());
        info.setRelativePath(uploadResult.getRelativePath());
        info.setStoredSize(uploadResult.getSize());
        info.setMd5(uploadResult.getMd5());
        info.setUploadedBy("system");
        info.setUploadTime(new java.util.Date());
        return info;
    }
}
