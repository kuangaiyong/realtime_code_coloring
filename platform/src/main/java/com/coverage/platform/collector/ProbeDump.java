package com.coverage.platform.collector;

import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.data.SessionInfo;

import java.util.List;

/** 一次远程抓取的结果：执行数据，以及被测实例自报的会话信息 */
public record ProbeDump(ExecutionDataStore exec, List<SessionInfo> sessions) {
}
