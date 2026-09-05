package com.example.fileupload;

import com.example.fileupload.enums.UploadType;
import com.example.fileupload.model.FileInfo;
import com.example.fileupload.service.FileStorageService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Date;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FileStorageService 单元测试
 * 验证 Mock 数据存储的 CRUD + 搜索功能
 */
@SpringBootTest
class FileStorageServiceTest {

    @Autowired
    private FileStorageService fileStorageService;

    @BeforeEach
    void setUp() {
        // 每次测试前清空数据，保证独立性
        fileStorageService.clear();
    }

    // ==================== save / findById ====================

    @Test
    void should_save_and_find_by_id() {
        FileInfo info = buildSampleFileInfo("test.png", "png", "local", "/uploads/test.png");
        FileInfo saved = fileStorageService.save(info);

        assertNotNull(saved.getId());
        assertTrue(saved.getId().length() > 0);

        Optional<FileInfo> found = fileStorageService.findById(saved.getId());
        assertTrue(found.isPresent());
        assertEquals("test.png", found.get().getOriginalFilename());
    }

    // ==================== deleteById ====================

    @Test
    void should_delete_by_id() {
        FileInfo info = buildSampleFileInfo("del.png", "png", "local", "/uploads/del.png");
        fileStorageService.save(info);
        assertEquals(1, fileStorageService.size());

        boolean deleted = fileStorageService.deleteById(info.getId());
        assertTrue(deleted);
        assertEquals(0, fileStorageService.size());

        assertFalse(fileStorageService.findById(info.getId()).isPresent());
    }

    @Test
    void should_return_false_when_deleting_nonexistent() {
        assertFalse(fileStorageService.deleteById("non-existent-id"));
    }

    // ==================== findAll ====================

    @Test
    void should_find_all() {
        fileStorageService.save(buildSampleFileInfo("a.png", "png", "local", "/a.png"));
        fileStorageService.save(buildSampleFileInfo("b.jpg", "jpg", "ftp", "/b.jpg"));

        List<FileInfo> all = fileStorageService.findAll();
        assertEquals(2, all.size());
    }

    // ==================== searchByName ====================

    @Test
    void should_search_by_name_keyword() {
        fileStorageService.save(buildSampleFileInfo("report_2024.pdf", "pdf", "local", "/r.pdf"));
        fileStorageService.save(buildSampleFileInfo("banner.png", "png", "oss", "/b.png"));
        fileStorageService.save(buildSampleFileInfo("photo_vacation.jpg", "jpg", "local", "/p.jpg"));

        List<FileInfo> result = fileStorageService.searchByName("report");
        assertEquals(1, result.size());
        assertEquals("report_2024.pdf", result.get(0).getOriginalFilename());

        result = fileStorageService.searchByName("photo");
        assertEquals(1, result.size());
        assertEquals("photo_vacation.jpg", result.get(0).getOriginalFilename());
    }

    @Test
    void should_return_all_when_keyword_is_empty() {
        fileStorageService.save(buildSampleFileInfo("x.png", "png", "local", "/x.png"));
        List<FileInfo> result = fileStorageService.searchByName("");
        assertEquals(1, result.size());
    }

    // ==================== findByStorageType ====================

    @Test
    void should_filter_by_storage_type() {
        fileStorageService.save(buildSampleFileInfo("a.png", "png", "local", "/a.png"));
        fileStorageService.save(buildSampleFileInfo("b.jpg", "jpg", "ftp", "/b.jpg"));
        fileStorageService.save(buildSampleFileInfo("c.png", "png", "oss", "/c.png"));

        assertEquals(1, fileStorageService.findByStorageType("local").size());
        assertEquals(1, fileStorageService.findByStorageType("ftp").size());
        assertEquals(1, fileStorageService.findByStorageType("oss").size());
        assertEquals(0, fileStorageService.findByStorageType("s3").size());
    }

    // ==================== findBySuffix ====================

    @Test
    void should_filter_by_suffix() {
        fileStorageService.save(buildSampleFileInfo("a.png", "png", "local", "/a.png"));
        fileStorageService.save(buildSampleFileInfo("b.jpg", "jpg", "ftp", "/b.jpg"));
        fileStorageService.save(buildSampleFileInfo("c.gif", "gif", "local", "/c.gif"));

        assertEquals(1, fileStorageService.findBySuffix(".png").size());
        assertEquals(1, fileStorageService.findBySuffix(".jpg").size());
        assertEquals(0, fileStorageService.findBySuffix(".docx").size());
    }

    // ==================== clear ====================

    @Test
    void should_clear_all_data() {
        fileStorageService.save(buildSampleFileInfo("t.png", "png", "local", "/t.png"));
        fileStorageService.save(buildSampleFileInfo("t2.jpg", "jpg", "local", "/t2.jpg"));
        assertEquals(2, fileStorageService.size());

        fileStorageService.clear();
        assertEquals(0, fileStorageService.size());
        assertTrue(fileStorageService.findAll().isEmpty());
    }

    // ==================== 初始化 Mock 数据测试 ====================

    @Test
    void should_init_mock_data_at_startup() {
        // 注意：@PostConstruct 已在上下文加载时执行，但 @BeforeEach 的 clear() 会清除它。
        // 因此这里不依赖 clear()，而是重新初始化后验证。
        fileStorageService.initMockData();
        assertTrue(fileStorageService.size() >= 8);
    }

    // ==================== 辅助方法 ====================

    private FileInfo buildSampleFileInfo(String filename, String ext, String storageType, String fileKey) {
        FileInfo info = new FileInfo();
        info.setId(null); // 让 save() 自动生成
        info.setOriginalFilename(filename);
        info.setPureFilename(filename.substring(0, filename.lastIndexOf('.')));
        info.setSuffix("." + ext);
        info.setOriginalSize(1024L);
        info.setContentType("application/octet-stream");
        info.setStorageType(storageType);
        info.setFileKey(fileKey);
        // 仅本地存储模拟 fullPath，其余类型留空
        if ("local".equalsIgnoreCase(storageType)) {
            String sep = java.io.File.separator;
            info.setFullPath("C:" + sep + "file-upload-storage" + sep + fileKey.replace("/", sep));
            // relativePath 需要包含 base-path 目录名
            info.setRelativePath("file-upload-storage/" + fileKey);
        }
        info.setStoredSize(1024L);
        info.setMd5("test_md5_" + filename);
        info.setUploadedBy("test-user");
        info.setUploadTime(new Date());
        return info;
    }
}
