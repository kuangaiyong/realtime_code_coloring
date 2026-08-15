package com.coverage.platform.model;

import org.jacoco.core.data.SessionInfo;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 被测实例自报的构建版本。
 *
 * 来源是 JaCoCo agent 的 sessionid 启动参数（约定写成 {@code sessionid=<40位sha>[-dirty]}），
 * 因此仍然满足「源码零改动」——只多一个启动参数。
 *
 * 之所以不去读产物目录里的构建元数据：运行中的 JVM 加载的是它启动那一刻的字节码，
 * 磁盘上的产物可能已被重新构建过。会话 ID 由进程自己带出来，才是真正对得上的那个版本。
 */
public record BuildVersion(String commit, boolean dirty) {

    private static final Pattern SESSION_ID = Pattern.compile("^([0-9a-f]{40})(-dirty)?$");

    /** 会话 ID 不符合约定（例如未配置 sessionid，JaCoCo 会用「主机名-随机数」兜底）时返回 null */
    public static BuildVersion parse(List<SessionInfo> sessions) {
        for (int i = sessions.size() - 1; i >= 0; i--) {
            Matcher m = SESSION_ID.matcher(sessions.get(i).getId());
            if (m.matches()) {
                return new BuildVersion(m.group(1), m.group(2) != null);
            }
        }
        return null;
    }

    public String shortCommit() {
        return commit.substring(0, 8);
    }
}
