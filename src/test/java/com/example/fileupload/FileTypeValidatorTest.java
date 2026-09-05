package com.example.fileupload;

import com.example.fileupload.util.FileTypeValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FileTypeValidator 单元测试
 * 覆盖：后缀白名单、魔数匹配、非法文件拒绝
 */
class FileTypeValidatorTest {

    // ==================== getSuffix / getPureFilename ====================

    @Nested
    @DisplayName("后缀提取测试")
    class SuffixExtractionTests {

        @Test
        void should_extract_suffix_with_dot() {
            assertEquals(".jpg", FileTypeValidator.getSuffix("photo.jpg"));
            assertEquals(".PNG", FileTypeValidator.getSuffix("image.PNG"));
        }

        @Test
        void should_return_null_when_no_suffix() {
            assertNull(FileTypeValidator.getSuffix("noext"));
            assertNull(FileTypeValidator.getSuffix(null));
        }

        @Test
        void should_return_null_when_suffix_at_start_only() {
            assertNull(FileTypeValidator.getSuffix(".hidden"));
        }

        @Test
        void should_extract_pure_filename() {
            assertEquals("report_2024", FileTypeValidator.getPureFilename("report_2024.pdf"));
            assertEquals("banner", FileTypeValidator.getPureFilename("/path/to/banner.png"));
            assertEquals("noext", FileTypeValidator.getPureFilename("noext"));
        }
    }

    // ==================== 合法文件通过 ====================

    @Nested
    @DisplayName("合法文件校验 - 应该通过")
    class ValidFileTests {

        @Test
        void should_pass_png_file() throws IOException {
            byte[] pngHeader = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
            FileTypeValidator.validate("test.png", new ByteArrayInputStream(pngHeader));
            // 不抛异常即通过
        }

        @Test
        void should_pass_jpg_file() throws IOException {
            byte[] jpgHeader = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};
            FileTypeValidator.validate("photo.jpg", new ByteArrayInputStream(jpgHeader));
        }

        @Test
        void should_pass_gif_file() throws IOException {
            byte[] gifHeader = new byte[]{0x47, 0x49, 0x46, 0x38, 0x39, 0x61};
            FileTypeValidator.validate("anim.gif", new ByteArrayInputStream(gifHeader));
        }

        @Test
        void should_pass_pdf_file() throws IOException {
            byte[] pdfHeader = new byte[]{0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E, 0x34};
            FileTypeValidator.validate("doc.pdf", new ByteArrayInputStream(pdfHeader));
        }

        @Test
        void should_pass_txt_file_without_magic() throws IOException {
            // .txt 不在魔数映射中，应直接放行
            byte[] textData = "Hello World".getBytes();
            FileTypeValidator.validate("readme.txt", new ByteArrayInputStream(textData));
        }
    }

    // ==================== 非法文件拒绝 ====================

    @Nested
    @DisplayName("非法文件校验 - 应该被拒绝")
    class InvalidFileTests {

        @Test
        void should_fail_when_extension_not_in_whitelist() {
            byte[] data = new byte[]{0x00, 0x01};
            assertThrows(IllegalArgumentException.class, () ->
                    FileTypeValidator.validate("hack.exe", new ByteArrayInputStream(data)));
        }

        @Test
        void should_fail_when_file_has_no_extension() {
            byte[] data = new byte[]{0x00, 0x01};
            assertThrows(IllegalArgumentException.class, () ->
                    FileTypeValidator.validate("noextfile", new ByteArrayInputStream(data)));
        }

        @Test
        void should_fail_when_magic_number_mismatch_png_ext_but_jpg_content() {
            byte[] jpgContent = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                    FileTypeValidator.validate("fake.png", new ByteArrayInputStream(jpgContent)));
            assertTrue(ex.getMessage().contains("与实际内容不符"));
        }

        @Test
        void should_fail_when_file_too_small_for_magic_check() {
            byte[] singleByte = new byte[]{(byte) 0x89}; // 只有 1 字节
            assertThrows(IllegalArgumentException.class, () ->
                    FileTypeValidator.validate("bad.png", new ByteArrayInputStream(singleByte)));
        }
    }
}
