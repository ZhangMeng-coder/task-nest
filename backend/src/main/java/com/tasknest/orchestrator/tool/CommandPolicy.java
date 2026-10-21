package com.tasknest.orchestrator.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 危险命令防护栏。命中后不执行，返回 COMMAND_BLOCKED。
 * 规则匹配是尽力而为，不是不可绕过的安全边界。
 */
public class CommandPolicy {

    private static final Set<String> READ_ONLY_GIT = Set.of(
            "status", "diff", "log", "show", "rev-parse", "ls-files");
    /** 带参数的全局选项：跳过其值，避免把参数误判成子命令。 */
    private static final Set<String> GIT_OPTIONS_WITH_VALUE = Set.of(
            "-C", "--git-dir", "--work-tree", "--namespace", "--exec-path",
            "--super-prefix", "--config-env");
    private static final List<Rule> RULES = List.of(
            new Rule("windows-disk", "(?i)(^|[&|;]\\s*)(format|diskpart|bcdedit)(\\s|$)"),
            new Rule("system-power", "(?i)(^|[&|;]\\s*)(shutdown|reboot)(\\s|$)"),
            new Rule("windows-registry", "(?i)\\breg\\s+delete\\b"),
            new Rule("rm-root", "(?i)\\brm\\s+-[a-z]*r[a-z]*\\s+(/|~|\\*)(\\s|$)"),
            new Rule("rm-parent", "(?i)\\brm\\s+-[a-z]*r[a-z]*\\s+\\.\\.(/|\\s|$)"),
            new Rule("del-root", "(?i)\\bdel\\s+/[a-z]*[sq][a-z]*\\s+[a-z]:[\\\\/]?(\\s|$)"),
            new Rule("remove-item-root", "(?i)\\bremove-item\\s+.*-recurse.*(c:\\\\|/|~)"),
            new Rule("privilege", "(?i)(^|[&|;]\\s*)(sudo|runas|su)(\\s|$)"),
            new Rule("git-reset-hard", "(?i)\\bgit\\s+reset\\s+--hard\\b"),
            new Rule("git-clean", "(?i)\\bgit\\s+clean\\s+-[a-z]*[fd][a-z]*\\b"),
            new Rule("git-push-force", "(?i)\\bgit\\s+push\\s+.*(--force|-f)\\b"),
            new Rule("download-exec", "(?i)(curl|wget)\\b[^\\n|]*\\|\\s*(sh|bash|zsh|powershell|cmd)\\b"),
            new Rule("invoke-expression", "(?i)\\binvoke-expression\\b.*(downloadstring|downloadfile|http)"),
            new Rule("drop-database", "(?i)\\bdrop\\s+database\\b"),
            new Rule("truncate", "(?i)\\btruncate\\s+(table\\s+)?[a-z0-9_.`\"]+"),
            new Rule("delete-without-where", "(?i)\\bdelete\\s+from\\s+[a-z0-9_.`\"]+\\s*;?\\s*$"),
            new Rule("fork-bomb", ":\\(\\)\\s*\\{\\s*:\\|:&\\s*\\}\\s*;\\s*:"),
            new Rule("sensitive-env", "(?i)(^|[\\s/\\\\])\\.env(\\.|\\s|$|/)"),
            new Rule("sensitive-ssh", "(?i)(^|[\\s/\\\\])\\.ssh([/\\\\]|\\s|$)"),
            new Rule("sensitive-aws", "(?i)(^|[\\s/\\\\])\\.aws([/\\\\]|\\s|$)"),
            new Rule("sensitive-pem", "(?i)\\.(pem|p12)(\\s|$|[\"'])"),
            new Rule("git-config-file", "(?i)\\.git[/\\\\]config\\b")
    );

    public void check(String command) {
        String value = command == null ? "" : command;
        for (Rule rule : RULES) {
            if (rule.pattern().matcher(value).find()) {
                throw new ToolException("COMMAND_BLOCKED",
                        "命令命中危险规则：" + rule.name(), rule.name());
            }
        }
        checkGit(value);
    }

    private void checkGit(String command) {
        String lower = command.toLowerCase(Locale.ROOT);
        if (lower.contains("--exec-path")
                || lower.contains("--upload-pack")
                || lower.contains("--receive-pack")) {
            throw new ToolException("COMMAND_BLOCKED",
                    "命令命中危险规则：Git 危险选项", "git-dangerous-option");
        }
        List<String> tokens = tokenize(command);
        for (int index = 0; index < tokens.size(); index++) {
            if (!isGitToken(tokens.get(index))) {
                continue;
            }
            int cursor = index + 1;
            while (cursor < tokens.size()) {
                String token = tokens.get(cursor);
                if ("-c".equals(token) || "--config-env".equals(token)) {
                    throw new ToolException("COMMAND_BLOCKED",
                            "禁止通过 git -c / --config-env 注入配置", "git-config-injection");
                }
                if (GIT_OPTIONS_WITH_VALUE.contains(token)) {
                    cursor += 2;
                    continue;
                }
                if (token.startsWith("-")) {
                    cursor++;
                    continue;
                }
                if (!READ_ONLY_GIT.contains(token)) {
                    throw new ToolException("COMMAND_BLOCKED",
                            "Git 写操作只能由 Orchestrator 执行，禁止 git " + token,
                            "git-write-subcommand");
                }
                break;
            }
        }
    }

    private boolean isGitToken(String token) {
        String value = token.toLowerCase(Locale.ROOT).replace('\\', '/');
        return "git".equals(value) || value.endsWith("/git") || value.endsWith("/git.exe")
                || "git.exe".equals(value);
    }

    private List<String> tokenize(String command) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int index = 0; index < command.length(); index++) {
            char value = command.charAt(index);
            if (Character.isWhitespace(value) || value == '&' || value == '|'
                    || value == ';' || value == '(' || value == ')') {
                if (!current.isEmpty()) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            if (value == '\'' || value == '"') {
                char quote = value;
                index++;
                while (index < command.length() && command.charAt(index) != quote) {
                    current.append(command.charAt(index));
                    index++;
                }
                continue;
            }
            current.append(value);
        }
        if (!current.isEmpty()) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    private record Rule(String name, Pattern pattern) {
        Rule(String name, String regex) {
            this(name, Pattern.compile(regex));
        }
    }
}
