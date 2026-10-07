package com.rtcc.platform.collector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rtcc.platform.config.CoverageProperties;
import com.rtcc.platform.config.ProjectConfig;
import com.rtcc.platform.model.FileCoverage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

/**
 * 归一化层（C++）：.gcno + .gcda → 行级覆盖模型。
 *
 * 与 Go 侧同构 —— 二进制格式没有对外稳定契约，交给官方工具转成可解析的格式：
 * {@code gcov --json-format} 输出每行的执行次数（JSON，不依赖源码文件），{@code gcov-tool merge} 在**原生数据层**
 * 合并多实例（与 Java 的 exec 探针取或、Go 的 covdata 按块求和是同一层次的操作，
 * 精度不会因为提前退化成行状态而损失）。
 *
 * 探针交回的字节流格式（大端）：重复 { u32 名字长度 | 名字 | u32 内容长度 | 内容 }。
 * 一个 C++ 服务通常有多个编译单元，就有多份 .gcda，所以要带文件名传多份。
 */
public class CppCoverageAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(CppCoverageAnalyzer.class);

    private final ProjectConfig props;
    /** 工具链可执行文件的路径是部署机器的属性，换机器才改，与项目无关，因此仍从平台配置取 */
    private final CoverageProperties platform;

    public CppCoverageAnalyzer(ProjectConfig props, CoverageProperties platform) {
        this.props = props;
        this.platform = platform;
    }

    /** @param dumps 各实例交回的字节流，一个实例一份 */
    public Map<String, FileCoverage> analyze(List<byte[]> dumps) throws IOException {
        if (dumps.isEmpty()) {
            return Map.of();
        }
        String root = props.getCppSourceRoot();
        if (root == null || root.isBlank()) {
            throw new IOException("未配置 coverage.cpp-source-root，无法定位 C++ 源码");
        }
        if (props.getCppObjectsDir() == null || props.getCppObjectsDir().isBlank()) {
            throw new IOException("未配置 coverage.cpp-objects-dir，缺少 .gcno 就解不出行号");
        }
        Path objDir = Path.of(props.getCppObjectsDir());
        List<Path> gcno = listBySuffix(objDir, ".gcno");
        if (gcno.isEmpty()) {
            // .gcno 是编译期产物，与运行中的实例是否健康无关。缺了它 gcov 什么都出不来，
            // 而空结果与「这些代码没被跑过」在界面上长得一模一样
            throw new IOException("cpp-objects-dir 下没有任何 .gcno：" + objDir.toAbsolutePath()
                    + "。请确认被测服务是以 `g++ --coverage` 构建的，且该目录指向它的对象文件目录");
        }

        Path work = Files.createTempDirectory("cppcov");
        try {
            List<Path> perInstance = new ArrayList<>();
            for (int i = 0; i < dumps.size(); i++) {
                Path dir = Files.createDirectory(work.resolve("i" + i));
                unpack(dumps.get(i), dir);
                copyAll(gcno, dir);
                perInstance.add(dir);
            }
            Path profile = perInstance.size() == 1 ? perInstance.get(0) : merge(perInstance, work, gcno);
            return parse(runGcov(profile, gcno), root);
        } finally {
            deleteTree(work);
        }
    }

    /** 把探针交回的字节流还原成一个个 .gcda 文件 */
    private void unpack(byte[] payload, Path dir) throws IOException {
        ByteBuffer buf = ByteBuffer.wrap(payload);
        int files = 0;
        while (buf.remaining() > 0) {
            if (buf.remaining() < 4) {
                throw new IOException("探针交回的覆盖数据被截断（文件名长度字段不完整）");
            }
            int nameLen = buf.getInt();
            if (nameLen < 0 || buf.remaining() < nameLen + 4) {
                throw new IOException("探针交回的覆盖数据被截断（文件名或长度字段不完整）");
            }
            byte[] name = new byte[nameLen];
            buf.get(name);
            int dataLen = buf.getInt();
            if (dataLen < 0 || buf.remaining() < dataLen) {
                throw new IOException("探针交回的覆盖数据被截断（内容长度不足）");
            }
            byte[] data = new byte[dataLen];
            buf.get(data);
            // 文件名来自被测实例，不能直接当路径用：只取末段，挡住 ../ 之类的穿越
            Path out = dir.resolve(Path.of(new String(name, StandardCharsets.UTF_8)).getFileName().toString());
            Files.write(out, data);
            files++;
        }
        if (files == 0) {
            throw new IOException("探针交回了空的覆盖数据（一份 .gcda 都没有）");
        }
    }

    /** 多实例在 .gcda 层面合并，逐个折叠：gcov-tool 一次只吃两个目录 */
    private Path merge(List<Path> dirs, Path work, List<Path> gcno) throws IOException {
        Path acc = dirs.get(0);
        for (int i = 1; i < dirs.size(); i++) {
            Path out = work.resolve("m" + i);
            exec(List.of(platform.getGcovMergeTool(), "merge",
                    acc.toAbsolutePath().toString(), dirs.get(i).toAbsolutePath().toString(),
                    "-o", out.toAbsolutePath().toString()), null, "gcov-tool merge");
            copyAll(gcno, out);
            acc = out;
        }
        return acc;
    }

    /**
     * {@code --json-format} 出 JSON 中间格式，它的行与计数全部来自 .gcno / .gcda，<b>不读源码</b>。
     * 原先用的文本格式是「把源码逐行印出来、在行前标计数」，能印几行取决于此刻读到的源码文件 ——
     * 源码正被改写（git pull、切分支、编辑器保存，很多是先截断再写）时为空就一行不印、写了一半就只印一半，
     * 而 gcov 照样退出 0：报告静默少一个文件，或少一截行。2026-10-01 发版前的全量验收撞上过一次
     * （拿 demo 的真实数据对照过，两种格式解出的结果逐字段相同）。
     * 用长选项是因为短选项 {@code -j} 在 GCC 9、10 里是 --human-readable（那两版的 JSON 是 {@code -i}），
     * 照样退出 0、出的却是文本；长选项从 GCC 9 起一直是 JSON，与 {@code -t} 同版出现，最低版本没有因此抬高。
     * {@code -t} 让结果走标准输出，不在源码树里留下文件；{@code -r} 只出相对路径的源码，把系统头文件挡在外面。
     */
    private String runGcov(Path profileDir, List<Path> gcno) throws IOException {
        // -b 让 JSON 带上分支（不加就没有），-c 让分支给出执行次数，-m 给 demangled 的函数名
        List<String> cmd = new ArrayList<>(List.of(platform.getGcovTool(), "--json-format", "-t", "-r", "-b", "-c", "-m",
                "-o", profileDir.toAbsolutePath().toString()));
        List<String> units = gcno.stream().map(p -> p.getFileName().toString()).toList();
        cmd.addAll(units);
        Path cwd = Path.of(props.getRepoDir(), props.getCppSourceRoot());
        Run r = run(cmd, cwd, "gcov");
        Set<String> withData = new HashSet<>();
        for (String u : units) {
            if (Files.exists(profileDir.resolve(u.substring(0, u.length() - ".gcno".length()) + ".gcda"))) {
                withData.add(u);
            }
        }
        requireAllUnits(r, units, withData);
        return r.out();
    }

    /** 外部工具跑完的样子。退出码留给调用方判：gcov 崩在半路时，stdout 里缺了谁是唯一的线索 */
    record Run(int exit, String out, String err) {}

    private static final String REFUSE = "这一轮不出报告 —— 照常出的话，它的源码会从报告里静默消失，看上去像是没有这个文件";

    /**
     * 交给 gcov 的每个编译单元都必须有结果，否则整轮拒绝出报告并点名。退出码管不全 ——
     * 实测（gcov 16.2，把真实的 .gcno 逐字节截断）有两种情形光看它不够：
     * <ul>
     *   <li>gcov 崩在某个 .gcno 上（截在文件头里会段错误）：退出码非 0，但 stderr 什么都没有，
     *       只知道「gcov 失败」、不知道是谁。gcov 按命令行顺序一份一份出 JSON，第一份没回来的就是它停下的地方</li>
     *   <li>.gcno 截在第一个函数记录之前：gcov <b>退出 0</b>，那份 JSON 照常回来、一个文件都没有，
     *       只在 stderr 说 no functions found。真没有函数的编译单元（只有声明、常量）也这么说，
     *       区别是它编不出计数器、不会有 .gcda —— 有 .gcda 却读不出函数的，就是 .gcno 坏了。
     *       只在退出 0 时这么判：数据目录里留着旧构建的 .gcda 时，gcov 还会报 stamp mismatch、以 5 退出，
     *       那一轮照样拒绝出报告，原因由 gcov 自己的 stderr 说，不能说成截断
     *       （用 -frandom-seed 让两次编译 stamp 相同的构建除外，那里旧 .gcda 会被这条误判）</li>
     * </ul>
     * 包级可见是为了让测试直接喂实测时 gcov 给出的输出。
     *
     * @param withData 有 .gcda 的编译单元
     */
    static void requireAllUnits(Run r, List<String> units, Set<String> withData) throws IOException {
        if (r.exit() == 0) {
            String mark = ":no functions found";
            List<String> unreadable = r.err().lines()
                    .filter(l -> l.endsWith(mark))
                    .map(l -> fileName(l.substring(0, l.length() - mark.length())))
                    .filter(withData::contains)
                    .toList();
            if (!unreadable.isEmpty()) {
                throw new IOException("编译单元 " + String.join("、", unreadable) + " 有覆盖数据（.gcda），"
                        + "gcov 却从它的 .gcno 里读不出任何函数 —— .gcno 多半被截断了。" + REFUSE);
            }
            return;
        }
        Set<String> answered = new HashSet<>();
        for (String doc : r.out().split("\r?\n")) {
            try {
                answered.add(fileName(JSON.readTree(doc).path("data_file").asText()));
            } catch (IOException e) {
                // 崩在半路时最后一份可能只写了一半，读不了就当它没回来
            }
        }
        Optional<String> stopped = units.stream().filter(u -> !answered.contains(u)).findFirst();
        if (stopped.isEmpty()) {
            throw failed("gcov", r); // 每份都回来了，gcov 在 stderr 里自己点了名（not a gcov notes file、corrupted、stamp mismatch ……）
        }
        String err = r.err().trim();
        throw new IOException("gcov 失败（exit " + r.exit() + "），停在编译单元 " + stopped.get()
                + " 上，它和排在后面的都没有给出结果" + (err.isEmpty()
                ? "；gcov 没留下任何报错，多半是这个 .gcno 已损坏、让 gcov 崩了。" : "：" + err + "。") + REFUSE);
    }

    /** 路径的末段：gcov 的 stderr 带全路径，JSON 的 data_file 也不保证各版本都原样回传命令行上的名字 */
    private static String fileName(String path) {
        return path.substring(Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1);
    }

    private String exec(List<String> cmd, Path cwd, String what) throws IOException {
        Run r = run(cmd, cwd, what);
        if (r.exit() != 0) {
            throw failed(what, r);
        }
        return r.out();
    }

    private static IOException failed(String what, Run r) {
        return new IOException(what + " 失败（exit " + r.exit() + "）：" + r.err().trim()
                + "。请确认平台所在环境已安装 GCC 工具链（coverage.gcov-tool / gcov-merge-tool）");
    }

    private Run run(List<String> cmd, Path cwd, String what) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (cwd != null) {
            pb.directory(cwd.toFile());
        }
        // gcov 的 no functions found 要按原文认（见 requireAllUnits），装了翻译的机器上它会被本地化
        pb.environment().put("LC_ALL", "C");
        Process p = pb.start();
        // stderr 另起线程读走，避免管道写满时两端互相阻塞
        StringBuilder err = new StringBuilder();
        Thread drain = new Thread(() -> {
            try {
                err.append(new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException ignored) {
            }
        });
        drain.setDaemon(true);
        drain.start();
        // profile 全文走 stdout，可能上千行，必须读完再等进程退出
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        try {
            if (!p.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException(what + " 超时未返回");
            }
            // 进程已经退出，stderr 一定读得到头。不能只等一会儿：是否拒绝出报告要按它的原文判，读一半就会静默放行
            drain.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("等待 " + what + " 被中断", e);
        }
        return new Run(p.exitValue(), out, err.toString());
    }

    /**
     * 把 demangled 名里最常见的 STL 模板缩回短名。
     *
     * gcov -m 给的是完整展开：{@code std::__cxx11::basic_string<char,
     * std::char_traits<char>, std::allocator<char> >} —— 一个 std::string 参数就是
     * 七十多个字符，两个参数的函数名能顶满整屏，报表那一列直接撑爆。
     *
     * <b>只缩最常见的这一个，不做通用的模板折叠</b>：那要真解析嵌套尖括号，
     * 而收益只是让更罕见的名字短一点。认不出来的原样保留 ——
     * 显示得长好过显示得不对。
     */
    private static String shorten(String name) {
        return name
                .replace("std::__cxx11::basic_string<char, std::char_traits<char>, std::allocator<char> >",
                        "std::string")
                .replace("std::basic_string<char, std::char_traits<char>, std::allocator<char> >",
                        "std::string");
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 一个函数在某一行上的样子。同一个函数（修饰名相同）的几份副本合在一个 Row 里：被几个编译单元 include 的内联函数
     * 每份 JSON 各有一份，链接器只留其中一份，其余副本的计数永远是 0 —— 它们在最终的程序里根本不存在，
     * 所以「有块没跑到」只看跑过的副本；同一段源码编出的副本控制流相同，分支按位置合并、任一副本走过就算覆盖
     */
    private static final class Row {
        long count;
        boolean partial;
        final BitSet taken = new BitSet();
        int branches;

        void add(JsonNode ln) {
            long c = ln.path("count").asLong();
            count += c;
            if (c > 0 && ln.path("unexecuted_block").asBoolean()) {
                partial = true;
            }
            int i = 0;
            for (JsonNode br : ln.path("branches")) {
                // (throw) 是编译器为可能抛异常的操作生成的路径，不是源码里写的条件。
                // 实测一个几百行的 demo 有 359 条分支，其中 120 条是 throw，
                // 而源码里真正的条件语句只有 32 处
                if (br.path("throw").asBoolean()) {
                    continue;
                }
                if (br.path("count").asLong() > 0) {
                    taken.set(i);
                }
                i++;
            }
            branches = Math.max(branches, i);
        }
    }

    /**
     * 一个源码函数，以修饰名为键：同名的几份是同一个函数的副本（内联函数、头文件里的 static 函数），合成一个、调用次数相加；
     * 模板的不同实例化修饰名不同，各算一个 —— 合成一个的话「某个实例化从没跑过」就看不见了。
     * firstLine / covered / missed 是方法明细，在生成行状态时顺手累计，只看它自己在各行上的样子
     */
    private static final class Fn {
        final String name;
        final int startLine;
        final int endLine;
        long calls;
        int firstLine = Integer.MAX_VALUE;
        int covered;
        int missed;

        Fn(String name, int startLine, int endLine) {
            this.name = name;
            this.startLine = startLine;
            this.endLine = endLine;
        }

        boolean contains(int line) {
            return startLine <= line && line <= endLine;
        }
    }

    /**
     * 解析 {@code gcov --json-format -t} 的输出：一个 .gcno 一份 JSON（一行一份）。包级可见是为了让测试直接喂真实形态的输出。
     *
     * <p>同一段源码常被编进好几个函数实例（openspec：cpp-normalization），每一行先按所属函数分组：
     * 组内是同一个函数的副本，按 {@link Row} 的规则合；组间是<b>不同的函数</b>（模板的不同实例化、同一行起头的几个 lambda），
     * 各是一段真实的机器码 —— 只要有一个在这一行上没跑过、或跑过但有块没跑到，这一行就只算部分覆盖，分支并列。
     * 学内联副本那样忽略没跑过的函数，就是把没跑过的代码说成跑过
     */
    Map<String, FileCoverage> parse(String gcovJson, String root) throws IOException {
        Map<String, TreeMap<Integer, Map<String, Row>>> linesByFile = new LinkedHashMap<>();
        Map<String, Map<String, Fn>> fnsByFile = new LinkedHashMap<>();
        String base = root.replace('\\', '/');
        for (String doc : gcovJson.split("\r?\n")) {
            if (doc.isBlank()) {
                continue;
            }
            JsonNode d = JSON.readTree(doc);
            for (JsonNode f : d.path("files")) {
                String path = base + "/" + f.path("file").asText().replace('\\', '/');
                TreeMap<Integer, Map<String, Row>> lines = linesByFile.computeIfAbsent(path, k -> new TreeMap<>());
                Map<String, Fn> fns = fnsByFile.computeIfAbsent(path, k -> new LinkedHashMap<>());
                // 先收函数再收行：行只能归给这份 JSON 里这个文件的函数
                Map<String, Fn> here = new LinkedHashMap<>();
                for (JsonNode fn : f.path("functions")) {
                    String key = fn.path("name").asText();
                    Fn x = fns.computeIfAbsent(key, k -> new Fn(shorten(fn.path("demangled_name").asText()),
                            fn.path("start_line").asInt(), fn.path("end_line").asInt()));
                    x.calls += fn.path("execution_count").asLong();
                    here.put(key, x);
                }
                for (JsonNode ln : f.path("lines")) {
                    int no = ln.path("line_number").asInt();
                    lines.computeIfAbsent(no, k -> new LinkedHashMap<>())
                            .computeIfAbsent(owner(here, ln.path("function_name").asText(), no), k -> new Row())
                            .add(ln);
                }
            }
        }
        Map<String, FileCoverage> result = new LinkedHashMap<>();
        linesByFile.forEach((path, byLine) -> {
            if (byLine.isEmpty()) {
                return; // 纯声明的头文件没有可执行行，列进来只会是一行 0/0 的噪声
            }
            Map<String, Fn> fns = fnsByFile.getOrDefault(path, Map.of());
            List<FileCoverage.LineCoverage> lines = new ArrayList<>();
            byLine.forEach((no, byFn) -> {
                long count = 0;
                boolean partial = false;
                int taken = 0;
                int branches = 0;
                for (Map.Entry<String, Row> e : byFn.entrySet()) {
                    Row r = e.getValue();
                    count += r.count;
                    partial |= r.count == 0 || r.partial;
                    taken += r.taken.cardinality();
                    branches += r.branches;
                    Fn fn = fns.get(e.getKey());
                    if (fn != null) {
                        fn.firstLine = Math.min(fn.firstLine, no);
                        if (r.count > 0) {
                            fn.covered++;
                        } else {
                            fn.missed++;
                        }
                    }
                }
                lines.add(new FileCoverage.LineCoverage(no, count == 0 ? "MISSED" : partial ? "PARTIAL" : "COVERED",
                        taken, branches - taken));
            });
            List<Fn> sorted = new ArrayList<>(fns.values());
            sorted.sort(Comparator.comparingInt(fn -> fn.startLine)); // 稳定排序：同一行起头的几个函数保持 gcov 给的先后
            int calledFns = (int) sorted.stream().filter(fn -> fn.calls > 0).count();
            int missed = (int) lines.stream().filter(l -> "MISSED".equals(l.status())).count();
            int covered = lines.size() - missed;
            int cb = lines.stream().mapToInt(FileCoverage.LineCoverage::coveredBranches).sum();
            int mb = lines.stream().mapToInt(FileCoverage.LineCoverage::missedBranches).sum();
            int slash = path.lastIndexOf('/');
            result.put(path, new FileCoverage(
                    path,
                    slash < 0 ? "" : path.substring(0, slash).replace('/', '.'),
                    slash < 0 ? path : path.substring(slash + 1),
                    covered, missed,
                    covered * 100d / lines.size(),
                    cb, mb, calledFns, sorted.size() - calledFns,
                    methodsOf(sorted),
                    lines));
        });
        // 判的是结果而不是 gcov 给了几个条目：每份 JSON 都会给 include 进来的纯声明头文件列一个空条目，
        // 只看条目数的话这道兜底永远走不到。不读源码之后源码根配错也不再让 gcov 出不来数，
        // 走到这里的是源码全以绝对路径编译（被 -r 当成系统头文件滤掉了），或者没有一个编译单元含有函数
        if (result.isEmpty()) {
            throw new IOException("gcov 没有输出任何源码的覆盖数据：源码若是以绝对路径编译的，会被当成系统头文件滤掉。"
                    + "请确认被测服务是在 coverage.cpp-source-root 指向的目录下、用相对路径编译的（.gcno 里记的是相对源码名）");
        }
        return result;
    }

    /**
     * 这一行归哪个函数。先信 gcov 标的 {@code function_name}，但要那个函数的范围真包含这一行 ——
     * GCC 9、10 标的是「最后一个开始的函数」，lambda 结束后的行也标成 lambda；GCC 11 起按结束行维护嵌套栈，
     * 可两层在同一行结束时也只弹一层。不包含就归给包含这一行的最内层函数（范围最小的那个）；
     * 一个都不包含时返回 ""，这一行不进任何方法
     */
    private static String owner(Map<String, Fn> here, String named, int line) {
        Fn f = here.get(named);
        if (f != null && f.contains(line)) {
            return named;
        }
        String best = "";
        int span = Integer.MAX_VALUE;
        for (Map.Entry<String, Fn> e : here.entrySet()) {
            Fn g = e.getValue();
            if (g.contains(line) && g.endLine - g.startLine < span) {
                best = e.getKey();
                span = g.endLine - g.startLine;
            }
        }
        return best;
    }

    /**
     * 方法明细：每个函数在它自己那些行上的样子（行的归属见 {@link #owner}）。lambda 自成一个方法 —— 不能用
     * 「起始行到下一个起始行」近似，那会把外层函数后半段全算给 lambda。同一行起头的几个函数各占一条、首行号相同；
     * 一行都没有的函数不进明细（方法计数照算）
     */
    private static List<FileCoverage.MethodCoverage> methodsOf(List<Fn> fns) {
        List<FileCoverage.MethodCoverage> out = new ArrayList<>();
        for (Fn fn : fns) {
            if (fn.covered + fn.missed > 0) {
                out.add(new FileCoverage.MethodCoverage(fn.name, fn.firstLine, fn.covered, fn.missed, null, null));
            }
        }
        return out;
    }

    private List<Path> listBySuffix(Path dir, String suffix) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (var s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(suffix)).sorted().toList();
        }
    }

    private void copyAll(List<Path> files, Path dir) throws IOException {
        for (Path f : files) {
            Files.copy(f, dir.resolve(f.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void deleteTree(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException e) {
            log.warn("临时目录清理失败：{}", dir);
        }
    }
}
