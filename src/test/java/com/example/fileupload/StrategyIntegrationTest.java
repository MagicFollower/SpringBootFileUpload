package com.example.fileupload;

import com.example.fileupload.enums.UploadType;
import com.example.fileupload.model.UploadResult;
import com.example.fileupload.strategy.FtpUploadStrategy;
import com.example.fileupload.strategy.LocalUploadStrategy;
import com.example.fileupload.strategy.OssUploadStrategy;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 三种策略实现的集成测试
 * 验证：上传 → 下载 → 删除 全链路
 */
@SpringBootTest
class StrategyIntegrationTest {

    @Autowired private LocalUploadStrategy localStrategy;
    @Autowired private FtpUploadStrategy ftpStrategy;
    @Autowired private OssUploadStrategy ossStrategy;

    // ==================== UploadType getter ====================

    @Test
    void should_return_correct_type() {
        assertEquals(UploadType.LOCAL, localStrategy.getUploadType());
        assertEquals(UploadType.FTP, ftpStrategy.getUploadType());
        assertEquals(UploadType.OSS, ossStrategy.getUploadType());
    }

    // ==================== Local 策略 ====================

    @Test
    @DisplayName("LOCAL: upload → download → delete")
    void local_strategy_lifecycle() throws IOException {
        MultipartFile file = createMockPngFile();

        UploadResult result = localStrategy.upload(file, "test.png");
        assertNotNull(result);
        assertNotNull(result.getFileKey());
        // 本地策略不再有 url，改为验证 fullPath 和 relativePath
        assertNotNull(result.getFullPath());
        assertNotNull(result.getRelativePath());
        assertNotNull(result.getMd5());
        assertTrue(result.getSize() > 0); // 文件大小应大于 0

        // 下载
        byte[] data = localStrategy.download(result.getFileKey());
        assertNotNull(data);
        assertTrue(data.length > 0);
        assertArrayEquals(getPngBytes(), data);

        // 删除
        boolean deleted = localStrategy.delete(result.getFileKey());
        assertTrue(deleted);

        // 删除后应返回 null
        assertNull(localStrategy.download(result.getFileKey()));
    }

    // ==================== FTP 策略（需要真实 FTP 服务器）====================
    // 以下测试需要实际运行的 FTP 服务器（配置在 application.yml 的 file.ftp.*）。
    // 本地开发环境默认关闭，需手动启用。

    @Test
    @Disabled("Requires real FTP server at file.ftp.host:port — enable manually for integration testing")
    @DisplayName("FTP: upload → download → delete")
    void ftp_strategy_lifecycle() throws IOException {
        MultipartFile file = createMockJpgFile();

        UploadResult result = ftpStrategy.upload(file, "photo.jpg");
        assertNotNull(result);
        assertEquals(UploadType.FTP.getCode(), result.getStorageType());

        // 下载
        byte[] data = ftpStrategy.download(result.getFileKey());
        assertNotNull(data);
        assertArrayEquals(getJpgBytes(), data);

        // 删除
        assertTrue(ftpStrategy.delete(result.getFileKey()));

        // 再下载应为 null
        assertNull(ftpStrategy.download(result.getFileKey()));
    }

    // ==================== OSS Mock 策略 ====================

    @Test
    @DisplayName("OSS-MOCK: upload → download → delete")
    void oss_strategy_lifecycle() throws IOException {
        MultipartFile file = createMockZipFile();

        int beforeSize = ossStrategy.getMockSize();

        UploadResult result = ossStrategy.upload(file, "archive.zip");
        assertNotNull(result);
        assertEquals(UploadType.OSS.getCode(), result.getStorageType());
        assertEquals(beforeSize + 1, ossStrategy.getMockSize());

        // 下载
        byte[] data = ossStrategy.download(result.getFileKey());
        assertNotNull(data);
        assertArrayEquals(getZipBytes(), data);

        // 删除
        assertTrue(ossStrategy.delete(result.getFileKey()));
        assertEquals(beforeSize, ossStrategy.getMockSize());

        assertNull(ossStrategy.download(result.getFileKey()));
    }

    // ==================== 错误场景 ====================

    @Test
    void local_should_handle_nonexistent_file_key() throws Exception {
        assertNull(localStrategy.download("nonexistent-file-xyz.png"));
        assertFalse(localStrategy.delete("nonexistent-file-xyz.png"));
    }

    @Test
    void strategies_should_handle_null_file_key() throws Exception {
        assertFalse(localStrategy.delete(null));
        assertFalse(ftpStrategy.delete(null));
        assertFalse(ossStrategy.delete(null));
    }

    // ==================== 辅助方法 ====================

    private MultipartFile createMockPngFile() throws IOException {
        byte[] content = getPngBytes();
        return new MockMultipartFile(
                "file",       // 字段名
                "test.png",   // 原始文件名
                "image/png",  // MIME 类型
                content       // 字节内容
        );
    }

    private MultipartFile createMockJpgFile() throws IOException {
        byte[] content = getJpgBytes();
        return new MockMultipartFile("file", "photo.jpg", "image/jpeg", content);
    }

    private MultipartFile createMockZipFile() throws IOException {
        byte[] content = getZipBytes();
        return new MockMultipartFile("file", "archive.zip", "application/zip", content);
    }

    private byte[] getPngBytes() {
        return new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00};
    }

    private byte[] getJpgBytes() {
        return new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10, 0x4A, 0x46};
    }

    private byte[] getZipBytes() {
        return new byte[]{0x50, 0x4B, 0x03, 0x04, 0x14, 0x00, 0x00, 0x00};
    }
}
