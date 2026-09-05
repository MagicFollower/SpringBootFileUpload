package com.example.fileupload;

import com.example.fileupload.service.FileStorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * FileController 集成测试
 * 覆盖所有 REST API 端点：上传、下载、删除、查询、Mock 数据
 */
@SpringBootTest
@AutoConfigureMockMvc
class FileControllerIntegrationTest {

    private static final byte[] PNG_BYTES = {(byte)0x89, (byte)0x50, (byte)0x4E, (byte)0x47, (byte)0x0D, (byte)0x0A, (byte)0x1A, (byte)0x0A};
    private static final byte[] JPG_BYTES = {(byte)0xFF, (byte)0xD8, (byte)0xFF, (byte)0xE0};
    private static final byte[] ZIP_BYTES = {(byte)0x50, (byte)0x4B, (byte)0x03, (byte)0x04};
    private static final byte[] BMP_BYTES = {(byte)0x42, (byte)0x4D};

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private FileStorageService fileStorageService;

    @BeforeEach
    void setUp() {
        fileStorageService.clear();
    }

    // ==================== 1. 上传接口测试 ====================

    @Test
    @DisplayName("UPLOAD: 成功上传本地文件")
    void should_upload_file_successfully_local() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "test.png", "image/png", PNG_BYTES);

        mockMvc.perform(multipart("/api/files/upload")
                        .file(file)
                        .param("uploadType", "local"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.originalFilename").value("test.png"))
                .andExpect(jsonPath("$.data.storageType").value("local"))
                .andExpect(jsonPath("$.data.fileKey").isNotEmpty());
    }

    @Test
    @Disabled("需要真实 FTP 服务器，单文件上传现在走真实连接")
    @DisplayName("UPLOAD: 成功上传到 FTP Mock")
    void should_upload_to_ftp_mock() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "photo.jpg", "image/jpeg", JPG_BYTES);

        mockMvc.perform(multipart("/api/files/upload")
                        .file(file)
                        .param("uploadType", "ftp"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.storageType").value("ftp"));
    }

    @Test
    @DisplayName("UPLOAD: 成功上传到 OSS Mock")
    void should_upload_to_oss_mock() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "archive.zip", "application/zip", ZIP_BYTES);

        mockMvc.perform(multipart("/api/files/upload")
                        .file(file)
                        .param("uploadType", "oss"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.storageType").value("oss"));
    }

    @Test
    @DisplayName("UPLOAD: 非法后缀被拒绝")
    void should_reject_unauthorized_extension() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "malware.exe", "application/x-dosexec", new byte[10]);

        mockMvc.perform(multipart("/api/files/upload")
                        .file(file)
                        .param("uploadType", "local"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("UPLOAD: 魔数不匹配被拒绝")
    void should_reject_magic_number_mismatch() throws Exception {
        // .png 后缀但内容是 jpg 魔数
        MockMultipartFile file = new MockMultipartFile("file", "fake.png", "image/png", JPG_BYTES);

        mockMvc.perform(multipart("/api/files/upload")
                        .file(file)
                        .param("uploadType", "local"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("UPLOAD: 未知上传类型被拒绝")
    void should_reject_unknown_upload_type() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "t.png", "image/png", PNG_BYTES);

        mockMvc.perform(multipart("/api/files/upload")
                        .file(file)
                        .param("uploadType", "s3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    // ==================== 2. 下载接口测试 ====================

    @Test
    @DisplayName("DOWNLOAD: 从本地存储下载文件")
    void should_download_local_file() throws Exception {
        // Step 1: 上传获取 fileKey
        MockMultipartFile uploadFile = new MockMultipartFile("file", "dl_test.png", "image/png", PNG_BYTES);
        MvcResult uploadResult = mockMvc.perform(multipart("/api/files/upload")
                        .file(uploadFile)
                        .param("uploadType", "local"))
                .andExpect(status().isOk())
                .andReturn();

        String fileKey = objectMapper.readTree(uploadResult.getResponse().getContentAsString())
                .get("data").get("fileKey").asText();

        // Step 2: 下载并验证 Content-Disposition header
        mockMvc.perform(get("/api/files/download")
                        .param("fileKey", fileKey)
                        .param("uploadType", "local"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("dl_test.png")));
    }

    @Test
    @DisplayName("DOWNLOAD: 不存在的文件返回 404")
    void should_return_404_for_nonexistent_file() throws Exception {
        mockMvc.perform(get("/api/files/download")
                        .param("fileKey", "nonexistent.png")
                        .param("uploadType", "local"))
                .andExpect(status().isNotFound());
    }

    // ==================== 3. 删除接口测试 ====================

    @Test
    @DisplayName("DELETE: 按 ID 删除文件")
    void should_delete_file_by_id() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "del_test.png", "image/png", PNG_BYTES);
        MvcResult uploadResult = mockMvc.perform(multipart("/api/files/upload")
                        .file(file)
                        .param("uploadType", "local"))
                .andExpect(status().isOk())
                .andReturn();

        String id = objectMapper.readTree(uploadResult.getResponse().getContentAsString())
                .get("data").get("id").asText();

        assertEquals(1, fileStorageService.size());

        mockMvc.perform(delete("/api/files/" + id)
                        .param("uploadType", "local"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        assertEquals(0, fileStorageService.size());
    }

    @Test
    @DisplayName("DELETE: 删除不存在的文件返回 404")
    void should_return_404_when_deleting_nonexistent() throws Exception {
        mockMvc.perform(delete("/api/files/nonexistent-id")
                        .param("uploadType", "local"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));
    }

    // ==================== 4. 查询接口测试 ====================

    @Test
    @DisplayName("QUERY: 列出所有文件")
    void should_list_all_files() throws Exception {
        byte[] c1 = PNG_BYTES.clone();
        byte[] c2 = JPG_BYTES.clone();
        mockMvc.perform(multipart("/api/files/upload").file(new MockMultipartFile("file", "a.png", "image/png", c1)).param("uploadType", "local"));
        mockMvc.perform(multipart("/api/files/upload").file(new MockMultipartFile("file", "b.jpg", "image/jpeg", c2)).param("uploadType", "local"));

        mockMvc.perform(get("/api/files"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(2));
    }

    @Test
    @DisplayName("QUERY: 按关键词搜索文件名")
    void should_search_by_keyword() throws Exception {
        byte[] c = PNG_BYTES.clone();
        mockMvc.perform(multipart("/api/files/upload").file(new MockMultipartFile("file", "banner_logo.png", "image/png", c)).param("uploadType", "local"));
        mockMvc.perform(multipart("/api/files/upload").file(new MockMultipartFile("file", "other.txt", "text/plain", "test".getBytes())).param("uploadType", "local"));

        mockMvc.perform(get("/api/files").param("keyword", "banner"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].originalFilename").value("banner_logo.png"));
    }

    @Test
    @DisplayName("QUERY: 按存储类型过滤")
    void should_filter_by_storage_type() throws Exception {
        byte[] c = PNG_BYTES.clone();
        mockMvc.perform(multipart("/api/files/upload").file(new MockMultipartFile("file", "local.png", "image/png", c)).param("uploadType", "local"));
        mockMvc.perform(multipart("/api/files/upload").file(new MockMultipartFile("file", "oss.png", "image/png", c)).param("uploadType", "oss"));

        mockMvc.perform(get("/api/files").param("storageType", "local"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));

        mockMvc.perform(get("/api/files").param("storageType", "oss"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));
    }

    @Test
    @DisplayName("QUERY: 按后缀过滤")
    void should_filter_by_suffix() throws Exception {
        mockMvc.perform(multipart("/api/files/upload").file(new MockMultipartFile("file", "a.png", "image/png", PNG_BYTES.clone())).param("uploadType", "local"));
        mockMvc.perform(multipart("/api/files/upload").file(new MockMultipartFile("file", "b.jpg", "image/jpeg", JPG_BYTES.clone())).param("uploadType", "local"));

        mockMvc.perform(get("/api/files").param("suffix", ".png"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));
    }

    @Test
    @DisplayName("QUERY: 按 ID 查询单条")
    void should_get_by_id() throws Exception {
        MvcResult result = mockMvc.perform(multipart("/api/files/upload")
                        .file(new MockMultipartFile("file", "single.png", "image/png", PNG_BYTES.clone()))
                        .param("uploadType", "local"))
                .andExpect(status().isOk())
                .andReturn();

        String id = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("data").get("id").asText();

        mockMvc.perform(get("/api/files/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(id));
    }

    // ==================== 5. Mock 数据接口测试 ====================

    @Test
    @DisplayName("MOCK: 初始化后应有默认数量的记录")
    void should_init_mock_data_and_count() throws Exception {
        mockMvc.perform(post("/api/files/mock/init"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/api/files/mock/count"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(8));
    }
}
