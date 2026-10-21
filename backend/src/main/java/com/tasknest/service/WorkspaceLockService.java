package com.tasknest.service;

import com.tasknest.common.BusinessException;
import com.tasknest.common.ErrorCode;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按 Git 仓库根目录加进程内锁。同一仓库同时只允许一个规划会话或执行运行。
 * 锁不持久化，进程重启后自动释放。
 */
@Service
public class WorkspaceLockService {

    private final Map<String, String> owners = new ConcurrentHashMap<>();

    public String acquire(Path root, String owner) {
        String key = key(root);
        String existing = owners.putIfAbsent(key, owner);
        if (existing != null && !existing.equals(owner)) {
            throw new BusinessException(ErrorCode.WORKSPACE_BUSY,
                    "同一仓库已有活动运行: " + key);
        }
        return key;
    }

    public void release(Path root, String owner) {
        String key = key(root);
        owners.computeIfPresent(key, (ignored, current) ->
                current.equals(owner) ? null : current);
    }

    public void release(String key, String owner) {
        owners.computeIfPresent(key, (ignored, current) ->
                current.equals(owner) ? null : current);
    }

    public boolean isLocked(Path root) {
        return owners.containsKey(key(root));
    }

    private String key(Path root) {
        try {
            return root.toRealPath().toString();
        } catch (IOException exception) {
            return root.toAbsolutePath().normalize().toString();
        }
    }
}
