package com.rtcc.platform.artifact;

import com.rtcc.platform.config.ProjectConfig;
import com.rtcc.platform.model.BuildVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 按模式解析产物路径。<b>本次只接通 local</b>：uploaded 当场拒绝，
 * 「把路径换成按 buildId 解压出来的目录」随下一个提交连同它的用例一起补。
 *
 * <p>换的是喂给 Analyzer 的那份配置、而不是 Analyzer 本身，理由见
 * {@link ArtifactStore#resolveInto} 的 javadoc —— 那里也写明了接 uploaded 时
 * 还要做哪几件事（重造 C++/Rust 的 Analyzer、拒绝 null 与 dirty 版本）。
 */
class ArtifactResolveTest {

    private static final String SHA = "77842897548da30523c688d97389c6d33e84a2d5";

    private static ProjectConfig cfg(String source) {
        ProjectConfig c = new ProjectConfig();
        c.setId("demo");
        c.setArtifactSource(source);
        c.setClassesDir("原来的/classes");
        c.setCppObjectsDir("原来的/obj");
        c.setRustBinary("原来的/svc.exe");
        return c;
    }

    /** local 模式下必须原样返回 —— 现有的裸机部署与 8 实例验收链路走的都是这条 */
    @Test
    void 本地模式原样返回(@TempDir Path root) throws Exception {
        ArtifactStore store = new ArtifactStore(root, 10);
        ProjectConfig in = cfg("local");

        ProjectConfig out = store.resolveInto(in, new BuildVersion(SHA, false));

        assertSame(in, out, "local 模式不该复制配置，更不该改路径");
    }

    /**
     * 传进来的那份配置<b>一个字段都不许就地改</b>。
     *
     * <p>它不是副本：{@code ProjectRuntime} 持有的 props 就是 {@code ProjectRegistry}
     * 注册表里那个活对象，也是 {@code GET /api/projects} 原样吐回去的那一份。
     * 就地改产物路径的话，用户在项目设置里会看到平台内部解压出来的临时目录，
     * 而且改一次污染到底 —— 下一轮采集拿到的已经是被改过的「原始配置」。
     */
    @Test
    void 不就地改入参(@TempDir Path root) throws Exception {
        ArtifactStore store = new ArtifactStore(root, 10);
        ProjectConfig in = cfg("local");

        store.resolveInto(in, new BuildVersion(SHA, false));

        assertEquals("原来的/classes", in.getClassesDir());
        assertEquals("原来的/obj", in.getCppObjectsDir());
        assertEquals("原来的/svc.exe", in.getRustBinary());
    }

    /**
     * uploaded 已经被配置校验放行（见 ProjectValidationTest 的「合法的产物来源放行」），
     * 而这条路还没接通。中间态若原样返回配置，平台就会拿<b>本机路径</b>的产物去解
     * 另一个 buildId 的探针数据 —— 行号错位、probeStatus 照样是 CONNECTED、界面上看不出异样，
     * 正是上一个提交（「产物来源填错当场拒绝」）刚论证过的那族问题换了个入口。
     *
     * <p>大小写两种写法都要拒绝：{@code usesUploadedArtifacts()} 本来就是
     * {@code equalsIgnoreCase}，只堵小写那个等于没堵。
     *
     * <p>这条用例的寿命到 uploaded 接通为止，届时改成断言三条产物路径真被换掉。
     */
    @Test
    void 上传模式尚未接通时当场拒绝而不是静默退回本地(@TempDir Path root) {
        ArtifactStore store = new ArtifactStore(root, 10);

        for (String source : List.of("uploaded", "UPLOADED")) {
            IOException e = assertThrows(IOException.class,
                    () -> store.resolveInto(cfg(source), new BuildVersion(SHA, false)),
                    "artifactSource=" + source + " 没被拦住，会静默退回 local");
            assertTrue(e.getMessage().contains("uploaded"), e.getMessage());
        }
    }
}
