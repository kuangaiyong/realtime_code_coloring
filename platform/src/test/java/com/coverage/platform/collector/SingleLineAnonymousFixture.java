package com.coverage.platform.collector;

/**
 * 验证夹具：把匿名内部类压在一行里，使该行同时持有外部类的 NEW 指令
 * 与内部类方法体的指令。JaCoCo 会为 Outer 和 Outer$1 各报告一次该行。
 */
class SingleLineAnonymousFixture {

    Runnable make() {
        return new Runnable() { public void run() { System.out.print(""); } };
    }
}
