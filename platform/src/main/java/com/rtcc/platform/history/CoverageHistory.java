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
 * 覆盖率历史：每个构建一行，用于跨构建趋势。
 *
 * 三个刻意的取舍：
 *
 * 1. **只记干净构建**。工作树脏时 commit 标识不了这份代码，记进去就是噪声，
 *    而且同一个 commit 会对应多份不同的字节码。宁可不记，不记错。
 * 2. **存峰值而不是末值**。「清零计数器」会把当前值打到 0，若存末值，
 *    趋势图上会出现一段根本不存在的「覆盖率回退」。峰值才回答
 *    「这个构建最终被测到了多少」。
 * 3. **数据库不可用不能拖垮平台**。写入失败只记一次日志并把原因留下，
 *    采集与染色照常；趋势接口据此明确报错，而不是回一张空图
 *    —— 空图会被读成「这个项目一直没有覆盖」。
 */
@Component
public class CoverageHistory {

    private static final Logger log = LoggerFactory.getLogger(CoverageHistory.class);

    private final JdbcTemplate jdbc;
    private volatile String unavailable;
    private volatile boolean ready;

    public CoverageHistory(DataSource dataSource) {
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
                        CREATE TABLE IF NOT EXISTS build_coverage (
                          project_id    VARCHAR(64)  NOT NULL DEFAULT 'default',
                          build_commit  CHAR(40)     NOT NULL,
                          first_seen_at DATETIME(3)  NOT NULL,
                          peak_at       DATETIME(3)  NOT NULL,
                          overall_ratio DECIMAL(5,2) NOT NULL,
                          covered_lines INT          NOT NULL,
                          missed_lines  INT          NOT NULL,
                          file_count    INT          NOT NULL,
                          PRIMARY KEY (project_id, build_commit)
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                        """);
                migrateProjectId();
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
     * 给多项目之前建的表补上 project_id。既有记录按 DEFAULT 归入默认项目 ——
     * 它们本来就是那一个项目产生的，趋势图不会断。
     *
     * <p>MySQL 没有 {@code ADD COLUMN IF NOT EXISTS}，所以先查一次列在不在。
     * 三个动作放进同一条 ALTER：分开执行的话，中途失败会留下一张
     * 「有 project_id 但主键还是单列 commit」的表，两个项目照样互相覆盖，
     * 而且看不出问题出在哪。
     */
    private void migrateProjectId() {
        Integer exists = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = 'build_coverage' AND column_name = 'project_id'
                """, Integer.class);
        if (exists != null && exists == 0) {
            jdbc.execute("""
                    ALTER TABLE build_coverage
                      ADD COLUMN project_id VARCHAR(64) NOT NULL DEFAULT 'default' FIRST,
                      DROP PRIMARY KEY,
                      ADD PRIMARY KEY (project_id, build_commit)
                    """);
            log.info("build_coverage 已加上 project_id，既有记录归入项目 default");
        }
    }

    /**
     * 记下这个构建的覆盖峰值。已有记录时只在覆盖行数更多时才更新 ——
     * 清零之后的低值不该把已经测到的成绩抹掉。
     *
     * <p>按项目分区：两个项目盯的可能是同一个仓库的同一个 commit，
     * 不带项目维度的话它们会写进同一行互相覆盖，趋势图上串成一条。
     */
    public void record(String projectId, String buildCommit, double overallRatio,
                       int coveredLines, int missedLines, int fileCount) {
        if (buildCommit == null || !ensureReady()) {
            return;
        }
        try {
            jdbc.update("""
                    INSERT INTO build_coverage
                      (project_id, build_commit, first_seen_at, peak_at, overall_ratio,
                       covered_lines, missed_lines, file_count)
                    VALUES (?, ?, NOW(3), NOW(3), ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE
                      peak_at       = IF(VALUES(covered_lines) > covered_lines, NOW(3), peak_at),
                      overall_ratio = IF(VALUES(covered_lines) > covered_lines, VALUES(overall_ratio), overall_ratio),
                      missed_lines  = IF(VALUES(covered_lines) > covered_lines, VALUES(missed_lines), missed_lines),
                      file_count    = IF(VALUES(covered_lines) > covered_lines, VALUES(file_count), file_count),
                      covered_lines = GREATEST(covered_lines, VALUES(covered_lines))
                    """, projectId, buildCommit, overallRatio, coveredLines, missedLines, fileCount);
            unavailable = null;
        } catch (Exception e) {
            // 每轮采集都会走到这里，连不上时不能每 3 秒刷一条 ERROR
            if (unavailable == null) {
                log.error("覆盖率历史写入失败，趋势将不可用（采集与染色不受影响）：{}", describe(e));
            }
            unavailable = describe(e);
            ready = false;
        }
    }

    /** 按时间正序返回这个项目最近若干个构建，供趋势图使用 */
    public Map<String, Object> recent(String projectId, int limit) {
        Map<String, Object> res = new LinkedHashMap<>();
        if (!ensureReady()) {
            res.put("available", false);
            res.put("error", unavailable);
            res.put("builds", List.of());
            return res;
        }
        try {
            List<Map<String, Object>> rows = jdbc.query("""
                    SELECT build_commit, peak_at, overall_ratio, covered_lines, missed_lines, file_count
                    FROM (
                      SELECT * FROM build_coverage
                      WHERE project_id = ? ORDER BY peak_at DESC LIMIT ?
                    ) t ORDER BY peak_at ASC
                    """,
                    (rs, i) -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("buildCommit", rs.getString("build_commit"));
                        m.put("peakAt", rs.getTimestamp("peak_at").toInstant().toString());
                        m.put("overallRatio", rs.getBigDecimal("overall_ratio").doubleValue());
                        m.put("coveredLines", rs.getInt("covered_lines"));
                        m.put("missedLines", rs.getInt("missed_lines"));
                        m.put("fileCount", rs.getInt("file_count"));
                        return m;
                    }, projectId, limit);
            res.put("available", true);
            res.put("error", null);
            res.put("builds", new ArrayList<>(rows));
            return res;
        } catch (Exception e) {
            ready = false;
            unavailable = describe(e);
            res.put("available", false);
            res.put("error", unavailable);
            res.put("builds", List.of());
            return res;
        }
    }

    private static String describe(Exception e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }
}
