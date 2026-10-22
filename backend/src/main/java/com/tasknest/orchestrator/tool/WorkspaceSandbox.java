package com.tasknest.orchestrator.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 工作目录沙箱：所有路径解析后必须位于仓库根目录内；敏感路径直接拒绝。
 * 这是进程内防护，不替代操作系统级隔离。
 *
 * <p>属于工具安全层，{@link ToolExecutor} 的每个文件路径都必须先经过这里。
 * 已存在路径用 toRealPath 解析符号链接后再校验；新建路径向上找到最近已存在的
 * 父目录做 realpath 校验，再拼接剩余部分。UNC / 设备路径、越界路径以及
 * .git / .ssh / .aws / .env / 私钥文件等敏感路径一律拒绝。
 */
public class WorkspaceSandbox {

    private final Path root;

    public WorkspaceSandbox(Path root) {
        try {
            // 根目录先解析为真实路径，后续 startsWith 比较才能识别符号链接逃逸。
            this.root = root.toRealPath();
        } catch (IOException exception) {
            throw new ToolException("PATH_OUT_OF_SANDBOX", "工作目录不可访问: " + root);
        }
    }

    public Path root() {
        return root;
    }

    /**
     * 解析已存在路径：拒绝不存在、越界（含符号链接逃逸）以及类型不符的路径。
     *
     * @param directory true 要求目录，false 要求普通文件
     */
    public Path resolveExisting(String rawPath, boolean directory) {
        Path candidate = resolve(rawPath, false);
        if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            throw new ToolException("FILE_NOT_FOUND", "路径不存在: " + display(rawPath));
        }
        // 字面路径可能经符号链接指向沙箱外，必须解析 realpath 后再比较一次。
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

    /**
     * 解析新建目标：最近已存在的父目录必须是真实路径且位于沙箱内。
     * 目标已存在时按真实路径校验；不存在的尾部允许后续创建父目录，但仍禁止敏感路径。
     */
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
        // 新建路径尚不存在：向上找到最近的已存在父目录，解析真实路径后再拼接剩余部分。
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

    /** 返回相对仓库根的 POSIX 风格路径（分隔符统一为 /），根目录返回 "."。 */
    public String relative(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (normalized.equals(root)) {
            return ".";
        }
        return root.relativize(normalized).toString().replace('\\', '/');
    }

    /**
     * 拒绝敏感路径：仓库根下的 .git / .ssh / .aws 目录、.env 系列（.env.example 例外）
     * 以及 .pem / .key / .p12 / id_rsa 等私钥文件。.env 与私钥按最后一段文件名判断，
     * 因此任意层级的同名文件都会被拦截。
     */
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
