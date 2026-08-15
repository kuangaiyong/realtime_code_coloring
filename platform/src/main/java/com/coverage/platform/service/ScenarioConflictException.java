package com.coverage.platform.service;

/**
 * 当前操作会破坏场景归因的可信度。
 *
 * 场景覆盖是「start 清零 → 执行 → stop 抓取」这段窗口的独占产物，
 * 窗口被并发场景或中途清零打断后，归档下来的数据仍是一份完整报告的样子，
 * 但它已经不再回答「这个场景覆盖了什么」——只能拒绝，不能将就。
 */
public class ScenarioConflictException extends RuntimeException {
    public ScenarioConflictException(String message) {
        super(message);
    }
}
