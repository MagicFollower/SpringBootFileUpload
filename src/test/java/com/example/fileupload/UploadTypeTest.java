package com.example.fileupload;

import com.example.fileupload.enums.UploadType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * UploadType 枚举单元测试
 */
class UploadTypeTest {

    @Test
    @DisplayName("should find enum by valid code")
    void should_find_by_code() {
        assertEquals(UploadType.LOCAL, UploadType.fromCode("local"));
        assertEquals(UploadType.FTP, UploadType.fromCode("ftp"));
        assertEquals(UploadType.OSS, UploadType.fromCode("oss"));
        // 大小写不敏感
        assertEquals(UploadType.LOCAL, UploadType.fromCode("LOCAL"));
        assertEquals(UploadType.FTP, UploadType.fromCode("Ftp"));
    }

    @Test
    @DisplayName("should throw when code does not match any enum")
    void should_throw_for_invalid_code() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> UploadType.fromCode("s3"));
        assertTrue(ex.getMessage().contains("不支持的上传类型"));
    }

    @Test
    @DisplayName("should return correct description")
    void should_have_description() {
        assertEquals("本地磁盘存储", UploadType.LOCAL.getDesc());
        assertEquals("FTP服务器", UploadType.FTP.getDesc());
        assertEquals("阿里云OSS", UploadType.OSS.getDesc());
    }
}
