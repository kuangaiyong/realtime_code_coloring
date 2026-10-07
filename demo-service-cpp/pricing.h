// 演示「同一段源码被编进好几个函数实例」（openspec：cpp-normalization）：
// feeCents 是内联函数，main.cpp 与 order.cpp 都用到它 —— 两个编译单元各编出一份副本，链接器只留一份，
// 另一份的计数永远是 0；clampTo 是模板，实例化了 long long 与 int 两次。
// 平台要按源码计数：跑全了的行是已覆盖、分支不随副本翻倍、同一个函数只算一个方法，模板的每个实例各算一个方法。
#pragma once

inline long long feeCents(long long amount) {
    if (amount >= 10000) {
        return amount / 100;
    }
    return 50;
}

template <typename T>
T clampTo(T value, T lo, T hi) {
    if (value < lo) {
        return lo;
    }
    if (value > hi) {
        return hi;
    }
    return value;
}
