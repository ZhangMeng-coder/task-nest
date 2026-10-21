package com.tasknest.orchestrator.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 工作目录沙箱：所有路径解析后必须位于仓库根目录内；敏感路径直接拒绝。
 * 这是进程内防护，不替代操作系统级隔离。
 */
public class WorkspaceSandbox {

    private final Path root;

    public WorkspaceSandbox(Path root) {
        try {
            this.root = root.toRealPath();
        } catch (IOException exception) {
            throw new ToolException("PATH_OUT_OF_SANDBOX", "工作目录不可访问: " + root);
        }
    }

    public Path root() {
        return root;
    }

    public Path resolveExisting(String rawPath, boolean directory) {
        Path candidate = resolve(rawPath, false);
        if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            throw new ToolException("FILE_NOT_FOUND", "路径不存在: " + display(rawPath));
        }
        Path real;
        try {
            real = candidate.toRealPath();
        } catch (IOException exception) {
            throw new ToolException("FILE_NOT_FOUND", "路径不可访问: " + display(rawPath));
        }
        ensureInside(real, rawPath);
        if (directory && !Files.isDirectory(real)) {
            throw new ToolException("NOT_A_DIRECTORY", "不是目录: " + display(rawPath));
        }
        if (!directory && !Files.isRegularFile(real)) {
            throw new ToolException("NOT_A_FILE", "不是普通文件: " + display(rawPath));
        }
        return real;
    }

    /** 解析新建目标：最近已存在的父目录必须是真实路径且位于沙箱内。 */
    public Path resolveForWrite(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new ToolException("INVALID_ARGUMENT", "path 不能为空");
        }
        Path candidate = resolve(rawPath, true);
        ensureSensitive(relative(candidate));
        if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Path real = candidate.toRealPath();
                ensureInside(real, rawPath);
                ensureSensitive(relative(real));
                return real;
            } catch (IOException exception) {
                throw new ToolException("PATH_OUT_OF_SANDBOX", "路径不可访问: " + display(rawPath));
            }
        }
        Path parent = candidate.getParent();
        while (parent != null && !Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) {
            parent = parent.getParent();
        }
        if (parent == null) {
            throw new ToolException("PATH_OUT_OF_SANDBOX", "路径超出工作目录");
        }
        try {
            Path realParent = parent.toRealPath();
            ensureInside(realParent, rawPath);
            Path resolved = realParent.resolve(parent.relativize(candidate).toString());
            ensureInside(resolved.normalize(), rawPath);
            ensureSensitive(relative(resolved.normalize()));
            return resolved.normalize();
        } catch (IOException exception) {
            throw new ToolException("PATH_OUT_OF_SANDBOX", "无法解析路径: " + display(rawPath));
        }
    }

    private Path resolve(String rawPath, boolean forWrite) {
        String value = rawPath == null || rawPath.isBlank() ? "." : rawPath.trim();
        if (value.startsWith("\\\\") || value.startsWith("//")) {
            throw new ToolException("PATH_OUT_OF_SANDBOX", "不允许 UNC/设备路径");
        }
        Path candidate;
        try {
            Path given = Path.of(value);
            candidate = given.isAbsolute() ? given.normalize() : root.resolve(given).normalize();
        } catch (RuntimeException exception) {
            throw new ToolException("INVALID_ARGUMENT", "路径格式非法: " + value);
        }
        if (!candidate.startsWith(root)) {
            throw new ToolException("PATH_OUT_OF_SANDBOX", "路径超出工作目录: " + value);
        }
        return candidate;
    }

    private void ensureInside(Path path, String rawPath) {
        if (!path.startsWith(root)) {
            throw new ToolException("PATH_OUT_OF_SANDBOX", "路径超出工作目录: " + display(rawPath));
        }
    }

    public String relative(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (normalized.equals(root)) {
            return ".";
        }
        return root.relativize(normalized).toString().replace('\\', '/');
    }

    public void ensureSensitive(String relativePath) {
        String value = relativePath.replace('\\', '/');
        String lower = value.toLowerCase(Locale.ROOT);
        String name = lower.contains("/")
                ? lower.substring(lower.lastIndexOf('/') + 1)
                : lower;
        if (lower.equals(".git") || lower.startsWith(".git/")
                || lower.equals(".ssh") || lower.startsWith(".ssh/")
                || lower.equals(".aws") || lower.startsWith(".aws/")) {
            throw new ToolException("SENSITIVE_PATH", "拒绝访问敏感路径: " + value);
        }
        if (name.equals(".env") || (name.startsWith(".env.") && !name.equals(".env.example"))) {
            throw new ToolException("SENSITIVE_PATH", "拒绝访问敏感文件: " + value);
        }
        if (name.endsWith(".pem") || name.endsWith(".key") || name.endsWith(".p12")
                || name.equals("id_rsa") || name.startsWith("id_rsa.")) {
            throw new ToolException("SENSITIVE_PATH", "拒绝访问敏感文件: " + value);
        }
    }

    private String display(String rawPath) {
        return rawPath == null || rawPath.isBlank() ? "." : rawPath.trim();
    }
}
