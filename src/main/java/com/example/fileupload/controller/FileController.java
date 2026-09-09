package com.example.fileupload.controller;

import com.example.fileupload.enums.UploadType;
import com.example.fileupload.model.FileInfo;
import com.example.fileupload.model.Result;
import com.example.fileupload.model.UploadResult;
import com.example.fileupload.service.FileStorageService;
import com.example.fileupload.service.FilePreviewService;
import com.example.fileupload.service.RequestUploadProcessor;
import com.example.fileupload.strategy.UploadStrategy;
import com.example.fileupload.strategy.UploadStrategyFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 * 文件管理 REST API
 * <p>
 * 提供上传、下载、删除、查询、Mock数据初始化五个端点。
 */
@RestController
@RequestMapping("/api/files")
public class FileController {

    private static final Logger log = LoggerFactory.getLogger(FileController.class);

    private final RequestUploadProcessor processor;
    private final FileStorageService fileStorageService;
    private final UploadStrategyFactory strategyFactory;
    private final FilePreviewService previewService;

    @Value("${file.upload.base-path:/testPath/}")
    private String localBasePath;

    public FileController(RequestUploadProcessor processor, FileStorageService fileStorageService,
                          UploadStrategyFactory strategyFactory, FilePreviewService previewService) {
        this.processor = processor;
        this.fileStorageService = fileStorageService;
        this.strategyFactory = strategyFactory;
        this.previewService = previewService;
    }

    // ==================== 1. 上传接口 ====================

    /**
     * POST /api/files/upload?uploadType=local
     * <p>
     * 接收 multipart/form-data，字段名 file
     * uploadType: local / ftp / oss
     */
    @PostMapping("/upload")
    public Result<?> upload(@RequestParam("uploadType") String uploadTypeCode,
                            @RequestParam(value = "uploadedBy", required = false, defaultValue = "system") String uploadedBy,
                            HttpServletRequest request) {
        try {
            UploadType uploadType = UploadType.fromCode(uploadTypeCode);
            // 需要 spring MVC 解析后的 MultipartHttpServletRequest
            if (!(request instanceof org.springframework.web.multipart.MultipartHttpServletRequest)) {
                return Result.error(400, "请求必须是 multipart/form-data 类型");
            }
            org.springframework.web.multipart.MultipartHttpServletRequest multipartRequest =
                    (org.springframework.web.multipart.MultipartHttpServletRequest) request;

            List<FileInfo> results = processor.process(multipartRequest, Collections.singletonList("file"), uploadType);

            for (FileInfo info : results) {
                info.setUploadedBy(uploadedBy);
                fileStorageService.save(info);
            }
            return Result.success(results.isEmpty() ? null : results.get(0));
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        } catch (Exception e) {
            log.error("File upload failed", e);
            return Result.error(500, "上传失败: " + e.getMessage());
        }
    }

    // ==================== 1b. 批量上传接口（FTP/OSS） ====================

    /**
     * POST /api/files/upload/batch?uploadType=ftp
     * <p>
     * 多文件字段名固定为 files，支持一次上传多个文件，复用同一个 FTP 连接。
     */
    @PostMapping("/upload/batch")
    public Result<?> batchUpload(@RequestParam("uploadType") String uploadTypeCode,
                                 @RequestParam(value = "uploadedBy", required = false, defaultValue = "system") String uploadedBy,
                                 HttpServletRequest request) {
        try {
            UploadType uploadType = UploadType.fromCode(uploadTypeCode);

            if (!(request instanceof org.springframework.web.multipart.MultipartHttpServletRequest)) {
                return Result.error(400, "请求必须是 multipart/form-data 类型");
            }
            org.springframework.web.multipart.MultipartHttpServletRequest multipartRequest =
                    (org.springframework.web.multipart.MultipartHttpServletRequest) request;

            // 取 files 字段（可传多个文件）
            java.util.List<MultipartFile> files = multipartRequest.getFiles("files");
            if (files.isEmpty()) {
                return Result.error(400, "未找到有效文件");
            }

            // 统一走策略接口的 batchUpload，各策略自行决定优化方式
            UploadStrategy strategy = strategyFactory.getStrategy(uploadType);
            java.util.List<UploadResult> uploadResults = strategy.batchUpload(files);

            java.util.List<FileInfo> results = new java.util.ArrayList<>(uploadResults.size());
            for (int i = 0; i < uploadResults.size(); i++) {
                UploadResult r = uploadResults.get(i);
                MultipartFile mf = files.get(i);
                FileInfo info = buildFileInfo(mf, uploadType, r);
                info.setUploadedBy(uploadedBy);
                fileStorageService.save(info);
                results.add(info);
            }

            return Result.success(results);
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        } catch (Exception e) {
            log.error("Batch file upload failed", e);
            return Result.error(500, "批量上传失败: " + e.getMessage());
        }
    }

    /**
     * 将 UploadResult 转为 FileInfo（非持久化用）
     */
    private FileInfo buildFileInfo(MultipartFile file, UploadType uploadType, UploadResult uploadResult) {
        FileInfo info = new FileInfo();
        info.setId(java.util.UUID.randomUUID().toString().replace("-", ""));
        info.setOriginalFilename(file.getOriginalFilename());
        info.setPureFilename(com.example.fileupload.util.FileTypeValidator.getPureFilename(file.getOriginalFilename()));
        info.setSuffix(com.example.fileupload.util.FileTypeValidator.getSuffix(file.getOriginalFilename()));
        info.setOriginalSize(file.getSize());
        info.setContentType(file.getContentType());
        info.setStorageType(uploadType.getCode());
        info.setFileKey(uploadResult.getFileKey());
        info.setFullPath(uploadResult.getFullPath());
        info.setRelativePath(uploadResult.getRelativePath());
        info.setMd5(uploadResult.getMd5());
        info.setUploadedBy("system");
        info.setUploadTime(new java.util.Date());
        return info;
    }

    // ==================== 2. 下载接口 ====================

    /**
     * GET /api/files/download?fileKey=xxx&uploadType=local
     * <p>
     * 按策略路由到对应的 download 实现，返回文件字节流。
     */
    @GetMapping("/download")
    public ResponseEntity<byte[]> download(@RequestParam("fileKey") String fileKey,
                                           @RequestParam("uploadType") String uploadTypeCode) {
        try {
            UploadType uploadType = UploadType.fromCode(uploadTypeCode);
            byte[] data = processor.download(uploadType, fileKey);
            if (data == null || data.length == 0) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(("File not found: " + fileKey).getBytes());
            }

            // 从存储中查找原始文件名用于响应头
            String originalFilename = fileKey;
            List<FileInfo> all = fileStorageService.findAll();
            for (FileInfo fi : all) {
                if (fi.getFileKey().equals(fileKey) && fi.getStorageType().equalsIgnoreCase(uploadType.getCode())) {
                    originalFilename = fi.getOriginalFilename();
                    break;
                }
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            headers.setContentDispositionFormData("attachment", originalFilename);
            headers.setContentLength(data.length);

            return ResponseEntity.ok().headers(headers).body(data);
        } catch (Exception e) {
            log.error("File download failed", e);
            return ResponseEntity.internalServerError().body(("Download failed: " + e.getMessage()).getBytes());
        }
    }

    // ==================== 3. 删除接口 ====================

    /**
     * DELETE /api/files/{id}?uploadType=local
     * <p>
     * 先删除底层存储中的文件，再清除元数据。
     */
    @DeleteMapping("/{id}")
    public Result<?> deleteById(@PathVariable String id,
                                   @RequestParam("uploadType") String uploadTypeCode) {
        try {
            UploadType uploadType = UploadType.fromCode(uploadTypeCode);
            FileInfo fileInfo = fileStorageService.findById(id)
                    .orElse(null);
            if (fileInfo == null) {
                return Result.error(404, "文件不存在: " + id);
            }

            boolean deleted = processor.delete(uploadType, fileInfo.getFileKey());
            if (deleted) {
                fileStorageService.deleteById(id);
                return Result.success("删除成功");
            } else {
                return Result.error(500, "删除失败：底层存储中的文件可能不存在或权限不足");
            }
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        } catch (IOException e) {
            log.error("Delete failed: id={}", id, e);
            return Result.error(500, "删除失败: " + e.getMessage());
        }
    }

    // ==================== 4. 查询接口 ====================

    /**
     * GET /api/files?page=1&size=10&keyword=banner&storageType=local&suffix=.png
     * <p>
     * 支持多维度组合查询，返回列表 + 总数。
     */
    @GetMapping
    public Result<?> query(@RequestParam(defaultValue = "1") int page,
                           @RequestParam(defaultValue = "20") int size,
                           @RequestParam(required = false) String keyword,
                           @RequestParam(required = false) String storageType,
                           @RequestParam(required = false) String suffix) {
        try {
            List<FileInfo> list;
            if (keyword != null && !keyword.trim().isEmpty()) {
                list = fileStorageService.searchByName(keyword);
            } else {
                list = fileStorageService.findAll();
                if (storageType != null && !storageType.trim().isEmpty()) {
                    list = list.stream()
                            .filter(f -> f.getStorageType().equalsIgnoreCase(storageType))
                            .collect(java.util.stream.Collectors.toList());
                }
                if (suffix != null && !suffix.trim().isEmpty()) {
                    list = list.stream()
                            .filter(f -> f.getSuffix() != null && f.getSuffix().equalsIgnoreCase(suffix))
                            .collect(java.util.stream.Collectors.toList());
                }
            }

            // 分页
            int total = list.size();
            int start = Math.min((page - 1) * size, total);
            int end = Math.min(start + size, total);
            if (start >= total) {
                list = Collections.emptyList();
            } else {
                list = list.subList(start, end);
            }

            java.util.Map<String, Object> result = new java.util.HashMap<>();
            result.put("total", total);
            result.put("page", page);
            result.put("size", size);
            result.put("list", list);
            return Result.success(result);
        } catch (Exception e) {
            log.error("Query failed", e);
            return Result.error(500, "查询失败: " + e.getMessage());
        }
    }

    /**
     * GET /api/files/{id}
     * <p>
     * 按 ID 查询单条文件信息。
     */
    @GetMapping("/{id}")
    public Result<FileInfo> getById(@PathVariable String id) {
        return fileStorageService.findById(id)
                .map(Result::success)
                .orElse(Result.error(404, "文件不存在: " + id));
    }

    // ==================== 5. 在线预览接口（kkFileView 对接） ====================

    /**
     * GET /api/files/previewUrl?id=xxx
     * <p>
     * 生成 kkFileView 在线预览 URL。
     * 前端拿到该 URL 后 window.open() 即可在浏览器中预览文件。
     *
     * @param id 文件在 FileStorageService 中的 ID
     * @return kkFileView 完整预览 URL
     */
    @GetMapping("/previewUrl")
    public Result<String> previewUrl(@RequestParam("id") String id) {
        try {
            String previewUrl = previewService.generatePreviewUrl(id);
            return Result.success(previewUrl);
        } catch (IllegalArgumentException e) {
            return Result.error(404, e.getMessage());
        } catch (Exception e) {
            log.error("Generate preview URL failed, id={}", id, e);
            return Result.error(500, "生成预览链接失败: " + e.getMessage());
        }
    }

    /**
     * GET /api/files/previewFile?fileKey=xxx&uploadType=local&fullfilename=report.pdf
     * <p>
     * 供 kkFileView 服务器拉取文件流的端点。
     * 与 download 接口的区别：
     * <ul>
     *   <li>Content-Disposition 为 inline（浏览器内展示而非下载）</li>
     *   <li>Content-Type 根据文件后缀动态设置（kkFileView 依赖此信息识别文件类型）</li>
     *   <li>通过 fullfilename 参数确保 kkFileView 能正确识别无后缀的下载流</li>
     * </ul>
     */
    @GetMapping("/previewFile")
    public ResponseEntity<byte[]> previewFile(@RequestParam("fileKey") String fileKey,
                                              @RequestParam("uploadType") String uploadTypeCode,
                                              @RequestParam(value = "fullfilename", required = false) String fullFilename) {
        try {
            UploadType uploadType = UploadType.fromCode(uploadTypeCode);
            byte[] data = processor.download(uploadType, fileKey);
            if (data == null || data.length == 0) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            }

            // 确定原始文件名：优先用 fullfilename 参数，其次从存储中查找
            String originalFilename = resolveOriginalFilename(fileKey, uploadTypeCode, fullFilename);

            HttpHeaders headers = new HttpHeaders();
            // inline 展示（kkFileView 需要直接获取文件流进行转换）
            headers.setContentDisposition(
                    org.springframework.http.ContentDisposition.inline()
                            .filename(originalFilename, java.nio.charset.StandardCharsets.UTF_8)
                            .build()
            );
            headers.setContentType(resolveContentType(originalFilename));
            headers.setContentLength(data.length);

            return ResponseEntity.ok().headers(headers).body(data);
        } catch (Exception e) {
            log.error("File preview failed", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // ==================== 6. Mock 数据初始化接口 ====================

    /**
     * POST /api/files/mock/init
     * <p>
     * 重新生成内存中的 Mock 数据（默认 8 条）。
     * 可在启动后通过 curl/postman 触发，也可通过 application.yml 的 file.mock.enabled=false 禁用自动初始化。
     */
    @PostMapping("/mock/init")
    public Result<String> initMockData() {
        fileStorageService.clear();
        fileStorageService.initMockData();
        return Result.success("Mock 数据已重新初始化: " + fileStorageService.size() + " 条记录");
    }

    /**
     * 查看当前 Mock 数据总量。
     */
    @GetMapping("/mock/count")
    public Result<Integer> mockCount() {
        return Result.success(fileStorageService.size());
    }

    // ==================== 预览辅助方法 ====================

    /**
     * 解析原始文件名：优先使用 fullfilename 参数，否则从存储中查找
     */
    private String resolveOriginalFilename(String fileKey, String uploadTypeCode, String fullFilename) {
        if (fullFilename != null && !fullFilename.isEmpty()) {
            return fullFilename;
        }
        List<FileInfo> all = fileStorageService.findAll();
        for (FileInfo fi : all) {
            if (fi.getFileKey().equals(fileKey) && fi.getStorageType().equalsIgnoreCase(uploadTypeCode)) {
                return fi.getOriginalFilename();
            }
        }
        return fileKey;
    }

    /**
     * 根据文件后缀返回对应的 Content-Type
     */
    private MediaType resolveContentType(String filename) {
        if (filename == null) return MediaType.APPLICATION_OCTET_STREAM;
        String lower = filename.toLowerCase();
        if (lower.endsWith(".pdf"))  return MediaType.APPLICATION_PDF;
        if (lower.endsWith(".png"))  return MediaType.IMAGE_PNG;
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return MediaType.IMAGE_JPEG;
        if (lower.endsWith(".gif"))  return MediaType.IMAGE_GIF;
        if (lower.endsWith(".bmp"))  return MediaType.parseMediaType("image/bmp");
        if (lower.endsWith(".txt"))  return MediaType.TEXT_PLAIN;
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return MediaType.TEXT_HTML;
        if (lower.endsWith(".xml"))  return MediaType.APPLICATION_XML;
        if (lower.endsWith(".json")) return MediaType.APPLICATION_JSON;
        if (lower.endsWith(".doc"))  return MediaType.parseMediaType("application/msword");
        if (lower.endsWith(".docx")) return MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        if (lower.endsWith(".xls"))  return MediaType.parseMediaType("application/vnd.ms-excel");
        if (lower.endsWith(".xlsx")) return MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        if (lower.endsWith(".ppt"))  return MediaType.parseMediaType("application/vnd.ms-powerpoint");
        if (lower.endsWith(".pptx")) return MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.presentationml.presentation");
        if (lower.endsWith(".zip"))  return MediaType.parseMediaType("application/zip");
        if (lower.endsWith(".rar"))  return MediaType.parseMediaType("application/x-rar-compressed");
        if (lower.endsWith(".mp4"))  return MediaType.parseMediaType("video/mp4");
        if (lower.endsWith(".mp3"))  return MediaType.parseMediaType("audio/mpeg");
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}
