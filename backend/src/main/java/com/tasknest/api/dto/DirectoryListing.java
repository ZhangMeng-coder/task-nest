package com.tasknest.api.dto;

import java.util.List;

/**
 * 后端本地目录浏览结果。roots 是本机盘符 / 根目录，parent 为空表示已在根目录。
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
