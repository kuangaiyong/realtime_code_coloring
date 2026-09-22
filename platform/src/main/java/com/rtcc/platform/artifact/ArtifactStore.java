package com.rtcc.platform.artifact;

import com.rtcc.platform.config.ProjectConfig;
import com.rtcc.platform.model.BuildVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 按 buildId 存放被测服务的编译产物。
 *
 * <p><b>为什么以 buildId 为唯一索引</b>（整个容器方案的地基，不是实现细节）：
 * 按固定路径存的话，推上去的是 commit A 的产物、容器里跑的是 commit B，
 * 平台会拿 A 的字节码解 B 的探针数据，算出<b>行号错位却看起来完全正常</b>的报告。
 * 按 buildId 索引之后，取不到就是明确的「这个构建的产物没上传」。
 *
 * <p>而 buildId 恰好已经是实例自报的那个值（见 {@code BuildVersion}），
 * 两端天然对齐，不需要新的版本标识。
 */
public class ArtifactStore {

    private static final Logger log = LoggerFactory.getLogger(ArtifactStore.class);

    /**
     * 只认 40 位小写 hex。<b>buildId 直接参与磁盘路径</b>，不校验就是路径穿越 ——
     * 一个 {@code ../../} 能让上传接口写到平台的任意位置。
     */
    private static final Pattern SHA = Pattern.compile("^[0-9a-f]{40}$");

    private final Path root;
    private final int keep;

    public ArtifactStore(Path root, int keep) {
        if (root == null) {
            throw new IllegalArgumentException("产物根目录不能为空");
        }
        // keep < 1 会让 prune 把<b>刚刚换入的那个构建</b>一起删掉：上传接口照样回 200、
        // kept 是空的，下一轮采集却说「这个构建没上传产物」—— 上传说成功、取用说没有。
        // 这是平台级配置（yml，改了要重启），写错就该在启动时炸掉、说清楚是哪一项，
        // 而不是等到某次上传之后才以一种完全不着边际的方式表现出来
        if (keep < 1) {
            throw new IllegalArgumentException(
                    "coverage.artifact-keep 至少是 1，实际为 " + keep
                            + "：配成 0 或负数会让每次上传都把刚存好的那份产物立刻删掉");
        }
        this.root = root;
        this.keep = keep;
    }

    public Path root() {
        return root;
    }

    public int keep() {
        return keep;
    }

    /** {@code <root>/<projectId>/<buildId>/<lang>/} */
    public Path dirOf(String projectId, String buildId, ArtifactKind kind) {
        requireValidBuildId(buildId);
        return projectDir(projectId).resolve(buildId).resolve(kind.dir());
    }

    /**
     * 校验 buildId 能不能安全地当目录名用。
     *
     * <p>拒绝 {@code -dirty}：它意味着同一个 commit 可以对应无数份不同的产物，
     * 允许上传就等于允许「同一个 key 指向不同内容」，取出来的可能不是
     * 这个容器加载的那份 —— 仍然是行号错位且看不出来。
     */
    public void requireValidBuildId(String buildId) {
        if (buildId == null || buildId.isBlank()) {
            throw new IllegalArgumentException("buildId 不能为空");
        }
        if (buildId.endsWith("-dirty")) {
            throw new IllegalArgumentException("拒绝脏构建的产物：" + buildId
                    + "。工作树脏时同一个 commit 可以对应多份不同的产物，"
                    + "按它取回的可能不是被测进程加载的那一份，算出的行号会错位且看不出来");
        }
        if (!SHA.matcher(buildId).matches()) {
            throw new IllegalArgumentException("buildId 必须是 40 位小写十六进制，实际为：" + buildId);
        }
    }

    /**
     * 能不能当构建 id 用，不抛异常的版本。
     *
     * <p>给「磁盘上混进了不是 buildId 的目录」那种场景用：手工建的、别的工具落下的目录，
     * 列表要能<b>跳过</b>它继续往下列，而不是整个接口挂掉 ——
     * 而列表存在的理由正是「运维要能看出磁盘上到底有什么」，
     * 偏偏在磁盘上真有意外东西的时候瘫掉，自相矛盾。
     */
    public boolean isValidBuildId(String buildId) {
        try {
            requireValidBuildId(buildId);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * 校验 projectId 能不能安全地当目录名用。
     *
     * <p><b>它与 buildId 是同一条路上的两个口子</b>：projectId 同样直接参与磁盘路径
     * （{@code <root>/<projectId>/...}）。只堵 buildId 等于没堵 —— 实测过，
     * {@code ?project=../../../../tmp/x} 会把产物写到产物根之外并照样回 200，
     * 同一个口子走删除接口就是把根之外的整棵目录树删掉。
     *
     * <p><b>为什么做包含性检查而不限定字符集</b>：项目 id 由用户自取，现网已经存在
     * 中文项目名，限字符集会把合法项目挡在外面。这里只要求「归一化之后仍落在根之下」，
     * 绝对路径也一并挡住 —— {@link Path#resolve} 遇到绝对路径会把 base 整个替换掉，
     * 那比 {@code ../} 更直接。
     */
    public void requireValidProjectId(String projectId) {
        projectDir(projectId);
    }

    /** {@code <root>/<projectId>/}。一切按 projectId 拼路径的地方都必须走这里，否则校验就是摆设 */
    private Path projectDir(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalArgumentException("projectId 不能为空");
        }
        Path base = root.toAbsolutePath().normalize();
        Path dir = base.resolve(projectId).normalize();
        if (!dir.startsWith(base) || dir.equals(base)) {
            throw new IllegalArgumentException("projectId 会指向产物根之外，拒绝：" + projectId);
        }
        return dir;
    }

    /**
     * 解压一份产物到 {@code <root>/<projectId>/<buildId>/<lang>/}。
     *
     * <p><b>先解压到同级临时目录，全部条目都处理完且没有异常才换入正式目录</b>：
     * 若直接边解压边写正式目录，Zip Slip（或 zip 中途损坏）在第 N 个条目才触发时，
     * 前 N-1 个合法条目已经落盘 —— {@link #find} 的判据只看「目录存在且非空」，
     * 会把这个半成品当成已经就绪的构建，后续拿着不完整的字节码去解探针数据，
     * 算出的覆盖率错得界面上看不出任何异样。若这次是对已有 buildId 的重传，更糟：
     * 旧产物一旦被清空就回不来，绝不能用半成品去顶替一份还在服役的好产物。
     *
     * <p>存完顺手 {@link #prune}，不必另起一个清理任务。
     */
    public void save(String projectId, String buildId, ArtifactKind kind, InputStream zip)
            throws IOException {
        Path dir = dirOf(projectId, buildId, kind);
        Path tmp = dir.getParent().resolve(kind.dir() + ".tmp-" + System.nanoTime());
        Files.createDirectories(tmp);
        Path aside = null;
        try {
            Path base = tmp.toAbsolutePath().normalize();
            int files = 0;
            try (ZipInputStream in = new ZipInputStream(zip)) {
                ZipEntry e;
                while ((e = in.getNextEntry()) != null) {
                    // Zip Slip：条目名里带 ../ 就能写到目标目录之外。
                    // 上传接口是写平台磁盘的，这条必须挡住
                    Path out = base.resolve(e.getName()).normalize();
                    if (!out.startsWith(base)) {
                        throw new BadArtifactException("产物包里有指向目标目录之外的条目：" + e.getName());
                    }
                    if (e.isDirectory()) {
                        Files.createDirectories(out);
                    } else {
                        Files.createDirectories(out.getParent());
                        Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                        // 只数<b>文件</b>：一个只含目录条目的 zip 同样是「没有产物」，
                        // 但目录条目会让计数非零，于是换入一个只有空子目录的 <lang>/，
                        // 而 find() 的判据是「存在且非空」—— 空子目录照样让它判为就绪
                        files++;
                    }
                }
            } catch (java.util.zip.ZipException | java.io.EOFException e) {
                // zip 本身坏了：格式不对，或者传输中被截断。这两类异常不会因为
                // 磁盘满、没权限而抛出，所以能安全地归到「包的问题」一侧 ——
                // 让调用方换个包重传，而不是去查平台的磁盘
                throw new BadArtifactException(
                        "产物包解不开，可能不是 zip 或者传输被截断：" + e.getMessage());
            }
            // 零条目必须拒绝，不能存成一个空壳：ZipInputStream 读到<b>不是 zip</b> 的数据时，
            // 首次 getNextEntry() 就返回 null 而<b>不抛异常</b>，于是这里会「成功」地存下一个空目录、
            // 接口回 200 且该 buildId 进入 kept 与列表，而平台之后 find() 判它为空，
            // 对外说「这个构建没上传产物」。上传说成功、取用说没有 ——
            // 与「宁可 4xx 也不给一份静默错误的报告」是同一条原则。
            if (files == 0) {
                throw new BadArtifactException(
                        "产物包里一个文件都没有：它要么不是 zip，要么是个空包，要么只有目录没有内容");
            }
            // 旧产物<b>挪开</b>，而不是原地删。原地删是逐个 Files.delete，删到一半失败
            // （平台自己每 3 秒采集一轮，正读着这个目录下的 class；Windows 上还有只读位、
            // 文件被占用等等）就会留下一个<b>非空的残缺目录</b>：清理只删空目录、动不了它，
            // 而 find() 只看「存在且非空」，照样判它就绪 —— 平台接着拿不完整的字节码去解
            // 探针数据，算出的覆盖率界面上看不出任何异样。
            // 挪开是一次目录改名：要么整体成功、要么整体没动。于是 dir 永远只有
            // 「完整的旧产物」和「完整的新产物」两种状态，绝不会是两者的混合体或残缺体。
            if (Files.exists(dir)) {
                Path parked = dir.getParent().resolve(kind.dir() + ".old-" + System.nanoTime());
                moveAtomic(dir, parked);
                // 只有真的挪开了才记下来：挪不动时直接抛，此时 dir 原封不动
                aside = parked;
            }
            moveAtomic(tmp, dir);
        } catch (IOException | RuntimeException ex) {
            deleteTree(tmp);
            if (aside != null) {
                try {
                    // 换不进去就把旧产物原样搬回来：宁可留着旧版本，也不能让这个构建变成空的
                    moveAtomic(aside, dir);
                } catch (IOException restoreFailed) {
                    // 搬不回去必须让人看见：这个构建的产物暂时取不到，而原因不在上传的包里
                    ex.addSuppressed(new IOException(
                            "旧产物没能搬回原位，它现在在 " + aside + "，需要人工改回 " + dir, restoreFailed));
                }
            }
            // 上面的 Files.createDirectories(tmp) 顺带把 <buildId>/ 也建了出来。留着它的话，
            // builds() 会把这个空壳计入、mtime 还排在最前：CI 连推 N 次坏包，
            // 下一次成功上传触发的 prune 就把 N 个<b>真实构建</b>挤掉了 ——
            // 一个由「几次失败的上传」引发的故障，查起来完全不着边际。
            deleteIfEmpty(dir.getParent());
            throw ex;
        }
        // 换入成功之后，旧产物才可以删。这里用宽容删除：它已经不在服役路径上了，
        // 删不干净只是占点磁盘，不会让任何人拿到错的产物
        if (aside != null) {
            deleteTree(aside);
        }
        // 目录的 mtime 决定保留顺序，显式刷一下：换入过程中它可能没被更新。
        //
        // <b>刷不动也只能算了，绝不能让它把这次上传判成失败</b>：走到这里时新产物已经换入、
        // 旧产物已经删掉，这次上传<b>是成功的</b>。若任由异常冲出去，调用方会拿到
        // 500「平台侧故障」，CI 据此重推 —— 而磁盘上那份产物明明是好的。
        // 它真正的影响只在保留顺序上：mtime 偏旧可能让这个构建被提前淘汰，所以要留一行日志
        try {
            Files.setLastModifiedTime(dir.getParent(),
                    java.nio.file.attribute.FileTime.from(java.time.Instant.now()));
        } catch (IOException e) {
            log.warn("产物已存好，但刷新 {} 的修改时间失败：{}。"
                            + "这只影响保留顺序（可能比别的构建先被淘汰），不影响这次上传",
                    dir.getParent(), e.toString());
        }
        prune(projectId);
    }

    /**
     * 目录改名，被瞬时占用挡住时退避重试。
     *
     * <p><b>为什么需要重试</b>：Windows 上刚写完的文件会被实时扫描之类的进程短暂持有，
     * 此时给目录改名会失败，而抛出来的 {@code FileSystemException} 只有
     * 「源 -&gt; 目标」两个路径、<b>没有失败原因</b>，看不出是瞬时占用还是真出了问题。
     * 实测：连打 12 次首传随机失败 2 次（约 17%），单测一次都测不出来 ——
     * 临时目录里只有几个小文件，跑得比扫描快。CI 上 17% 的随机红，
     * 排查方向还会被引到「上传的包有问题」上去，而包是好的。
     *
     * <p><b>为什么上限很短</b>：占用是毫秒级的；而权限不足、磁盘满这类真实错误
     * 重试多少次也不会变好，拖长只会让接口迟迟不返回。所以退避到约 0.3s 就放弃，
     * 把最后一次的异常原样抛出去 —— 与探针那边「只重试被拒、不重试超时」同一个道理。
     *
     * <p>保留 {@link StandardCopyOption#ATOMIC_MOVE}：整套「挪开旧产物再换入」的正确性
     * 全靠这一步要么整体成功、要么整体没动。
     */
    private static void moveAtomic(Path src, Path dst) throws IOException {
        final int tries = 5;
        for (int i = 1; ; i++) {
            try {
                Files.move(src, dst, StandardCopyOption.ATOMIC_MOVE);
                return;
            } catch (IOException e) {
                if (i == tries) {
                    throw e;
                }
                try {
                    Thread.sleep(20L << (i - 1));   // 20 / 40 / 80 / 160ms
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    /** 取不到就是没上传过。返回空目录会被上游读成「这个构建没有代码」 */
    public Optional<Path> find(String projectId, String buildId, ArtifactKind kind) {
        requireValidBuildId(buildId);
        Path dir = projectDir(projectId).resolve(buildId).resolve(kind.dir());
        if (!Files.isDirectory(dir)) {
            return Optional.empty();
        }
        try (var s = Files.list(dir)) {
            return s.findAny().isPresent() ? Optional.of(dir) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * 按模式解析出这一轮归一化该用的产物路径。
     *
     * <p>{@code local} 模式原样返回入参 —— 现有的裸机部署走的就是这条，一步都不多做。
     *
     * <p><b>为什么返回的是一份配置、而不是几个路径</b>：三个 Analyzer 拿产物路径的方式
     * 并不一致。{@code CoverageAnalyzer}（Java）由外部传，而 {@code CppCoverageAnalyzer}
     * 与 {@code RustCoverageAnalyzer} 自己从 {@link ProjectConfig} 读，压根没有接收路径的入参。
     * 所以换的是喂给它们的那份配置，而不是给它们加一个参数（见 spec §4.2 的 2026-09-03 修正）。
     *
     * <p><b>但「换了配置」不等于「它们能收到」。</b>这两个 Analyzer 是在
     * {@code ProjectRuntimeFactory.create} 里一次性造好、存成 {@code ProjectRuntime} 的
     * final 字段的，而 buildId 每轮采集才知道 —— 所以 uploaded 模式下 {@code ProjectRuntime}
     * 必须用这里返回的配置<b>重造</b>它们，否则会出现「Java 用解压出来的产物、
     * C++/Rust 用本机路径」的混合报告，行号错位且界面上看不出。
     *
     * <p><b>{@code needed} 是「这一轮真有实例的那几种语言」，不是「配置里填了路径的那几种」。</b>
     * 后者会把「C++ 两台都掉线」变成整轮 ANALYZE_ERROR：那一轮 {@code cppDumps} 为空、
     * C++ 归一化根本不跑，却因为「缺 cpp 产物」连累其余语言一起没有报告 ——
     * 而平台的既定行为是少几台就降级成 PARTIAL、照常出报告。Go 永远不在这个集合里，
     * 它的覆盖数据是自包含的（见 {@link ArtifactKind}）。
     *
     * @throws IOException 取不到产物、没有构建版本、或构建是脏的。<b>一律拒绝，不降级不跳过</b>：
     *                     跳过那门语言的话界面上表现为「这些代码没被调用过」，与真相正相反，
     *                     而且看不出是缺产物
     */
    public ProjectConfig resolveInto(ProjectConfig cfg, BuildVersion version,
                                     Set<ArtifactKind> needed) throws IOException {
        // 先判这个值合不合法，再判它是不是 uploaded。
        //
        // <b>为什么这里还要再校验一遍</b>：ProjectRegistry.validate 只挂在 create / update 上，
        // 而本项目改配置的正规方式之一是<b>直接改库里那份 JSON</b>（见 CLAUDE.md §三）——
        // 从那条路进来的值不过 validate，yml 种子也不过。而 usesUploadedArtifacts()
        // 判的是「等不等于 uploaded」，于是 upload、uploded、带空格的值统统被当成 local：
        // 容器化部署上打错一个字母，平台就拿本机路径的产物去解另一个 buildId 的探针数据。
        // 堵在<b>使用点</b>才堵得全 —— 入口有好几个，用的地方只有这一个
        String source = cfg.getArtifactSource();
        if (!"local".equalsIgnoreCase(source) && !"uploaded".equalsIgnoreCase(source)) {
            throw new IOException("产物来源只能是 local（用配置里的本地路径）"
                    + "或 uploaded（按 buildId 从产物仓库取），实际为：" + source
                    + "。不认识的值不会被当成 local —— 那会拿本机产物去解另一个构建的探针数据");
        }
        if (!cfg.usesUploadedArtifacts()) {
            return cfg;
        }
        if (needed.isEmpty()) {
            // 这一轮一个产物都用不上（纯 Go 项目，或这一轮只有 Go 实例连着）。
            // 版本闸门必须排在这个判断<b>之后</b>：否则一个压根不需要产物的项目，
            // 会因为「工作树脏」或「实例没配 sessionid」整轮打成 ANALYZE_ERROR ——
            // 与上面「只解析真有实例的语言」是同一条理由，少做一步就漏了这个口子
            return cfg;
        }
        if (version == null) {
            // 不是边界情况：实例没配 sessionid 是平台明确支持的降级态（只是增量不可用），
            // 实例之间版本不一致时 unifiedVersion 同样给 null。
            // 这两种情况下「该取哪一份产物」这个前提不成立，只能拒绝，不能挑一个默认的
            throw new IOException("产物按构建版本索引，但拿不到统一的构建版本："
                    + "要么这些实例没上报（Java 的 sessionid / 其余语言的 COVERAGE_BUILD_ID），"
                    + "要么各实例报的版本不一致 —— 无从知道该取哪一份产物");
        }
        if (version.dirty()) {
            // 剥掉 -dirty 去取干净 commit 的产物，正是 requireValidBuildId 在上传侧
            // 拒绝 -dirty 所要防的那件事：拿干净构建的字节码解脏字节码的探针数据
            throw new IOException("被测实例跑的是未提交的改动（" + version.commit()
                    + "-dirty），产物仓库里不会有与之对应的那一份：工作树脏时同一个 commit"
                    + "能对应无数份不同的产物。按干净 commit 取回的产物解脏字节码的探针数据，"
                    + "行号会错位且看不出来");
        }
        ProjectConfig copy = cfg.copy();
        for (ArtifactKind kind : needed) {
            Path dir = require(cfg.getId(), version.commit(), kind);
            switch (kind) {
                case JAVA -> copy.setClassesDir(dir.toString());
                case CPP -> copy.setCppObjectsDir(dir.toString());
                // Rust 要的是产物文件本身，不是目录
                case RUST -> copy.setRustBinary(theOnlyFileIn(dir, version.commit()).toString());
            }
        }
        return copy;
    }

    /**
     * 取不到就是没上传过这个构建的这门语言的产物。消息里要同时有 buildId、语言和补救办法。
     *
     * <p><b>补救命令必须带上 {@code ?project=}</b>：上传接口的 project 默认是 {@code default}，
     * 非默认项目照着一条不带它的命令做，包会传进 default 项目、接口还回 200，
     * 下一轮采集仍报这同一句话 —— 照提示做了却没用，而且看不出为什么。
     */
    private Path require(String projectId, String buildId, ArtifactKind kind) throws IOException {
        return find(projectId, buildId, kind).orElseThrow(() -> new IOException(
                "缺少构建 " + buildId + " 的 " + kind.dir() + " 产物。请在构建后调 "
                        + "POST /api/artifacts/" + buildId + "?project=" + projectId
                        + "&lang=" + kind.dir()
                        + " 把它推上来 —— 没有它解不出行号，而跳过这门语言会让界面显示成"
                        + "「这些代码没被调用过」，与真相正相反"));
    }

    /**
     * Rust 产物包里只该有那一个带 coverage mapping 的可执行文件。
     *
     * <p><b>刻意不是「取第一个」</b>：目录列举的顺序没有任何保证，包里混进
     * {@code .pdb} / {@code .d} 之类时会随机选中一个错的，而 llvm-cov 拿到非产物文件后
     * 报的错离真正的原因很远。多于一个就说清楚该怎么改。
     *
     * <p><b>而且要递归找</b>，不能只看顶层：{@link #save} 统计条目时把嵌套路径下的文件
     * 一并算进去了，所以 {@code zip -r target/release/demo} 打的包上传会成功；
     * 这里若只看顶层就会说「一个文件都没有」—— 又一次「上传说成功、取用说没有」，
     * 正是这个类反复要消灭的那种自相矛盾。
     */
    private static Path theOnlyFileIn(Path dir, String buildId) throws IOException {
        List<Path> files;
        try (var s = Files.walk(dir)) {
            files = s.filter(Files::isRegularFile).sorted().toList();
        }
        if (files.size() == 1) {
            return files.get(0);
        }
        if (files.isEmpty()) {
            throw new IOException("构建 " + buildId + " 的 rust 产物目录里一个文件都没有");
        }
        throw new IOException("构建 " + buildId + " 的 rust 产物包里有 " + files.size() + " 个文件（"
                + files.stream().map(p -> dir.relativize(p).toString()).collect(Collectors.joining("、"))
                + "），无法确定哪个才是带 coverage mapping 的产物；"
                + "rust 产物包里只放那一个可执行文件");
    }

    /** 这个项目存过哪些构建，新的在前 */
    public List<String> builds(String projectId) {
        return dirsUnder(projectId).stream().filter(this::isValidBuildId).toList();
    }

    /**
     * 磁盘上那些<b>不是</b> buildId 的目录名：手工建的、别的工具落下的。
     *
     * <p>它们必须与真正的构建<b>分开对待</b>，否则两头都出错：算进 {@link #builds} 就会占掉
     * {@code keep} 名额（keep=10、盘上有 3 个陌生目录，就只留得下 7 份真产物），
     * 而 {@link #prune} 反过来又会把运维放在那里的东西悄悄删掉。
     */
    public List<String> strangers(String projectId) {
        return dirsUnder(projectId).stream().filter(b -> !isValidBuildId(b)).toList();
    }

    /** 项目目录下的全部子目录名，新的排前面 */
    private List<String> dirsUnder(String projectId) {
        Path p = projectDir(projectId);
        if (!Files.isDirectory(p)) {
            return List.of();
        }
        try (var s = Files.list(p)) {
            return s.filter(Files::isDirectory)
                    .sorted(Comparator.comparingLong((Path d) -> d.toFile().lastModified()).reversed())
                    .map(d -> d.getFileName().toString())
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /**
     * 删掉超出 {@link #keep} 的最旧构建，返回删了几个。
     *
     * <p><b>按个数而不是按天数</b>：一个长期不发布的服务会把自己正在跑的那份产物清掉，
     * 而那时平台会开始拒绝出报告 —— 一个由「太久没发版」引发的故障，没人查得到。
     */
    public int prune(String projectId) {
        List<String> all = builds(projectId);
        int removed = 0;
        for (int i = keep; i < all.size(); i++) {
            deleteTree(projectDir(projectId).resolve(all.get(i)));
            removed++;
        }
        return removed;
    }

    /** 删掉一个构建的全部产物 */
    public void remove(String projectId, String buildId) throws IOException {
        requireValidBuildId(buildId);
        Path dir = projectDir(projectId).resolve(buildId);
        deleteTree(dir);
        // 删不干净必须报错，不能照样回 ok:true。deleteTree 是宽容删除（单个文件删不掉就跳过），
        // 留下的残缺目录会被 find() 判为就绪 —— 平台接着拿不完整的字节码去算覆盖率，
        // 算出的结果界面上看不出异样。这正是 save() 这轮消灭掉的失败模式，
        // 不能在删除这条路上原样留着。prune() 那边仍用宽容删除：它删的是已经过期的产物，
        // 少删一个只占点磁盘，下一轮会再试 —— 两处语义不同，不要统一。
        if (Files.exists(dir)) {
            throw new IOException("产物没有删干净，残留在 " + dir
                    + "：残留会被当成一份就绪的产物用，需要人工清掉");
        }
    }

    /**
     * 只删空目录，非空就原样留着 —— {@link Files#deleteIfExists} 对非空目录会抛
     * {@code DirectoryNotEmptyException}，正好就是这里要的语义。
     *
     * <p><b>为什么必须「只删空的」</b>：同一个 buildId 下可能已经有别的语言的正常产物
     * （java 传成功了、cpp 这次传坏了），把整个 {@code <buildId>/} 删掉就是拿一次失败的上传
     * 去毁掉一份还在服役的好产物。
     */
    private static void deleteIfEmpty(Path dir) {
        try {
            Files.deleteIfExists(dir);
        } catch (IOException ignored) {
            // 非空、或者删不掉，都不该盖掉调用方真正要抛的那个失败原因
        }
    }

    /**
     * 宽容删除：单个文件删不掉不该让整次操作失败。
     *
     * <p>用在三处，共同点是<b>删不干净也不会让任何人拿到错的产物</b> ——
     * {@link #prune} 删的是要退休的旧构建（下一轮还会再试）、{@code save()} 失败时清临时目录、
     * 以及换入成功后清那份已经挪开的旧产物。
     *
     * <p>注意 {@code save()} 的<b>换入</b>刻意不走这里：那一步要么整体成功、要么整体没动
     * （靠目录改名做到），因为「删了一半」正好会造出一个 {@link #find} 判为就绪的残缺产物。
     */
    private static void deleteTree(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 单个文件删不掉不该让整次操作失败：下次 prune 会再试
                }
            });
        } catch (IOException ignored) {
            // 同上
        }
    }
}
