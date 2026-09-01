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
        Map<String, List<FileCoverage.MethodCoverage>> methodsByFile = methodsOf(builder, prefix);
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
                    methodsByFile.getOrDefault(path, List.of()),
                    lines
            ));
        }
        return result;
    }

    /**
     * 从<b>同一次分析</b>的 builder 里另取一份方法明细，按源文件归并。
     *
     * <b>为什么要走第二条路：</b>ISourceFileCoverage 只给方法的<b>计数</b>，
     * 拿不到方法名与行号，而「点击类看到方法」正需要它们；方法明细只存在于
     * IClassCoverage.getMethods() 里。
     *
     * <b>为什么文件级不跟着改用 getClasses()：</b>见 analyze() 里那段注释 ——
     * 自行遍历 classes 合并行数据，会在同一行同时属于两个类（写在一行里的匿名内部类）
     * 时把分母重复计入。所以这里只取方法，行与文件级计数仍由 getSourceFiles() 负责。
     *
     * 两条路径的归并键必须一致（都是「包名/源文件名」），否则方法会挂到别的文件上、
     * 或者挂不上任何文件。这个键正是 JaCoCo 自己聚 getSourceFiles() 用的那个，
     * 因此「方法条数 == sf.getMethodCounter()」是结构性成立的，有单测钉着。
     */
    private Map<String, List<FileCoverage.MethodCoverage>> methodsOf(CoverageBuilder builder, String prefix) {
        Map<String, List<FileCoverage.MethodCoverage>> byFile = new LinkedHashMap<>();
        for (IClassCoverage cc : builder.getClasses()) {
            // 没有调试信息的类压根不在 getSourceFiles() 里，也就没有文件可挂 ——
            // 不跳过的话会产生一批挂不上任何文件的孤儿方法
            if (cc.getSourceFileName() == null) {
                continue;
            }
            String path = prefix + cc.getPackageName() + "/" + cc.getSourceFileName();
            List<FileCoverage.MethodCoverage> list =
                    byFile.computeIfAbsent(path, k -> new ArrayList<>());
            for (IMethodCoverage mc : cc.getMethods()) {
                ICounter lc = mc.getLineCounter();
                ICounter bc = mc.getBranchCounter();
                list.add(new FileCoverage.MethodCoverage(
                        readableName(cc, mc), mc.getFirstLine(),
                        lc.getCoveredCount(), lc.getMissedCount(),
                        bc.getCoveredCount(), bc.getMissedCount()));
            }
        }
        return byFile;
    }

    /**
     * 方法在报表里显示成什么。
     *
     * 字节码里有两个名字对人没意义：{@code <init>} 是构造器、{@code <clinit>} 是静态
     * 初始化块。JaCoCo 自己的 HTML 报告靠 org.jacoco.report 的 JavaNames 渲染，
     * 但本项目只依赖 org.jacoco.core（面向内网，不为一个显示名多拉一个包），
     * 所以这里自己认这两个特例，其余原样用。
     *
     * <b>重载要能分辨</b>：pay(int) 与 pay(long) 名字相同、只有描述符不同，
     * 不带上参数段的话报表里会出现两行一模一样的方法名。返回类型不参与重载判定，去掉更短。
     */
    private String readableName(IClassCoverage cc, IMethodCoverage mc) {
        String n = mc.getName();
        if ("<init>".equals(n)) {
            String cls = cc.getName();
            int slash = cls.lastIndexOf('/');
            n = (slash < 0 ? cls : cls.substring(slash + 1)).replace('$', '.');
        } else if ("<clinit>".equals(n)) {
            n = "static {}";
        }
        String desc = mc.getDesc();
        int close = desc.indexOf(')');
        return close > 0 ? n + desc.substring(0, close + 1) : n;
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
