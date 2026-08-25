package com.rtcc.platform.service;

/**
 * 门禁无法给出可信判定。
 *
 * 门禁的结论要驱动自动化动作（挡不挡这次合并），只有「通过 / 不通过」两个值，
 * 没有「部分可信」的余地。所以数据一旦不完整，就必须回绝而不是照常给个数字：
 * 少算的覆盖会压低比例，把本该放行的改动挡在门外，开发补完测试仍然过不了——
 * 真正的原因（某台实例掉线）却一个字都没出现在结论里。
 */
public class GateUndecidableException extends RuntimeException {
    public GateUndecidableException(String message) {
        super(message);
    }
}
