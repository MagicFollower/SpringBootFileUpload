package com.example.fileupload;

import com.example.fileupload.enums.UploadType;
import com.example.fileupload.model.UploadResult;
import com.example.fileupload.service.FileStorageService;
import com.example.fileupload.strategy.FtpUploadStrategy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * FTP 完整冒烟测试
 * <p>
 * 需要 application.yml 中配置可用的 FTP 服务器（file.ftp.host/port/username/password）。
 * 覆盖：策略层 + REST API 层的单文件上传/下载、批量上传、删除、错误处理全链路。
 * <p>
 * 注意：当前 FTP 服务器若无删除权限，删除相关断言会自动降级为验证 "返回 false"。
 *
 * <h2>测试用例清单（10 个）</h2>
 * <table border="1">
 *   <tr><th>编号</th><th>层级</th><th>场景</th><th>测试内容</th></tr>
 *   <tr><td>#1</td><td>策略层</td><td>单文件上传</td><td>storageType / fileKey / url / size 全字段校验</td></tr>
 *   <tr><td>#2</td><td>策略层</td><td>下载</td><td>上传内容 vs 下载内容字节级比对</td></tr>
 *   <tr><td>#3</td><td>策略层</td><td>生命周期</td><td>上传 → 下载 → 尽力删除（兼容无权限）</td></tr>
 *   <tr><td>#4</td><td>策略层</td><td>批量上传</td><td>3 文件（JPG/PNG/BMP）复用 FTP 连接</td></tr>
 *   <tr><td>#5</td><td>策略层</td><td>批量 + 下载</td><td>批量上传后逐个下载，字节级比对</td></tr>
 *   <tr><td>#6</td><td>策略层</td><td>批量 + 删除</td><td>批量上传后逐个删除（尽力）</td></tr>
 *   <tr><td>#7</td><td>REST API</td><td>单文件全链路</td><td>上传 → 下载 → 按 ID 查询 → 按类型过滤</td></tr>
 *   <tr><td>#8</td><td>REST API</td><td>批量全链路</td><td>批量上传 3 文件 → 逐个下载 → 元数据验证</td></tr>
 *   <tr><td>#9</td><td>策略层</td><td>错误处理</td><td>null / 空串 / 不存在的 fileKey</td></tr>
 *   <tr><td>#10</td><td>REST API</td><td>错误处理</td><td>非法后缀 .exe 拒绝 + 未知类型拒绝</td></tr>
 * </table>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FtpSmokeTest {

    /** 合法 PNG 文件头（8 字节） */
    private static final byte[] PNG_BYTES = {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    };

    /** 合法 JPG 文件头（4 字节） */
    private static final byte[] JPG_BYTES = {
            (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0
    };

    /** 合法 BMP 文件头（2 字节） */
    private static final byte[] BMP_BYTES = {
            (byte) 0x42, (byte) 0x4D
    };

    @Autowired private FtpUploadStrategy ftpStrategy;
    @Autowired private FileStorageService fileStorageService;
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    /** 记录每个测试上传的 fileKey，用于 @AfterEach 尽力清理 FTP 服务器 */
    private final List<String> uploadedFileKeys = new ArrayList<>();

    @BeforeEach
    void setUp() {
        fileStorageService.clear();
        uploadedFileKeys.clear();
    }

    @AfterEach
    void tearDown() {
        for (String key : uploadedFileKeys) {
            try {
                ftpStrategy.delete(key);
            } catch (Exception ignored) {
            }
        }
    }

    // ============================================================
    // Part A: 策略层 — 单文件上传 & 下载
    // ============================================================

    @Test
    @Order(1)
    @DisplayName("[策略层] 单文件上传：返回值完整校验")
    void strategy_single_upload_should_return_valid_result() throws IOException {
        MockMultipartFile file = new MockMultipartFile(
                "file", "smoke_single.png", "image/png", PNG_BYTES);

        UploadResult result = ftpStrategy.upload(file, "smoke_single.png");

        assertNotNull(result, "上传结果不应为 null");
        assertEquals(UploadType.FTP.getCode(), result.getStorageType(), "storageType 应为 ftp");
        assertNotNull(result.getFileKey(), "fileKey 不应为 null");
        assertTrue(result.getFileKey().contains("smoke_single.png"), "fileKey 应包含原始文件名");
        assertNotNull(result.getUrl(), "FTP url 不应为 null");
        assertTrue(result.getUrl().startsWith("ftp://"), "url 应以 ftp:// 开头");
        assertEquals(PNG_BYTES.length, result.getSize(), "文件大小应匹配");

        uploadedFileKeys.add(result.getFileKey());
    }

    @Test
    @Order(2)
    @DisplayName("[策略层] 下载：内容完整性校验（字节级比对）")
    void strategy_download_should_return_identical_content() throws IOException {
        MockMultipartFile file = new MockMultipartFile(
                "file", "smoke_dl.png", "image/png", PNG_BYTES);
        UploadResult result = ftpStrategy.upload(file, "smoke_dl.png");
        uploadedFileKeys.add(result.getFileKey());

        byte[] downloaded = ftpStrategy.download(result.getFileKey());

        assertNotNull(downloaded, "下载结果不应为 null");
        assertEquals(PNG_BYTES.length, downloaded.length, "下载文件大小应匹配");
        assertArrayEquals(PNG_BYTES, downloaded, "下载内容应与上传内容完全一致");
    }

    @Test
    @Order(3)
    @DisplayName("[策略层] 上传 → 下载 → 删除（尽力删除，兼容无权限场景）")
    void strategy_upload_download_delete_lifecycle() throws IOException {
        MockMultipartFile file = new MockMultipartFile(
                "file", "smoke_lifecycle.png", "image/png", PNG_BYTES);
        UploadResult result = ftpStrategy.upload(file, "smoke_lifecycle.png");

        // 确认存在
        byte[] before = ftpStrategy.download(result.getFileKey());
        assertNotNull(before, "上传后应能下载");
        assertArrayEquals(PNG_BYTES, before);

        // 尝试删除（FTP 服务器可能无权限，返回 false 即可）
        boolean deleted = ftpStrategy.delete(result.getFileKey());
        if (deleted) {
            // 删除成功 → 验证不可下载
            byte[] after = ftpStrategy.download(result.getFileKey());
            assertNull(after, "删除成功后下载应返回 null");
        } else {
            // 删除失败 → 仅记录警告，不视为测试失败
            System.err.println("[WARN] FTP 服务器无删除权限，跳过删除后校验");
            uploadedFileKeys.add(result.getFileKey()); // 留给 @AfterEach 清理
        }
    }

    // ============================================================
    // Part B: 策略层 — 批量上传
    // ============================================================

    @Test
    @Order(4)
    @DisplayName("[策略层] 批量上传 3 文件（FTP 连接复用）")
    void strategy_batch_upload_should_succeed_for_all_files() throws IOException {
        List<MockMultipartFile> files = new ArrayList<>();
        files.add(new MockMultipartFile("files", "batch_a.jpg", "image/jpeg", JPG_BYTES));
        files.add(new MockMultipartFile("files", "batch_b.png", "image/png", PNG_BYTES));
        files.add(new MockMultipartFile("files", "batch_c.bmp", "image/bmp", BMP_BYTES));

        List<UploadResult> results = ftpStrategy.batchUpload(new ArrayList<>(files));

        assertEquals(3, results.size(), "应返回 3 个结果");
        for (int i = 0; i < results.size(); i++) {
            UploadResult r = results.get(i);
            assertEquals(UploadType.FTP.getCode(), r.getStorageType());
            assertNotNull(r.getFileKey());
            assertTrue(r.getSize() > 0, "文件大小应大于 0");
            uploadedFileKeys.add(r.getFileKey());
        }
    }

    @Test
    @Order(5)
    @DisplayName("[策略层] 批量上传后逐个下载：内容完整性校验")
    void strategy_batch_upload_then_download_should_match() throws IOException {
        // 构造带真实魔数的扩展内容
        byte[] contentA = "EXTRA_JPG_DATA".getBytes();
        byte[] jpgFull = new byte[JPG_BYTES.length + contentA.length];
        System.arraycopy(JPG_BYTES, 0, jpgFull, 0, JPG_BYTES.length);
        System.arraycopy(contentA, 0, jpgFull, JPG_BYTES.length, contentA.length);

        byte[] contentB = "EXTRA_PNG_DATA".getBytes();
        byte[] pngFull = new byte[PNG_BYTES.length + contentB.length];
        System.arraycopy(PNG_BYTES, 0, pngFull, 0, PNG_BYTES.length);
        System.arraycopy(contentB, 0, pngFull, PNG_BYTES.length, contentB.length);

        List<MockMultipartFile> files = new ArrayList<>();
        files.add(new MockMultipartFile("files", "dl_a.jpg", "image/jpeg", jpgFull));
        files.add(new MockMultipartFile("files", "dl_b.png", "image/png", pngFull));

        List<UploadResult> results = ftpStrategy.batchUpload(new ArrayList<>(files));
        assertEquals(2, results.size());
        for (UploadResult r : results) uploadedFileKeys.add(r.getFileKey());

        // 逐个下载并字节级比对
        byte[] downA = ftpStrategy.download(results.get(0).getFileKey());
        assertArrayEquals(jpgFull, downA, "第 1 个文件内容应一致");

        byte[] downB = ftpStrategy.download(results.get(1).getFileKey());
        assertArrayEquals(pngFull, downB, "第 2 个文件内容应一致");
    }

    @Test
    @Order(6)
    @DisplayName("[策略层] 批量上传后逐个删除（尽力删除）")
    void strategy_batch_upload_then_delete_all() throws IOException {
        List<MockMultipartFile> files = new ArrayList<>();
        files.add(new MockMultipartFile("files", "del_a.jpg", "image/jpeg", JPG_BYTES));
        files.add(new MockMultipartFile("files", "del_b.png", "image/png", PNG_BYTES));

        List<UploadResult> results = ftpStrategy.batchUpload(new ArrayList<>(files));
        assertEquals(2, results.size());

        int deleteSuccessCount = 0;
        for (UploadResult r : results) {
            if (ftpStrategy.delete(r.getFileKey())) {
                deleteSuccessCount++;
                assertNull(ftpStrategy.download(r.getFileKey()), "删除后应不可下载");
            }
        }

        if (deleteSuccessCount == 0) {
            System.err.println("[WARN] FTP 服务器无删除权限，批量删除全部返回 false");
            for (UploadResult r : results) uploadedFileKeys.add(r.getFileKey());
        } else {
            assertEquals(2, deleteSuccessCount, "若有权删除，应全部成功");
        }
    }

    // ============================================================
    // Part C: REST API 层 — 单文件完整生命周期
    // ============================================================

    @Test
    @Order(7)
    @DisplayName("[REST API] FTP 单文件上传 → 下载 → 元数据查询")
    void api_single_ftp_upload_download_query() throws Exception {
        // 1. 上传
        MockMultipartFile file = new MockMultipartFile(
                "file", "api_single.jpg", "image/jpeg", JPG_BYTES);

        MvcResult uploadResult = mockMvc.perform(multipart("/api/files/upload")
                        .file(file)
                        .param("uploadType", "ftp")
                        .param("uploadedBy", "smoke-tester"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.storageType").value("ftp"))
                .andExpect(jsonPath("$.data.originalFilename").value("api_single.jpg"))
                .andExpect(jsonPath("$.data.uploadedBy").value("smoke-tester"))
                .andExpect(jsonPath("$.data.fileKey").isNotEmpty())
                .andReturn();

        String fileKey = readJson(uploadResult, "data.fileKey");
        String id = readJson(uploadResult, "data.id");
        uploadedFileKeys.add(fileKey);

        // 2. 下载 — 验证 Content-Disposition 和 HTTP 200
        mockMvc.perform(get("/api/files/download")
                        .param("fileKey", fileKey)
                        .param("uploadType", "ftp"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("api_single.jpg")));

        // 3. 按 ID 查询元数据
        mockMvc.perform(get("/api/files/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(id))
                .andExpect(jsonPath("$.data.storageType").value("ftp"))
                .andExpect(jsonPath("$.data.originalFilename").value("api_single.jpg"));

        // 4. 按 FTP 存储类型过滤查询
        mockMvc.perform(get("/api/files").param("storageType", "ftp"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));
    }

    // ============================================================
    // Part D: REST API 层 — 批量上传
    // ============================================================

    @Test
    @Order(8)
    @DisplayName("[REST API] FTP 批量上传 3 文件 → 逐个下载校验")
    void api_batch_upload_then_download_all() throws Exception {
        // 1. 批量上传
        MockMultipartFile f1 = new MockMultipartFile("files", "api_b1.jpg", "image/jpeg", JPG_BYTES);
        MockMultipartFile f2 = new MockMultipartFile("files", "api_b2.png", "image/png", PNG_BYTES);
        MockMultipartFile f3 = new MockMultipartFile("files", "api_b3.bmp", "image/bmp", BMP_BYTES);

        MvcResult uploadResult = mockMvc.perform(multipart("/api/files/upload/batch")
                        .file(f1).file(f2).file(f3)
                        .param("uploadType", "ftp"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andReturn();

        String responseBody = uploadResult.getResponse().getContentAsString();

        // 提取每个文件的 fileKey
        List<String> fileKeys = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            fileKeys.add(objectMapper.readTree(responseBody)
                    .get("data").get(i).get("fileKey").asText());
        }
        uploadedFileKeys.addAll(fileKeys);

        // 2. 逐个下载并验证 Content-Disposition
        String[] expectedNames = {"api_b1.jpg", "api_b2.png", "api_b3.bmp"};
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/api/files/download")
                            .param("fileKey", fileKeys.get(i))
                            .param("uploadType", "ftp"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Disposition", containsString(expectedNames[i])));
        }

        // 3. 验证元数据已存储（查询列表应能看到 3 条 FTP 记录）
        mockMvc.perform(get("/api/files").param("storageType", "ftp"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(3));
    }

    // ============================================================
    // Part E: 错误处理 & 边界场景
    // ============================================================

    @Test
    @Order(9)
    @DisplayName("[策略层] 错误处理：null / 空字符串 / 不存在的 fileKey")
    void strategy_error_handling_for_invalid_file_keys() throws IOException {
        // null fileKey
        assertNull(ftpStrategy.download(null), "download(null) 应返回 null");
        assertFalse(ftpStrategy.delete(null), "delete(null) 应返回 false");

        // 空字符串
        assertNull(ftpStrategy.download(""), "download('') 应返回 null");
        assertFalse(ftpStrategy.delete(""), "delete('') 应返回 false");

        // 不存在的文件（FTP 返回 550，策略应返回 false 而非抛异常）
        assertNull(ftpStrategy.download("nonexistent_smoke_test_file.png"),
                "不存在的文件下载应返回 null");
        assertFalse(ftpStrategy.delete("nonexistent_smoke_test_file.png"),
                "不存在的文件删除应返回 false");
    }

    @Test
    @Order(10)
    @DisplayName("[REST API] 错误处理：非法后缀被拒绝 / 未知上传类型被拒绝")
    void api_error_handling_for_invalid_requests() throws Exception {
        // 非法后缀 .exe
        MockMultipartFile exe = new MockMultipartFile(
                "file", "hack.exe", "application/x-dosexec", new byte[10]);
        mockMvc.perform(multipart("/api/files/upload")
                        .file(exe)
                        .param("uploadType", "ftp"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        // 未知上传类型
        MockMultipartFile png = new MockMultipartFile(
                "file", "t.png", "image/png", PNG_BYTES);
        mockMvc.perform(multipart("/api/files/upload")
                        .file(png)
                        .param("uploadType", "unknown"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    // ============================================================
    // 辅助方法
    // ============================================================

    /**
     * 从 MockMvc 响应的 JSON 中按路径读取字符串值
     */
    private String readJson(MvcResult result, String jsonPath) throws Exception {
        String body = result.getResponse().getContentAsString();
        com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(body);
        for (String segment : jsonPath.split("\\.")) {
            node = node.get(segment);
        }
        return node.asText();
    }
}
