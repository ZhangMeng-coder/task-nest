package com.tasknest.service;

import com.tasknest.api.dto.DirectoryEntry;
import com.tasknest.api.dto.DirectoryListing;
import com.tasknest.common.BusinessException;
import com.tasknest.common.ErrorCode;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 本地目录浏览：只列目录，不读取文件内容。用于页面上的工作目录选择器。
 */
@Service
public class DirectoryBrowserService {

    private static final int MAX_ENTRIES = 500;

    public DirectoryListing list(String requestedPath, boolean showHidden) {
        Path current = resolve(requestedPath);
        List<DirectoryEntry> directories = new ArrayList<>();
        boolean truncated = false;
        try (Stream<Path> stream = Files.list(current)) {
            List<Path> paths = stream
                    .filter(Files::isDirectory)
                    .sorted(Comparator.comparing(
                            path -> displayName(path), String.CASE_INSENSITIVE_ORDER))
                    .limit(MAX_ENTRIES + 1L)
                    .toList();
            if (paths.size() > MAX_ENTRIES) {
                truncated = true;
                paths = paths.subList(0, MAX_ENTRIES);
            }
            for (Path path : paths) {
                boolean hidden = isHidden(path);
                if (!hidden || showHidden) {
                    directories.add(new DirectoryEntry(displayName(path), path.toString(), hidden));
                }
            }
        } catch (IOException | SecurityException exception) {
            throw new BusinessException(ErrorCode.DIRECTORY_NOT_READABLE,
                    "无法读取目录: " + current);
        }
        Path parent = current.getParent();
        return new DirectoryListing(
                current.toString(),
                parent == null ? null : parent.toString(),
                displayName(current),
                roots(),
                directories,
                truncated);
    }

    private Path resolve(String requestedPath) {
        Path target;
        try {
            if (requestedPath == null || requestedPath.isBlank()) {
                String home = System.getProperty("user.home");
                target = Path.of(home);
            } else {
                target = Path.of(requestedPath.trim());
            }
            target = target.toAbsolutePath().normalize();
        } catch (InvalidPathException | SecurityException exception) {
            throw new BusinessException(ErrorCode.INVALID_WORKING_DIR,
                    "目录路径格式非法: " + requestedPath);
        }
        if (!Files.exists(target) || !Files.isDirectory(target)) {
            throw new BusinessException(ErrorCode.INVALID_WORKING_DIR,
                    "目录不存在或不是文件夹: " + target);
        }
        try {
            return target.toRealPath();
        } catch (IOException | SecurityException exception) {
            throw new BusinessException(ErrorCode.DIRECTORY_NOT_READABLE,
                    "目录不可访问: " + target);
        }
    }

    private List<String> roots() {
        File[] rootFiles = File.listRoots();
        if (rootFiles == null || rootFiles.length == 0) {
            return List.of("/");
        }
        return Arrays.stream(rootFiles)
                .map(File::getAbsolutePath)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    private String displayName(Path path) {
        Path fileName = path.getFileName();
        return fileName == null ? path.toString() : fileName.toString();
    }

    private boolean isHidden(Path path) {
        try {
            return Files.isHidden(path);
        } catch (IOException | SecurityException exception) {
            return false;
        }
    }
}
