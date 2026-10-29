package com.rtcc.platform.collector;

import java.util.ArrayList;
import java.util.List;

/**
 * Rust v0 符号（{@code _R} 开头）的结构：它是<b>哪一个源码定义</b>的实例，以及给人看的名字。
 *
 * <p>为什么要认结构：泛型会被单态化成好几个实例，llvm-cov 给每个实例各一条 FN 记录，
 * 而它自己的文件级方法数（FNF）按函数起点「行, 列」分组 —— 同一个源码定义的几个实例只算一个。
 * lcov 的 FN 只有行号、没有列号，所以同一行上的不同定义（同一行的两个闭包）只能靠符号分开，
 * 同一个定义的几个实例也只能靠符号认成一个。只比「行号 + 名字」两个方向都会错（2026-10-21 实测）。
 *
 * <p><b>这不是完整的 demangle</b>：不渲染类型，只认结构。文法照 v0 规范（RFC 2603）与 rustc-demangle 的解析器逐项实现；
 * 文法之外的写法一律判「认不出」、返回 null，由调用方退回逐条 —— 宁可多一条，也不把认不出的东西硬并成一个。
 * 不引库、不调外部工具：rustup 的 llvm-tools 里没有 llvm-cxxfilt，而本项目对「内网离线可构建」有立场。
 *
 * @param definition 源码定义的键：同一个源码定义的各个实例相同，不同定义不同。
 *                   由 crate 根、命名空间、标识符、消歧号、impl 路径、trait 默认方法所属的 trait 组成；
 *                   函数自己的泛型实参、impl 的 Self 类型、实例化所在的 crate 不算，路径上的反向引用展开后再比
 * @param nameParts  名字的各段：路径上的标识符按出现顺序、不跟反向引用（与原先的扫描法一致）；
 *                   函数自己的泛型实参、const 值、生命周期不进来
 */
record RustSymbol(String definition, List<String> nameParts) {

    /** 递归深度上限。真实符号远到不了；畸形输入超过它就判认不出，免得打穿采集线程的栈 */
    private static final int MAX_DEPTH = 200;

    /** 基本类型的单字母编码：i8 a、bool b、char c、f64 d、str e、f32 f、u8 h …… 占位 p */
    private static final String BASIC_TYPES = "abcdefhijlmnopstuvxyz";

    /** 认不出结构（不是 v0、带编码版本号、文法之外的写法、结构不完整、嵌套过深）时返回 null，不抛异常 */
    static RustSymbol parse(String mangled) {
        if (mangled == null || !mangled.startsWith("_R")) {
            return null;
        }
        // 反向引用的位置以 _R 之后为基准
        String s = mangled.substring(2);
        // _R 后紧跟十进制数是编码版本号，rustc 从不产出 —— 认不得就不认
        if (s.isEmpty() || isDigit(s.charAt(0))) {
            return null;
        }
        try {
            Parser p = new Parser(s, 0);
            List<String> name = new ArrayList<>();
            String key = p.path(name, true, true, false, 0);
            // 实例化所在的 crate：同一个泛型从不同 crate 实例化时这里不同，不算进定义，也不进名字
            if (p.pos < s.length() && isUpper(s.charAt(p.pos))) {
                p.path(null, false, false, false, 0);
            }
            // 之后只允许厂商后缀（.llvm.123 之类）
            if (p.pos < s.length() && s.charAt(p.pos) != '.' && s.charAt(p.pos) != '$') {
                return null;
            }
            return new RustSymbol(key, List.copyOf(name));
        } catch (Unrecognized e) {
            return null;
        }
    }

    /**
     * 一组实例的名字（至少一个）：都相同时原样返回，否则取逐段的公共前缀 + 公共后缀。
     * 实例名字不同只因为 impl 的 Self 类型（blanket impl）或 trait 路径的写法（反向引用 / 完整路径），
     * 不同的部分夹在中间 —— 掐头去尾留下的正是这个定义自己的名字。掐得一段不剩时退回第一个实例的名字：
     * 一条没有名字的明细比名字里带着某个实例的类型更糟。
     */
    static List<String> commonName(List<List<String>> names) {
        List<String> first = names.get(0);
        if (names.stream().allMatch(first::equals)) {
            return first;
        }
        int shortest = names.stream().mapToInt(List::size).min().orElse(0);
        int prefix = 0;
        while (prefix < shortest && sameAt(names, prefix, false)) {
            prefix++;
        }
        // 后缀不能与前缀重叠：一个名字是另一个的前缀时，共同的部分只算一遍
        int suffix = 0;
        while (suffix < shortest - prefix && sameAt(names, suffix, true)) {
            suffix++;
        }
        List<String> out = new ArrayList<>(first.subList(0, prefix));
        out.addAll(first.subList(first.size() - suffix, first.size()));
        return out.isEmpty() ? first : out;
    }

    /** 各名字的第 i 段（fromEnd 时为倒数第 i + 1 段）是否都相同 */
    private static boolean sameAt(List<List<String>> names, int i, boolean fromEnd) {
        String want = segment(names.get(0), i, fromEnd);
        return names.stream().allMatch(n -> segment(n, i, fromEnd).equals(want));
    }

    private static String segment(List<String> name, int i, boolean fromEnd) {
        return fromEnd ? name.get(name.size() - 1 - i) : name.get(i);
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isUpper(char c) {
        return c >= 'A' && c <= 'Z';
    }

    /** 认不出。不填栈：这是预期内的分支之一，不是故障 */
    private static final class Unrecognized extends RuntimeException {
        Unrecognized() {
            super(null, null, false, false);
        }
    }

    /**
     * 递归下降。各方法的参数含义：
     * name 是名字段要追加进去的列表（null 表示这一段不进名字）；key 表示要不要算定义键；
     * value 表示这是一条「值路径」（被命名的函数本身），它上面的泛型实参是实例专属的；
     * expand 表示名字要跟着反向引用展开、并去掉全部实参（只用于 trait 默认方法的 trait 路径）；d 是递归深度。
     */
    private static final class Parser {

        private final String s;
        private int pos;

        Parser(String s, int pos) {
            this.s = s;
            this.pos = pos;
        }

        String path(List<String> name, boolean key, boolean value, boolean expand, int d) {
            if (d > MAX_DEPTH) {
                throw new Unrecognized();
            }
            int start = pos;
            char tag = next();
            switch (tag) {
                case 'C' -> {
                    // crate 根：消歧号是 crate 的哈希，同名的两个 crate 靠它分开
                    String dis = disambiguator();
                    String id = identifier();
                    add(name, id);
                    return key ? "C" + dis + ":" + id : null;
                }
                case 'N' -> {
                    // 命名空间：v 值、t 类型、C 闭包……同一行的两个闭包只差这里的消歧号
                    char ns = next();
                    if (!(ns >= 'a' && ns <= 'z') && !isUpper(ns)) {
                        throw new Unrecognized();
                    }
                    String inner = path(name, key, value, expand, d + 1);
                    String dis = disambiguator();
                    String id = identifier();
                    add(name, id);
                    return key ? "N" + ns + "(" + inner + ")" + dis + ":" + id : null;
                }
                case 'M', 'X' -> {
                    // impl 路径（消歧号 + impl 所在模块）唯一确定一个 impl 块
                    String dis = disambiguator();
                    String impl = path(name, key, true, expand, d + 1);
                    // Self 类型照旧进名字（Store::refund 的 Store），但不进定义：blanket impl 的 Self 是实例专属的
                    type(name, d + 1);
                    if (tag == 'X') {
                        // trait 路径：impl 块已由 impl 路径确定，不必再算进定义
                        path(name, false, false, false, d + 1);
                    }
                    return key ? tag + "" + dis + "(" + impl + ")" : null;
                }
                case 'Y' -> {
                    // <Self as Trait>::item 命名的是 trait 里那段默认实现：Self 类型是实例专属的，名字与定义都不算它
                    type(null, d + 1);
                    int traitAt = pos;
                    String trait = path(null, key, false, false, d + 1);
                    if (name != null) {
                        // trait 路径常是指向 Self 类型内部的反向引用：名字要展开它，否则 crate 前缀时有时无
                        new Parser(s, traitAt).path(name, false, false, true, d + 1);
                    }
                    return key ? "Y(" + trait + ")" : null;
                }
                case 'I' -> {
                    String inner = path(name, key, value, expand, d + 1);
                    // 值路径上的实参是函数自己的泛型参数、每个实例各不相同：名字与定义都不算；
                    // 类型里的实参照旧进名字 —— impl Foo for Vec<Order> 与 Vec<Refund> 是两个 impl 块，靠它分得开
                    List<String> argName = value || expand ? null : name;
                    while (!eat('E')) {
                        genericArg(argName, d + 1);
                    }
                    return inner;
                }
                case 'B' -> {
                    int target = backref(start);
                    String k = key ? new Parser(s, target).path(null, true, value, false, d + 1) : null;
                    if (expand && name != null) {
                        new Parser(s, target).path(name, false, value, true, d + 1);
                    }
                    return k;
                }
                default -> throw new Unrecognized();
            }
        }

        /** 类型：只为找到它在哪里结束、以及收集具名类型里的标识符；类型从不进定义 */
        void type(List<String> name, int d) {
            if (d > MAX_DEPTH) {
                throw new Unrecognized();
            }
            int start = pos;
            char tag = pos < s.length() ? s.charAt(pos) : 0;
            if (tag != 0 && BASIC_TYPES.indexOf(tag) >= 0) {
                pos++;
                return;
            }
            switch (tag) {
                case 'R', 'Q' -> {
                    // 引用：可带生命周期
                    pos++;
                    if (eat('L')) {
                        base62();
                    }
                    type(name, d + 1);
                }
                case 'P', 'O', 'S' -> {
                    // 裸指针 / 切片
                    pos++;
                    type(name, d + 1);
                }
                case 'A' -> {
                    // 数组：元素类型 + 长度（const）
                    pos++;
                    type(name, d + 1);
                    constant(d + 1);
                }
                case 'T' -> {
                    pos++;
                    while (!eat('E')) {
                        type(name, d + 1);
                    }
                }
                case 'F' -> {
                    // 函数指针：[for<'a>] [unsafe] [extern "abi"] (参数…) -> 返回类型
                    pos++;
                    binder();
                    eat('U');
                    if (eat('K') && !eat('C')) {
                        identifier();
                    }
                    while (!eat('E')) {
                        type(name, d + 1);
                    }
                    type(name, d + 1);
                }
                case 'D' -> {
                    // dyn：[for<'a>] trait（各自可带关联类型绑定 p）…… E，最后必须跟一个生命周期
                    pos++;
                    binder();
                    while (!eat('E')) {
                        path(name, false, false, false, d + 1);
                        while (eat('p')) {
                            identifier();
                            type(name, d + 1);
                        }
                    }
                    if (!eat('L')) {
                        throw new Unrecognized();
                    }
                    base62();
                }
                case 'B' -> {
                    // 类型位置的反向引用：只校验、不跟（与原先的扫描法一致，跟了会把模块前缀重复进名字）
                    pos++;
                    backref(start);
                }
                default -> path(name, false, false, false, d + 1);
            }
        }

        void genericArg(List<String> name, int d) {
            if (eat('L')) {
                base62();
            } else if (eat('K')) {
                constant(d + 1);
            } else {
                type(name, d + 1);
            }
        }

        /** const 值：按类型字母分，照 rustc-demangle。值本身从不进名字 —— 原先的扫描法会把十六进制值错当成长度前缀 */
        void constant(int d) {
            if (d > MAX_DEPTH) {
                throw new Unrecognized();
            }
            int start = pos;
            char tag = next();
            switch (tag) {
                case 'p' -> {
                    // 占位
                }
                case 'h', 't', 'm', 'y', 'o', 'j', 'b', 'c', 'e' -> hexDigits();
                case 'a', 's', 'l', 'x', 'n', 'i' -> {
                    // 有符号整数：n 是负号
                    eat('n');
                    hexDigits();
                }
                case 'R', 'Q' -> constant(d + 1);
                case 'A', 'T' -> {
                    while (!eat('E')) {
                        constant(d + 1);
                    }
                }
                case 'V' -> {
                    // 结构体 / 枚举值：路径 + 单元（U）/ 元组式（T）/ 结构式（S，字段名 + 值）
                    path(null, false, false, false, d + 1);
                    switch (next()) {
                        case 'U' -> {
                        }
                        case 'T' -> {
                            while (!eat('E')) {
                                constant(d + 1);
                            }
                        }
                        case 'S' -> {
                            while (!eat('E')) {
                                disambiguator();
                                identifier();
                                constant(d + 1);
                            }
                        }
                        default -> throw new Unrecognized();
                    }
                }
                case 'B' -> backref(start);
                default -> throw new Unrecognized();
            }
        }

        /** 十六进制数字串，以 _ 收尾 */
        private void hexDigits() {
            while (!eat('_')) {
                char c = next();
                if (!isDigit(c) && !(c >= 'a' && c <= 'f')) {
                    throw new Unrecognized();
                }
            }
        }

        /** [for<'a, …>]：绑定的生命周期个数 */
        private void binder() {
            if (eat('G')) {
                base62();
            }
        }

        /**
         * 消歧号 s<base62>，返回原文（没有消歧号时是空串）。只用来分身份，不换算成数：
         * crate 根的消歧号就是 crate 的 64 位哈希，按无符号算会超出 long —— 标准库的 lFVcyoAu48q 约 1.76e19，
         * 换算的话整个 crate 的符号都会被判成认不出
         */
        private String disambiguator() {
            if (!eat('s')) {
                return "";
            }
            int from = pos;
            while (!eat('_')) {
                char c = next();
                if (!isDigit(c) && !(c >= 'a' && c <= 'z') && !isUpper(c)) {
                    throw new Unrecognized();
                }
            }
            return s.substring(from - 1, pos);
        }

        /** 不带消歧号的标识符：[u] 十进制长度 [_] 内容。长度为 0 的是闭包这类匿名项。u 是 punycode，内容原样当名字 */
        private String identifier() {
            eat('u');
            char c = next();
            if (!isDigit(c)) {
                throw new Unrecognized();
            }
            int len = c - '0';
            if (len != 0) {
                while (pos < s.length() && isDigit(s.charAt(pos))) {
                    len = len * 10 + (s.charAt(pos++) - '0');
                    if (len > s.length()) {
                        throw new Unrecognized();
                    }
                }
            }
            // 内容以数字或 _ 开头时，编码器会插一个 _ 当分隔符
            eat('_');
            if (len > s.length() - pos) {
                throw new Unrecognized();
            }
            String id = s.substring(pos, pos + len);
            pos += len;
            return id;
        }

        /** base-62 数：单独一个 _ 是 0，否则「数字串 + _」的值加 1 */
        private long base62() {
            if (eat('_')) {
                return 0;
            }
            long x = 0;
            while (!eat('_')) {
                char c = next();
                int digit;
                if (isDigit(c)) {
                    digit = c - '0';
                } else if (c >= 'a' && c <= 'z') {
                    digit = c - 'a' + 10;
                } else if (isUpper(c)) {
                    digit = c - 'A' + 36;
                } else {
                    throw new Unrecognized();
                }
                if (x > (Long.MAX_VALUE - 1 - digit) / 62) {
                    throw new Unrecognized();
                }
                x = x * 62 + digit;
            }
            return x + 1;
        }

        /** 反向引用 B<base62> 的目标位置：必须早于 B 自己，否则会绕回原处 */
        private int backref(int start) {
            long target = base62();
            if (target >= start) {
                throw new Unrecognized();
            }
            return (int) target;
        }

        private char next() {
            if (pos >= s.length()) {
                throw new Unrecognized();
            }
            return s.charAt(pos++);
        }

        private boolean eat(char c) {
            if (pos < s.length() && s.charAt(pos) == c) {
                pos++;
                return true;
            }
            return false;
        }

        private static void add(List<String> name, String id) {
            if (name != null && !id.isEmpty()) {
                name.add(id);
            }
        }
    }
}
