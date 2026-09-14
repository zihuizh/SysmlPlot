package io.github.zihuizh.sysmlplot.cli;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.xtext.diagnostics.Severity;
import org.eclipse.xtext.validation.Issue;

import io.github.zihuizh.sysmlplot.engine.SysMLWorkspace;

/**
 * 对整个工作区做一次批量检查，用于**官方语料验收**：给一个语料目录，报告每个文件的
 * 错误/警告数量，并输出可入库的报告 JSON。
 *
 * <p>它同时是性能观察点：报告里带各阶段耗时，见 {@code docs/archive/PHASE-3-PLAN.md} 的验收与性能章节。
 */
public final class WorkspaceCheck {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private WorkspaceCheck() {
    }

    public record FileReport(String file, int errors, int warnings, List<String> messages) {
    }

    public record Summary(String corpus, String modelDigest, int files, int filesWithErrors,
                          int errors, int warnings, long loadMillis, long checkMillis) {
    }

    public record Report(int schemaVersion, Summary summary, List<FileReport> files) {
    }

    public static Report run(SysMLWorkspace workspace, Path reportPath, long loadMillis) throws Exception {
        long started = System.currentTimeMillis();
        List<FileReport> reports = new ArrayList<>();
        int totalErrors = 0;
        int totalWarnings = 0;
        int filesWithErrors = 0;

        List<Resource> resources = new ArrayList<>(workspace.workspaceResources());
        resources.sort(Comparator.comparing(resource -> resource.getURI().lastSegment()));
        for (Resource resource : resources) {
            List<String> messages = new ArrayList<>();
            int errors = 0;
            int warnings = 0;
            for (Issue issue : workspace.validate(resource)) {
                if (issue.getSeverity() == Severity.ERROR) {
                    errors++;
                    messages.add("ERROR " + issue.getLineNumber() + ": " + issue.getMessage());
                } else if (issue.getSeverity() == Severity.WARNING) {
                    warnings++;
                    messages.add("WARN " + issue.getLineNumber() + ": " + issue.getMessage());
                }
            }
            totalErrors += errors;
            totalWarnings += warnings;
            if (errors > 0) {
                filesWithErrors++;
            }
            reports.add(new FileReport(resource.getURI().lastSegment(), errors, warnings, List.copyOf(messages)));
        }
        long checkMillis = System.currentTimeMillis() - started;

        Summary summary = new Summary(
                workspace.workspaceRoot().toString(),
                workspace.modelDigest(),
                reports.size(),
                filesWithErrors,
                totalErrors,
                totalWarnings,
                loadMillis,
                checkMillis);
        Report report = new Report(0, summary, List.copyOf(reports));

        System.out.printf("[check] files=%d filesWithErrors=%d errors=%d warnings=%d%n",
                summary.files(), summary.filesWithErrors(), summary.errors(), summary.warnings());
        System.out.printf("[check] load=%.1fs check=%.1fs%n", loadMillis / 1000.0, checkMillis / 1000.0);

        Map<String, Integer> byError = new LinkedHashMap<>();
        for (FileReport file : reports) {
            if (file.errors() > 0) {
                System.out.printf("  [error] %-52s errors=%d%n", file.file(), file.errors());
                for (String message : file.messages()) {
                    if (message.startsWith("ERROR")) {
                        byError.merge(message.substring(message.indexOf(' ') + 1), 1, Integer::sum);
                    }
                }
            }
        }
        if (!byError.isEmpty()) {
            System.out.println("  [error-kinds]");
            byError.entrySet().stream()
                    .sorted((left, right) -> Integer.compare(right.getValue(), left.getValue()))
                    .limit(10)
                    .forEach(entry -> System.out.printf("    %3d  %s%n", entry.getValue(), entry.getKey()));
        }

        if (reportPath != null) {
            Path target = reportPath.toAbsolutePath();
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Files.writeString(target, GSON.toJson(report) + "\n", StandardCharsets.UTF_8);
            System.out.println("[check] report written to " + target);
        }
        return report;
    }
}
