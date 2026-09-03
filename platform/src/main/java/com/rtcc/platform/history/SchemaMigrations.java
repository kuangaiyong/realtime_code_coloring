package com.rtcc.platform.history;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLSyntaxErrorException;

/**
 * 只跑一次的数据修正。
 *
 * <p><b>为什么需要：</b>历史时间列曾经用 {@code NOW(3)} 写入 —— 那是<b>数据库服务端的
 * 本地墙钟</b>；而 JDBC 连接串写着 {@code serverTimezone=UTC}，驱动读回时把那个墙钟
 * 当成 UTC。两端对同一个值的解释差了一整个时区偏移量，于是
 * {@code rs.getTimestamp(...).toInstant()} 交出的时刻凭空往后跳了 8 小时（本机 +08:00）。
 *
 * <p>页面上表现为采集事件的持续时长是<b>负数</b>（{@code -28672 秒（至今）}），
 * 因为事件时间戳比浏览器的「现在」还晚。趋势表是同样的写法、同样的偏移，
 * 只是没人拿趋势点跟「现在」比，所以一直没被发现。
 *
 * <p>写入侧已改为 {@code UTC_TIMESTAMP(3)}，与 {@code serverTimezone=UTC} 自洽。
 * 但<b>库里已有的行仍是本地墙钟</b>，不修正的话新旧混在一起，
 * 趋势曲线会在切换那一刻出现一个凭空的台阶，而没人知道那是什么。
 *
 * <p><b>偏移量必须问数据库自己，不能写死 8 小时。</b>写死的话，这份代码部署到
 * 别的时区就会把数据改错 —— 而且改完看起来一切正常，是这个项目最不能接受的那种错。
 */
final class SchemaMigrations {

    private static final Logger log = LoggerFactory.getLogger(SchemaMigrations.class);

    /**
     * 改一次数据就换一个 id；跑过的不再跑。
     *
     * <p><b>{@code schema_migration} 表与两张历史表同生共死，不能单独删。</b>
     * 删掉标记再启动，这个修正会<b>再减一次</b>，把已经正确的时间又改错 ——
     * 而改完看起来仍然正常（只是整体早了一个时区），没有任何地方会报错。
     * 备份与恢复必须带上这张表。
     *
     * <p>为什么不改成「看数据像不像已经修过」来判断：那个判据只在东半球成立。
     * 西半球（如 UTC-5）的偏移是负的，未修正的旧数据比真实时刻<b>更早</b>而不是更晚，
     * 「没有超前的行」会被误判成「已经修过了」，于是该修的永远不修。
     * 标记表是唯一可靠的判据。
     */
    private static final String UTC_FIX = "2026-09-02-utc-timestamps";

    private SchemaMigrations() {
    }

    /**
     * 把两张历史表里用本地墙钟写下的时间修正为 UTC。幂等：靠 {@code schema_migration}
     * 表记录跑过没有，重启多少次都只生效一次。
     *
     * <p>失败不抛：数据库不可用时平台照常采集与染色（与两张历史表本身同一条降级原则）。
     * 修正没跑成，下次启动会再试。
     */
    static void fixUtcTimestamps(JdbcTemplate jdbc) {
        try {
            // DDL 在 MySQL 里是隐式提交的，放不进下面那个事务，所以先单独建表
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS schema_migration (
                      id      VARCHAR(64) NOT NULL PRIMARY KEY,
                      done_at DATETIME(3) NOT NULL
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);
            Integer done = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM schema_migration WHERE id = ?", Integer.class, UTC_FIX);
            if (done != null && done > 0) {
                return;
            }

            // 问数据库自己偏了多少。<b>按分钟而不是按小时</b>：印度 +05:30、
            // 尼泊尔 +05:45 这类半小时时区，按小时取整会剩下 30 / 45 分钟不修正，
            // 而那点残差同样让「持续时长」算出负数
            Integer offset = jdbc.queryForObject(
                    "SELECT TIMESTAMPDIFF(MINUTE, UTC_TIMESTAMP(), NOW())", Integer.class);
            int minutes = offset == null ? 0 : offset;
            if (minutes == 0) {
                // 服务端本来就跑在 UTC 上，没有旧数据要修 —— 但标记仍要落，
                // 免得每次启动都重新算一遍
                jdbc.update("INSERT INTO schema_migration (id, done_at) VALUES (?, UTC_TIMESTAMP(3))",
                        UTC_FIX);
                return;
            }

            // <b>修正与标记必须原子。</b>「紧跟其后」不等于原子：UPDATE 成功而 INSERT 标记
            // 失败（进程被杀、连接断、磁盘满）时，下次启动会<b>再减一次</b>，
            // 数据被改错两次而时间看起来仍然正常 —— 完全不可见，正是本项目最忌讳的那种错。
            //
            // 一条真实的触发路径：build_coverage 表可能压根不存在（一个从未产生过
            // 干净构建的部署，CoverageHistory 从没建过表），那条 UPDATE 一抛，
            // collect_event 却已经减过了。
            int[] rows = jdbc.execute((ConnectionCallback<int[]>) con -> {
                boolean auto = con.getAutoCommit();
                con.setAutoCommit(false);
                try {
                    int ev = updateIfExists(con, """
                            UPDATE collect_event SET at = DATE_SUB(at, INTERVAL ? MINUTE)
                            """, minutes);
                    int bd = updateIfExists(con, """
                            UPDATE build_coverage
                               SET first_seen_at = DATE_SUB(first_seen_at, INTERVAL ? MINUTE),
                                   peak_at       = DATE_SUB(peak_at, INTERVAL ? MINUTE)
                            """, minutes, minutes);
                    try (PreparedStatement ps = con.prepareStatement(
                            "INSERT INTO schema_migration (id, done_at) VALUES (?, UTC_TIMESTAMP(3))")) {
                        ps.setString(1, UTC_FIX);
                        ps.executeUpdate();
                    }
                    con.commit();
                    return new int[]{ev, bd};
                } catch (SQLException e) {
                    con.rollback();
                    throw e;
                } finally {
                    con.setAutoCommit(auto);
                }
            });
            log.info("历史时间已由本地墙钟修正为 UTC（偏移 {} 分钟）：采集事件 {} 行、构建趋势 {} 行",
                    minutes, rows[0], rows[1]);
        } catch (Exception e) {
            // 数据库整个不可用时走这里。修正没跑成，下次启动会再试 ——
            // 而事务保证了「要么全做要么全不做」，重试不会重复减
            log.debug("时间修正未执行（下次启动会再试）：{}", e.toString());
        }
    }

    /**
     * 表不存在就当作 0 行，不让它掀翻整个事务。
     *
     * <p>两张历史表是各自懒建的（数据库没起也要让平台起来），所以完全可能只有一张在。
     * 这时另一张的「没有旧数据要修」是<b>事实</b>，不是错误 —— 若因此回滚，
     * 这个部署的时间就永远修不好了。
     */
    private static int updateIfExists(Connection con, String sql, int... params) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setInt(i + 1, params[i]);
            }
            return ps.executeUpdate();
        } catch (SQLSyntaxErrorException e) {
            // 1146 = ER_NO_SUCH_TABLE。只吞这一种，其余照抛 ——
            // 权限不足、磁盘满这些必须让事务回滚，否则又回到「减了一半」的老问题
            if (e.getErrorCode() == 1146) {
                return 0;
            }
            throw e;
        }
    }
}
