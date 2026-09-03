package com.rtcc.platform.history;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 采集状态的变化事件。回答「昨天半夜那次为什么没数据」。
 *
 * <p><b>为什么需要：</b>平台此前只有一个 {@code lastError} 挂在当前快照上，
 * 下一轮采集成功就被冲掉了。掉线、版本冲突、产物目录被删 —— 这些事发生过又恢复了，
 * 事后一点痕迹都没有，而覆盖率数字上的那个坑还在，没人解释得了。
 *
 * <p><b>只在状态<i>变化</i>时落一条，不是每轮一条。</b>采集是 3 秒一轮的常驻轮询，
 * 每轮一条的话一天 28800 行，既灌爆表也淹没真正有用的那几条。
 * 「连上了」「掉了」「又连上了」这三条才是人要看的；中间稳定的那几千轮没有信息量。
 *
 * <p>与 {@link CoverageHistory} 同一条降级原则：<b>数据库不可用不能拖垮平台</b>。
 * 写不进去只记一次日志，采集与染色照常；查询接口据此明确报错，
 * 而不是回一张空列表 —— 空列表会被读成「这个项目一直很健康」。
 */
@Component
public class CollectEvents {

    private static final Logger log = LoggerFactory.getLogger(CollectEvents.class);

    /** 说明文字的存储上限。探针报错可能很长，截断比让整条 INSERT 失败强 */
    private static final int DETAIL_MAX = 900;

    /**
     * 实例列表的存储上限，与建表里的 VARCHAR(500) 对齐。
     *
     * <p><b>必须按逗号边界截，不能直接 substring。</b>拦腰截断会留下
     * {@code rust://some-host:6} 这样的半截地址 —— 它会照常进筛选下拉，
     * 看起来像一台真实存在的实例，而按它筛什么都筛不出来。
     * 八个实例约 175 字符，这个上限只有在几十台的部署上才会碰到。
     */
    private static final int INSTANCES_MAX = 500;

    private final JdbcTemplate jdbc;
    private volatile String unavailable;
    private volatile boolean ready;

    public CollectEvents(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** 建表。启动时不建 —— 数据库没起也要让平台起来，所以推迟到第一次真正要用时 */
    private boolean ensureReady() {
        if (ready) {
            return true;
        }
        synchronized (this) {
            if (ready) {
                return true;
            }
            try {
                jdbc.execute("""
                        CREATE TABLE IF NOT EXISTS collect_event (
                          id         BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
                          project_id VARCHAR(64)  NOT NULL,
                          at         DATETIME(3)  NOT NULL,
                          status     VARCHAR(32)  NOT NULL,
                          detail     VARCHAR(1000),
                          instances  VARCHAR(500),
                          INDEX idx_project_at (project_id, at)
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                        """);
                // 老库补列。detail 里一直拼着实例名，但那是给人读的一段话 ——
                // 拿它筛选就得反过来解析文本，提示语一改就抽不出来了
                try {
                    jdbc.execute("ALTER TABLE collect_event ADD COLUMN instances VARCHAR(500)");
                } catch (Exception e) {
                    // 绝大多数情况是「列已存在」—— MySQL 8.0 的 ADD COLUMN 不支持
                    // IF NOT EXISTS，只能这么判。但权限不足之类的真失败也会走到这里，
                    // 完全无声的话，后面 INSERT 报「没有 instances 列」时查不到根因
                    log.debug("补 instances 列未执行（列已存在时属正常）：{}", e.toString());
                }
                SchemaMigrations.fixUtcTimestamps(jdbc);
                ready = true;
                unavailable = null;
                return true;
            } catch (Exception e) {
                unavailable = describe(e);
                return false;
            }
        }
    }

    /**
     * 记一条状态变化。
     *
     * @param status    变化<b>之后</b>的采集状态（CONNECTED / PARTIAL / DISCONNECTED / …）
     * @param detail    为什么。恢复正常时可以为 null
     * @param instances 这次是<b>哪几台</b>出了问题。恢复正常时为空。
     *                  单独存一列而不是只拼进 detail：detail 是给人读的一段话，
     *                  拿它筛选就得反过来解析文本，提示语一改就抽不出来了
     */
    public void record(String projectId, String status, String detail, List<String> instances) {
        if (!ensureReady()) {
            return;
        }
        try {
            // UTC_TIMESTAMP 而不是 NOW：NOW 给的是服务端本地墙钟，而连接串写着
            // serverTimezone=UTC，驱动会把那个墙钟当 UTC 读回来 —— 两端差一整个时区偏移，
            // 页面上表现为持续时长是负数。UTC 也是唯一不随部署机器变的基准
            jdbc.update("""
                    INSERT INTO collect_event (project_id, at, status, detail, instances)
                    VALUES (?, UTC_TIMESTAMP(3), ?, ?, ?)
                    """,
                    projectId, status,
                    detail == null ? null
                            : detail.length() > DETAIL_MAX ? detail.substring(0, DETAIL_MAX) + "…" : detail,
                    joinCapped(instances));
        } catch (Exception e) {
            // 记不进去不该影响采集本身。标记不可用，查询接口会照实说
            ready = false;
            unavailable = describe(e);
            log.warn("采集事件写入失败，事件历史将不可用：{}", unavailable);
        }
    }

    /**
     * 拼成一列，超长时<b>按完整的实例边界</b>丢掉尾部几个，而不是拦腰截断。
     * 宁可少记几台，也不能记出一个不存在的地址。
     *
     * <p>可见性放开到包级，是为了让单测能直接喂几十个长地址进来 ——
     * 这条走真实数据库的话，得先攒出一个几十实例的项目才碰得到上限。
     */
    static String joinCapped(List<String> instances) {
        if (instances == null || instances.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (String ep : instances) {
            if (ep == null || ep.isBlank()) {
                continue;
            }
            int add = sb.isEmpty() ? ep.length() : ep.length() + 1;
            if (sb.length() + add > INSTANCES_MAX) {
                break;
            }
            if (!sb.isEmpty()) {
                sb.append(',');
            }
            sb.append(ep);
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    /** 最近若干条，新的在前 —— 出问题时人先看最近发生了什么 */
    public Map<String, Object> recent(String projectId, int limit) {
        Map<String, Object> res = new LinkedHashMap<>();
        if (!ensureReady()) {
            res.put("available", false);
            res.put("error", unavailable);
            res.put("events", List.of());
            return res;
        }
        try {
            List<Map<String, Object>> rows = jdbc.query("""
                    SELECT at, status, detail, instances FROM collect_event
                    WHERE project_id = ? ORDER BY at DESC, id DESC LIMIT ?
                    """,
                    (rs, i) -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("at", rs.getTimestamp("at").toInstant().toString());
                        m.put("status", rs.getString("status"));
                        m.put("detail", rs.getString("detail"));
                        // 旧行这一列是 NULL（那时还没有它）—— 给空列表而不是 null，
                        // 前端就不必为「老数据」单开一条分支
                        String ins = rs.getString("instances");
                        m.put("instances", ins == null || ins.isBlank()
                                ? List.of() : List.of(ins.split(",")));
                        return m;
                    }, projectId, limit);
            res.put("available", true);
            res.put("error", null);
            res.put("events", new ArrayList<>(rows));
            return res;
        } catch (Exception e) {
            ready = false;
            unavailable = describe(e);
            res.put("available", false);
            res.put("error", unavailable);
            res.put("events", List.of());
            return res;
        }
    }

    /** 项目删了，它的事件也没有留着的理由 */
    public void forget(String projectId) {
        if (!ensureReady()) {
            return;
        }
        try {
            jdbc.update("DELETE FROM collect_event WHERE project_id = ?", projectId);
        } catch (Exception e) {
            log.warn("删除项目 {} 的采集事件失败：{}", projectId, describe(e));
        }
    }

    private static String describe(Exception e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }
}
