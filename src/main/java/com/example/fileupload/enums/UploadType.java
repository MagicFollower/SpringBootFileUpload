package com.example.fileupload.enums;

/**
 * 上传类型枚举，标识支持的存储方式
 */
public enum UploadType {

    LOCAL("local", "本地磁盘存储"),
    FTP("ftp", "FTP服务器"),
    OSS("oss", "阿里云OSS");

    private final String code;
    private final String desc;

    UploadType(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public String getCode() { return code; }
    public String getDesc() { return desc; }

    /**
     * 根据 code 查找枚举，找不到抛出异常
     */
    public static UploadType fromCode(String code) {
        for (UploadType type : values()) {
            if (type.code.equalsIgnoreCase(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("不支持的上传类型: " + code);
    }
}
