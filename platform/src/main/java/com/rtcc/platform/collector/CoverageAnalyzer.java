package com.rtcc.platform.collector;

import com.rtcc.platform.model.FileCoverage;
import org.jacoco.core.analysis.*;
import org.jacoco.core.data.ExecutionDataStore;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 归一化层：执行数据 + 字节码 → 行级覆盖模型。
 *
 * 只有 exec 数据是不够的——它记录的是探针 id 是否命中，
 * 必须结合 class 文件做字节码分析，才能知道哪些行是可执行的、探针落在哪一行。
 * 这也是 exec、class、源码三者版本必须一致的原因。
 */
@Component
public class CoverageAnalyzer {

    /**
     * @param sourceRoot 源码根目录相对仓库根的位置，如 demo-service/src/main/java。
     *                   产出的 path 一律以仓库根为基准 —— 多语言共存时各有各的源码根，
     *                   IR 里再用「源码根相对路径」就无法唯一定位文件，也对不上 git diff 的输出
     */
    public Map<String, FileCoverage> analyze(ExecutionDataStore execStore, File classesDir, String sourceRoot)
            throws IOException {
        CoverageBuilder builder = new CoverageBuilder();
        new Analyzer(execStore, builder).analyzeAll(classesDir);

        String prefix = sourceRoot == null || sourceRoot.isBlank() ? "" : sourceRoot.replace('\\', '/') + "/";
        Map<String, FileCoverage> result = new LinkedHashMap<>();
        // 用 getSourceFiles() 而不是 getClasses()：JaCoCo 已按源文件把外部类、内部类、
        // 匿名类的覆盖数据聚合好了。若改为遍历 getClasses() 自行合并，
        // 同一行同时属于两个类时（例如写在一行里的匿名内部类）会被重复计入分母。
        for (ISourceFileCoverage sf : builder.getSourceFiles()) {
            String path = prefix + sf.getPackageName() + "/" + sf.getName();

            List<FileCoverage.LineCoverage> lines = new ArrayList<>();
            int covered = 0, missed = 0;
            for (int i = sf.getFirstLine(); i <= sf.getLastLine(); i++) {
                ILine line = sf.getLine(i);
                String status = statusOf(line.getStatus());
                if (status == null) {
                    continue;
                }
                // 行级分支是源码区菱形标记的数据来源。没有分支的行拿到的是 0/0，
                // 不是 null —— null 在 IR 里的含义是「这门语言不提供」，而 Java 提供
                ICounter bc = line.getBranchCounter();
                lines.add(new FileCoverage.LineCoverage(i, status,
                        bc.getCoveredCount(), bc.getMissedCount()));
                if ("MISSED".equals(status)) {
                    missed++;
                } else {
                    covered++;
                }
            }

            int total = covered + missed;
            // 文件级的三组计数各取各的，不从行级累加：JaCoCo 已按源文件把外部类、
            // 内部类、匿名类聚合好了，自行累加会在同一行同时属于两个类时重复计入
            ICounter fileBranches = sf.getBranchCounter();
            ICounter fileMethods = sf.getMethodCounter();
            result.put(path, new FileCoverage(
                    path,
                    sf.getPackageName().replace('/', '.'),
                    sf.getName(),
                    covered,
                    missed,
                    total == 0 ? 0d : covered * 100d / total,
                    fileBranches.getCoveredCount(), fileBranches.getMissedCount(),
                    fileMethods.getCoveredCount(), fileMethods.getMissedCount(),
                    null,
                    lines
            ));
        }
        return result;
    }

    /** 返回 null 表示该行不可执行（空行、注释、方法签名、switch 的 case 标签等） */
    private String statusOf(int jacocoStatus) {
        return switch (jacocoStatus) {
            case ICounter.NOT_COVERED -> "MISSED";
            case ICounter.PARTLY_COVERED -> "PARTIAL";
            case ICounter.FULLY_COVERED -> "COVERED";
            default -> null;
        };
    }
}
