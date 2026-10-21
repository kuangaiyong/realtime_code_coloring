//! 演示「同一个泛型被单态化成好几个实例」（openspec：rust-normalization）：
//! clamp_to 以 i64 与 Cents 两种类型实例化 —— 手续费接口默认按 i64 算，带上 unit=cents 才走 Cents 那个实例。
//! rustc 给每个实例各编一份机器码、llvm-cov 各给一条函数记录；平台要按源码计数：
//! 方法明细里 clamp_to 只占一条、任一实例跑过即为已调用，与文件级方法数（llvm-cov 按源码去重）对得上。
//! 两个实例都得从 main 走得到 —— 没被引用的函数不会把它调用的泛型实例化出来。

#[derive(Clone, Copy, PartialEq, PartialOrd)]
pub struct Cents(pub i64);

pub fn clamp_to<T: PartialOrd>(value: T, lo: T, hi: T) -> T {
    if value < lo {
        return lo;
    }
    if value > hi {
        return hi;
    }
    value
}

/// 手续费：金额的 1%，不低于 50、不高于 5000
pub fn fee(amount: i64, in_cents: bool) -> i64 {
    if in_cents {
        return clamp_to(Cents(amount / 100), Cents(50), Cents(5000)).0;
    }
    clamp_to(amount / 100, 50, 5000)
}
