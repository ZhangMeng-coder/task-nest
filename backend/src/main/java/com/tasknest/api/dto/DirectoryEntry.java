package com.tasknest.api.dto;

/**
 * 目录选择器中的一个文件夹。只暴露目录，不读取文件内容。
 */
public record DirectoryEntry(String name, String path, boolean hidden) {
}
