package com.tasknest.orchestrator.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkspaceSandboxTest {

    @TempDir
    Path tempDir;

    @Test
    void resolvesPathsInsideWorkspace() throws IOException {
        Files.writeString(tempDir.resolve("README.md"), "hello");
        WorkspaceSandbox sandbox = new WorkspaceSandbox(tempDir);

        Path file = sandbox.resolveExisting("README.md", false);

        assertThat(Files.readString(file)).isEqualTo("hello");
        assertThat(sandbox.relative(file)).isEqualTo("README.md");
        assertThatCode(() -> sandbox.resolveForWrite("src/NewFile.java"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsPathsOutsideWorkspace() {
        WorkspaceSandbox sandbox = new WorkspaceSandbox(tempDir);

        assertThatThrownBy(() -> sandbox.resolveForWrite("../escape.txt"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("超出工作目录");
        assertThatThrownBy(() -> sandbox.resolveExisting(tempDir.getParent().toString(), true))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("超出工作目录");
    }

    @Test
    void rejectsSensitivePaths() {
        WorkspaceSandbox sandbox = new WorkspaceSandbox(tempDir);

        assertThatThrownBy(() -> sandbox.resolveForWrite(".env"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("敏感");
        assertThatThrownBy(() -> sandbox.resolveForWrite(".env.local"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("敏感");
        assertThatThrownBy(() -> sandbox.resolveForWrite(".git/config"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("敏感");
        assertThatThrownBy(() -> sandbox.resolveForWrite("certs/server.pem"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("敏感");
        assertThatThrownBy(() -> sandbox.resolveForWrite(".ssh/id_rsa"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("敏感");
        assertThatCode(() -> sandbox.resolveForWrite(".env.example"))
                .doesNotThrowAnyException();
    }
}
