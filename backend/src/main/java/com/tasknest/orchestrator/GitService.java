package com.tasknest.orchestrator;

import com.tasknest.common.BusinessException;
import com.tasknest.common.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 本地 Git 版本边界。只执行本地分支/提交/合并/回滚，不访问远端。
 * 所有命令显式指定 -C 与 ref，不依赖进程当前目录。
 */
@Service
public class GitService {

    private static final Logger log = LoggerFactory.getLogger(GitService.class);
    private static final String INTERNAL_NAME = "TaskNest";
    private static final String INTERNAL_EMAIL = "tasknest@local";
    private static final long COMMAND_TIMEOUT_SECONDS = 120;

    /** 校验通过后的仓库信息：规范化根目录、当前分支与 HEAD 提交。 */
    public record RepoInfo(Path root, String branch, String head) {
    }

    /**
     * 校验工作目录是可用的 Git 仓库根目录，且工作区干净。
     * 路径会规范化为绝对真实路径；指向子目录、detached HEAD、脏工作区或非仓库时分别抛出
     * 对应的 Git 错误码，调用方据此在创建运行前拒绝请求。
     */
    public RepoInfo validate(Path workingDir) {
        if (workingDir == null) {
            throw new BusinessException(ErrorCode.GIT_NOT_REPOSITORY, "必须选择工作目录");
        }
        Path real;
        try {
            real = workingDir.toRealPath();
        } catch (IOException | SecurityException exception) {
            throw new BusinessException(ErrorCode.INVALID_WORKING_DIR, "工作目录不存在或不可访问");
        }
        if (!Files.isDirectory(real)) {
            throw new BusinessException(ErrorCode.INVALID_WORKING_DIR, "工作目录不是文件夹");
        }
        ExecResult topLevel = run(real, "rev-parse", "--show-toplevel");
        if (topLevel.exitCode() != 0) {
            throw new BusinessException(ErrorCode.GIT_NOT_REPOSITORY,
                    "工作目录不是 Git 仓库: " + topLevel.stderrSummary());
        }
        Path root;
        try {
            root = Path.of(topLevel.stdout().trim()).toRealPath();
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.GIT_NOT_REPOSITORY, "无法解析 Git 仓库根目录");
        }
        if (!root.equals(real)) {
            throw new BusinessException(ErrorCode.GIT_NOT_REPO_ROOT,
                    "工作目录必须是 Git 仓库根目录: " + root);
        }
        String branch = currentBranch(root);
        if (branch == null) {
            throw new BusinessException(ErrorCode.GIT_DETACHED_HEAD, "当前处于 detached HEAD");
        }
        if (!statusClean(root)) {
            throw new BusinessException(ErrorCode.GIT_DIRTY_WORKTREE,
                    "Git 工作区存在未提交或未跟踪改动，请先提交或清理");
        }
        String head = headSha(root, "HEAD");
        return new RepoInfo(root, branch, head);
    }

    /** 探测本机 Git 是否可用：当前目录可 rev-parse，或能执行 git --version。 */
    public boolean isAvailable() {
        try {
            return run(Path.of(".").toAbsolutePath(), "rev-parse", "--git-dir").exitCode() == 0
                    || commandExists();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private boolean commandExists() {
        try {
            Process process = new ProcessBuilder("git", "--version")
                    .redirectErrorStream(true)
                    .start();
            return process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    /** 返回当前分支短名；detached HEAD 或读取失败时返回 null。 */
    public String currentBranch(Path root) {
        ExecResult result = run(root, "symbolic-ref", "--short", "-q", "HEAD");
        if (result.exitCode() != 0) {
            return null;
        }
        String branch = result.stdout().trim();
        return branch.isEmpty() ? null : branch;
    }

    /** 解析 ref 的完整提交 SHA；ref 不存在或命令失败时返回 null。 */
    public String headSha(Path root, String ref) {
        ExecResult result = run(root, "rev-parse", ref);
        return result.exitCode() == 0 ? result.stdout().trim() : null;
    }

    /** 解析 ref 的短提交 SHA；失败时返回 null。 */
    public String shortSha(Path root, String ref) {
        ExecResult result = run(root, "rev-parse", "--short", ref);
        return result.exitCode() == 0 ? result.stdout().trim() : null;
    }

    /** 工作区是否没有已暂存、未暂存或未跟踪改动。 */
    public boolean statusClean(Path root) {
        return statusLines(root).isEmpty();
    }

    /** 返回 git status --porcelain 的非空行。 */
    public List<String> statusLines(Path root) {
        ExecResult result = run(root, "status", "--porcelain");
        if (result.exitCode() != 0) {
            throw new BusinessException(ErrorCode.GIT_NOT_REPOSITORY,
                    "无法读取 Git 状态: " + result.stderrSummary());
        }
        List<String> lines = new ArrayList<>();
        for (String line : result.stdout().split("\\R")) {
            if (!line.isBlank()) {
                lines.add(line);
            }
        }
        return lines;
    }

    public String diffStat(Path root, String fromRef, String toRef) {
        ExecResult result = run(root, "diff", "--stat", fromRef, toRef);
        return result.exitCode() == 0 ? result.stdout().trim() : "";
    }

    /** 切换到已存在的分支；失败按分支漂移（{@code GIT_BRANCH_DRIFT}）处理。 */
    public void checkout(Path root, String branch) {
        ExecResult result = run(root, "checkout", branch);
        if (result.exitCode() != 0) {
            throw new BusinessException(ErrorCode.GIT_BRANCH_DRIFT,
                    "无法切换到分支 " + branch + ": " + result.stderrSummary());
        }
    }

    /** 以 startPoint 为起点创建并检出 branch；失败抛 {@code GIT_BRANCH_CREATE_FAILED}。 */
    public void createAndCheckout(Path root, String branch, String startPoint) {
        ExecResult result = run(root, "checkout", "-b", branch, startPoint);
        if (result.exitCode() != 0) {
            throw new BusinessException(ErrorCode.GIT_BRANCH_CREATE_FAILED,
                    "创建分支 " + branch + " 失败: " + result.stderrSummary());
        }
    }

    /**
     * 把工作区全部改动提交到当前分支（git add -A，跳过 hooks，使用内置身份）；
     * 没有改动时返回 null。
     */
    public String commitAll(Path root, String message) {
        ExecResult add = run(root, "add", "-A");
        if (add.exitCode() != 0) {
            throw new BusinessException(ErrorCode.GIT_COMMIT_FAILED,
                    "git add 失败: " + add.stderrSummary());
        }
        if (statusClean(root)) {
            return null;
        }
        ExecResult commit = runWithIdentity(root,
                "commit", "--no-verify", "-m", message);
        if (commit.exitCode() != 0) {
            throw new BusinessException(ErrorCode.GIT_COMMIT_FAILED,
                    "git commit 失败: " + commit.stderrSummary());
        }
        return shortSha(root, "HEAD");
    }

    /** 切到 targetBranch 后执行 --no-ff 合并；冲突时中止并抛出 GIT_MERGE_CONFLICT。 */
    public void mergeNoFf(Path root, String targetBranch, String sourceBranch) {
        checkout(root, targetBranch);
        ExecResult merge = runWithIdentity(root,
                "merge", "--no-ff", "--no-edit", sourceBranch);
        if (merge.exitCode() != 0) {
            run(root, "merge", "--abort");
            throw new BusinessException(ErrorCode.GIT_MERGE_CONFLICT,
                    "合并 " + sourceBranch + " 到 " + targetBranch + " 发生冲突，已保留分支与提交现场");
        }
    }

    /**
     * 删除本地分支：force=false 使用 -d，仅允许删除已合并分支；true 使用 -D 强制删除。
     * 调用方必须已切离该分支；删除失败抛 {@code GIT_ROLLBACK_FAILED}。
     */
    public void deleteBranch(Path root, String branch, boolean force) {
        ExecResult result = run(root, "branch", force ? "-D" : "-d", branch);
        if (result.exitCode() != 0) {
            throw new BusinessException(ErrorCode.GIT_ROLLBACK_FAILED,
                    "删除分支 " + branch + " 失败: " + result.stderrSummary());
        }
    }

    /** 尽力中止进行中的合并，忽略 git 返回码；用于冲突后的清理。 */
    public void abortMerge(Path root) {
        run(root, "merge", "--abort");
    }

    /**
     * 丢弃当前分支上的未提交改动与未跟踪文件，用于删除临时子任务分支前清理工作区。
     * 只允许编排器在 tasknest/ 临时分支上调用：先校验分支，再执行 reset --hard 与 clean -fd。
     */
    public void discardWorkingTree(Path root, String expectedBranch) {
        ensureBranch(root, expectedBranch);
        if (!expectedBranch.startsWith("tasknest/")) {
            throw new IllegalStateException("拒绝在非临时分支上丢弃工作区: " + expectedBranch);
        }
        run(root, "reset", "--hard", "HEAD");
        run(root, "clean", "-fd");
    }

    /** 返回 fromRef 到 toRef 之间变更的文件路径；命令失败时返回空列表。 */
    public List<String> changedFiles(Path root, String fromRef, String toRef) {
        ExecResult result = run(root, "diff", "--name-only", fromRef, toRef);
        if (result.exitCode() != 0) {
            return List.of();
        }
        List<String> files = new ArrayList<>();
        for (String line : result.stdout().split("\\R")) {
            if (!line.isBlank()) {
                files.add(line.trim());
            }
        }
        return files;
    }

    /** 校验当前分支没有被外部切换。 */
    public void ensureBranch(Path root, String expected) {
        String actual = currentBranch(root);
        if (!expected.equals(actual)) {
            throw new BusinessException(ErrorCode.GIT_BRANCH_DRIFT,
                    "期望分支 " + expected + "，当前为 " + (actual == null ? "detached HEAD" : actual));
        }
    }

    private ExecResult runWithIdentity(Path root, String... args) {
        // 统一注入内置身份并关闭 GPG 签名，避免宿主机缺少 user.name 或签名提示阻塞自动化提交/合并。
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(root.toString());
        command.add("-c");
        command.add("user.name=" + INTERNAL_NAME);
        command.add("-c");
        command.add("user.email=" + INTERNAL_EMAIL);
        command.add("-c");
        command.add("commit.gpgsign=false");
        command.addAll(List.of(args));
        return execute(command);
    }

    private ExecResult run(Path root, String... args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(root.toString());
        command.addAll(List.of(args));
        return execute(command);
    }

    private ExecResult execute(List<String> command) {
        Process process;
        try {
            process = new ProcessBuilder(command).start();
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.GIT_UNAVAILABLE,
                    "无法执行 git，请确认本机已安装 Git");
        }
        try {
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            Thread outReader = streamReader(process.getInputStream(), stdout);
            Thread errReader = streamReader(process.getErrorStream(), stderr);
            if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new BusinessException(ErrorCode.GIT_COMMIT_FAILED, "Git 命令超时");
            }
            outReader.join(2000);
            errReader.join(2000);
            return new ExecResult(
                    process.exitValue(),
                    stdout.toString(StandardCharsets.UTF_8),
                    stderr.toString(StandardCharsets.UTF_8));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.GIT_COMMIT_FAILED, "Git 命令被中断");
        }
    }

    private Thread streamReader(InputStream input, ByteArrayOutputStream output) {
        Thread thread = new Thread(() -> {
            try (input; output) {
                input.transferTo(output);
            } catch (IOException ignored) {
                // 进程被终止时读取可能失败，退出码仍可判断。
            }
        });
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /** 一次 git 命令的退出码与原始输出。 */
    private record ExecResult(int exitCode, String stdout, String stderr) {
        String stderrSummary() {
            String value = stderr == null ? "" : stderr.trim();
            if (value.isEmpty()) {
                value = stdout == null ? "" : stdout.trim();
            }
            return value.length() > 300 ? value.substring(0, 300) : value;
        }
    }
}
