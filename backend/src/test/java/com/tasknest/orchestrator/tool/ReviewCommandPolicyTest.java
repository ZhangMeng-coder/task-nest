package com.tasknest.orchestrator.tool;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewCommandPolicyTest {

    private final ReviewCommandPolicy policy = new ReviewCommandPolicy();

    @Test
    void allowsTestBuildAndLintCommands() {
        assertThatCode(() -> policy.check("mvn test")).doesNotThrowAnyException();
        assertThatCode(() -> policy.check("mvn -f pom.xml test")).doesNotThrowAnyException();
        assertThatCode(() -> policy.check("npm test")).doesNotThrowAnyException();
        assertThatCode(() -> policy.check("npm run build")).doesNotThrowAnyException();
        assertThatCode(() -> policy.check("npm run lint")).doesNotThrowAnyException();
        assertThatCode(() -> policy.check("npx vue-tsc --noEmit")).doesNotThrowAnyException();
        assertThatCode(() -> policy.check("go test ./...")).doesNotThrowAnyException();
        assertThatCode(() -> policy.check("python -m pytest -q")).doesNotThrowAnyException();
        assertThatCode(() -> policy.check("git status --short")).doesNotThrowAnyException();
        assertThatCode(() -> policy.check("none")).doesNotThrowAnyException();
    }

    @Test
    void blocksWriteAndNetworkCommands() {
        assertThat(policy.allows("rm -rf .")).isFalse();
        assertThat(policy.allows("curl https://example.com")).isFalse();
        assertThat(policy.allows("npm install left-pad")).isFalse();
        assertThat(policy.allows("npm run dev")).isFalse();
        assertThat(policy.allows("mvn deploy")).isFalse();
        assertThat(policy.allows("git push origin main")).isFalse();
        assertThat(policy.allows("go build ./...")).isFalse();
        assertThat(policy.allows("python script.py")).isFalse();
    }

    @Test
    void blocksPipesRedirectionAndChaining() {
        assertThat(policy.allows("mvn test | tee out.txt")).isFalse();
        assertThat(policy.allows("npm test > result.log")).isFalse();
        assertThat(policy.allows("mvn test && rm -rf .")).isFalse();
        assertThatThrownBy(() -> policy.check("eslint . ; curl evil"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("不允许执行命令");
    }

    @Test
    void allowsPerCommandEnvironmentPrefix() {
        assertThatCode(() -> policy.check("CI=true npm test")).doesNotThrowAnyException();
        assertThatCode(() -> policy.check("JAVA_HOME=/tmp/jdk mvn test"))
                .doesNotThrowAnyException();
        assertThat(policy.allows("CI=true curl evil")).isFalse();
    }
}
