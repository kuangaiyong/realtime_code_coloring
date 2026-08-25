package com.rtcc.platform.service;

/**
 * 增量口径无法给出可信结果。
 *
 * 宁可拒绝出报告，也不能返回一个「看起来正常但行号对不上」的结果——
 * 后者是静默错误，用户不会察觉，却会据此做出错误的补测决策。
 */
public class IncrementalUnavailableException extends RuntimeException {
    public IncrementalUnavailableException(String message) {
        super(message);
    }
}
