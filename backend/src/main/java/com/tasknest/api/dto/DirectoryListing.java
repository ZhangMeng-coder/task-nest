package com.tasknest.api.dto;

import java.util.List;

/**
 * 后端本地目录浏览结果，由 {@code GET /api/fs/directories} 返回。
 *
 * <p>{@code path} 为规范化后的绝对真实路径，{@code parent} 为空表示已在根目录；
 * {@code roots} 是本机盘符 / 根目录，供选择器快速跳转。{@code directories} 最多 500 项，
 * 超出时 {@code truncated=true}。结果只含目录，不含文件。</p>
 */
public record DirectoryListing(
        String path,
        String parent,
        String name,
        List<String> roots,
        List<DirectoryEntry> directories,
        boolean truncated
) {
}
