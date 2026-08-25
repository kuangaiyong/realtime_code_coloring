package com.rtcc.platform.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;

/**
 * 项目配置的落库。整份配置以 JSON 存一列，而不是拆成二十个列 ——
 * 每接入一种新语言就要多两三个配置项，拆列意味着每次都改表结构。
 *
 * <p><b>数据库不可用时不能拖垮平台。</b>配置是采集、染色、门禁的前提，
 * 把它压在数据库这条附加设施上，等于让平台的核心能力随数据库一起挂。
 * 因此读不到就用 application.yml 里的那份在内存里跑，只有「保存配置」明确报错
 * —— 与覆盖率历史的取舍是同一条原则（见 {@code CoverageHistory}）。
 */
@Component
public class ProjectStore {

    private static final Logger log = LoggerFactory.getLogger(ProjectStore.class);

    private final JdbcTemplate jdbc;
    /**
     * 认不出的字段直接忽略。整份配置存一列 JSON 图的就是「加字段不用改表」，
     * 若新版本加的字段能让旧版本读不出整个项目，这个好处就抵消掉了 ——
     * 表现还特别隐蔽：项目被跳过，库里明明有行却走进「表为空」去插种子。
     */
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public ProjectStore(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /**
     * 装载全部项目配置。表为空时把 yml 里的那份写进去作为种子，
     * 于是现有部署升级后行为完全不变：还是同一个项目、同一套配置。
     */
    public List<ProjectConfig> loadAll(ProjectConfig seed) {
        try {
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS project (
                      id          VARCHAR(64)  NOT NULL PRIMARY KEY,
                      name        VARCHAR(128) NOT NULL,
                      config_json JSON         NOT NULL,
                      created_at  DATETIME(3)  NOT NULL,
                      updated_at  DATETIME(3)  NOT NULL
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);
            List<ProjectConfig> loaded = new ArrayList<>();
            jdbc.query("SELECT id, config_json FROM project ORDER BY created_at", rs -> {
                ProjectConfig cfg = parse(rs.getString("id"), rs.getString("config_json"));
                if (cfg != null) {
                    loaded.add(cfg);
                }
            });
            if (!loaded.isEmpty()) {
                return loaded;
            }
            jdbc.update("""
                    INSERT INTO project (id, name, config_json, created_at, updated_at)
                    VALUES (?, ?, ?, NOW(3), NOW(3))
                    """, seed.getId(), seed.getName(), mapper.writeValueAsString(seed));
            log.info("project 表为空，已把 application.yml 里的配置写入为项目 {}（{}）",
                    seed.getId(), seed.getName());
            return List.of(seed);
        } catch (Exception e) {
            log.error("项目配置读取失败，本次改用 application.yml 里的配置运行"
                    + "（采集、染色、门禁不受影响，但页面上保存配置会失败）：{}", describe(e));
            return List.of(seed);
        }
    }

    /** 一个项目的配置坏了不该连累其余项目：点名跳过它，而不是整个平台起不来 */
    private ProjectConfig parse(String id, String json) {
        try {
            return mapper.readValue(json, ProjectConfig.class);
        } catch (Exception e) {
            log.error("项目 {} 的配置无法解析，已跳过：{}", id, describe(e));
            return null;
        }
    }

    private static String describe(Exception e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }
}
