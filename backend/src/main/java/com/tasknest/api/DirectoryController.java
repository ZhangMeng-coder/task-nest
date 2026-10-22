package com.tasknest.api;

import com.tasknest.api.dto.DirectoryListing;
import com.tasknest.common.ApiResponse;
import com.tasknest.service.DirectoryBrowserService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 本机目录浏览 REST 入口，供创建主任务时选择 Git 工作目录。
 *
 * <p>只列出文件夹，不读取文件内容。浏览器 {@code showDirectoryPicker()} 无法取得
 * 绝对路径，因此目录选择必须经此后端接口。路径非法、不存在或不可读时由
 * {@link com.tasknest.service.DirectoryBrowserService} 分别抛出
 * {@code INVALID_WORKING_DIR} / {@code DIRECTORY_NOT_READABLE}。</p>
 */
@RestController
@RequestMapping("/api/fs")
public class DirectoryController {

    private final DirectoryBrowserService directoryBrowserService;

    public DirectoryController(DirectoryBrowserService directoryBrowserService) {
        this.directoryBrowserService = directoryBrowserService;
    }

    /**
     * {@code GET /api/fs/directories}：浏览后端所在机器的本地目录。
     *
     * <p>{@code path} 省略时返回用户主目录，返回值会规范化为绝对真实路径；
     * {@code showHidden} 默认 {@code false}，为 {@code true} 时才返回隐藏目录。
     * 结果只包含文件夹，最多 500 项，被截断时 {@code truncated=true}。</p>
     *
     * @return 当前目录、父目录（根目录时为 {@code null}）、盘符根列表与子目录
     */
    @GetMapping("/directories")
    public ApiResponse<DirectoryListing> list(
            @RequestParam(name = "path", required = false) String path,
            @RequestParam(name = "showHidden", defaultValue = "false") boolean showHidden) {
        return ApiResponse.ok(directoryBrowserService.list(path, showHidden));
    }
}
