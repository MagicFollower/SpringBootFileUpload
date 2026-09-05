package com.example.fileupload.util;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * 文件类型校验工具
 * 提供后缀白名单检查 + 文件头魔数（Magic Number）匹配
 */
public class FileTypeValidator {

    private FileTypeValidator() {}

    /** 允许上传的后缀白名单 */
    private static final String[] ALLOWED_SUFFIXES = {
            ".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp",
            ".pdf",
            ".doc", ".docx",
            ".xls", ".xlsx",
            ".txt", ".csv",
            ".zip", ".rar", ".7z"
    };

    /** 常见文件类型的魔数映射（文件头前 N 字节） */
    private static final Map<String, byte[]> MAGIC_NUMBERS = new HashMap<>();

    static {
        MAGIC_NUMBERS.put("jpg", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});
        MAGIC_NUMBERS.put("jpeg", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});
        MAGIC_NUMBERS.put("png", new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47});
        MAGIC_NUMBERS.put("gif", new byte[]{0x47, 0x49, 0x46, 0x38});
        MAGIC_NUMBERS.put("bmp", new byte[]{0x42, 0x4D});
        MAGIC_NUMBERS.put("pdf", new byte[]{0x25, 0x50, 0x44, 0x46});
        // zip: PK, rar: Rar!, 7z: 7z\xBC\xAF\x27\x1C
        MAGIC_NUMBERS.put("zip", new byte[]{0x50, 0x4B, 0x03, 0x04});
        MAGIC_NUMBERS.put("rar", new byte[]{0x52, 0x61, 0x72, 0x21});
    }

    /**
     * 校验文件：检查后缀是否在白名单中，且魔数与后缀匹配
     *
     * @param originalFilename 原始文件名
     * @param inputStream      文件输入流（读取前 N 字节用于魔数校验）
     */
    public static void validate(String originalFilename, InputStream inputStream) throws IOException {
        String suffix = getSuffix(originalFilename);
        if (suffix == null || suffix.isEmpty()) {
            throw new IllegalArgumentException("文件必须包含合法后缀名");
        }

        // 1. 检查后缀白名单
        String lowerSuffix = suffix.toLowerCase();
        boolean allowed = false;
        for (String s : ALLOWED_SUFFIXES) {
            if (s.equalsIgnoreCase(lowerSuffix)) {
                allowed = true;
                break;
            }
        }
        if (!allowed) {
            throw new IllegalArgumentException("不允许上传的后缀: " + suffix + "，允许的 suffix: " + String.join(", ", ALLOWED_SUFFIXES));
        }

        // 2. 魔数校验（读取前 16 字节进行比对）
        byte[] header = new byte[16];
        int read = inputStream.read(header);
        if (read < 2) {
            throw new IllegalArgumentException("文件内容不完整，无法校验真实格式");
        }
        inputStream.reset(); // 调用方后续还要用此流，务必 reset

        String ext = lowerSuffix.substring(1).toLowerCase();
        byte[] expectedMagic = MAGIC_NUMBERS.get(ext);
        if (expectedMagic != null) {
            for (int i = 0; i < expectedMagic.length && i < read; i++) {
                if (header[i] != expectedMagic[i]) {
                    throw new IllegalArgumentException(
                            "文件后缀 [" + suffix + "] 与实际内容不符，请检查文件真实性");
                }
            }
        }
        // 未配置魔数的类型直接放行（如 .docx/.xlsx/.doc 等二进制复杂格式）
    }

    /**
     * 从文件名提取后缀（含 "."），如 ".jpg"；无后缀返回 null
     */
    public static String getSuffix(String filename) {
        if (filename == null) return null;
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex <= 0 || dotIndex == filename.length() - 1) return null;
        return filename.substring(dotIndex);
    }

    /**
     * 从文件名提取纯文件名（不含路径和后缀）
     */
    public static String getPureFilename(String filename) {
        if (filename == null) return null;
        // 先去掉路径
        int lastSlash = Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\'));
        String name = (lastSlash >= 0) ? filename.substring(lastSlash + 1) : filename;
        int dotIndex = name.lastIndexOf('.');
        return (dotIndex > 0) ? name.substring(0, dotIndex) : name;
    }
}
