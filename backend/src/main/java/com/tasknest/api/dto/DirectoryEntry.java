package com.tasknest.api.dto;

/**
 * 目录选择器中的一个文件夹，随 {@code GET /api/fs/directories} 返回。
 *
 * <p>只暴露目录，不读取文件内容。{@code name} 是展示名，{@code path} 是可再次请求的
 * 绝对路径，{@code hidden} 表示是否为隐藏目录（仅在 {@code showHidden=true} 时出现）。</p>
 */
public record DirectoryEntry(String name, String path, boolean hidden) {
}
