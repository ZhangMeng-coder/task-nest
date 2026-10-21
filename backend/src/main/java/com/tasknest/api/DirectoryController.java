package com.tasknest.api;

import com.tasknest.api.dto.DirectoryListing;
import com.tasknest.common.ApiResponse;
import com.tasknest.service.DirectoryBrowserService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/fs")
public class DirectoryController {

    private final DirectoryBrowserService directoryBrowserService;

    public DirectoryController(DirectoryBrowserService directoryBrowserService) {
        this.directoryBrowserService = directoryBrowserService;
    }

    /** 浏览后端所在机器的本地目录，供创建主任务时选择工作目录。 */
    @GetMapping("/directories")
    public ApiResponse<DirectoryListing> list(
            @RequestParam(name = "path", required = false) String path,
            @RequestParam(name = "showHidden", defaultValue = "false") boolean showHidden) {
        return ApiResponse.ok(directoryBrowserService.list(path, showHidden));
    }
}
