package com.coverage.platform.service;

/** 请求查看的场景不存在（未开始过，或平台重启后已丢失） */
public class ScenarioNotFoundException extends RuntimeException {
    public ScenarioNotFoundException(String message) {
        super(message);
    }
}
