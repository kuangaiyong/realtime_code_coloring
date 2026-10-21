package com.rtcc.platform.collector;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Rust v0 符号（{@code _R} 开头）的结构解析：认出「是哪一个源码定义的实例」，并给出不带实例类型实参的名字。
 *
 * <p>为什么非认结构不可：llvm-cov 的文件级方法数（FNF）按函数起点「行, 列」分组，同一个源码定义的几个实例只算一个；
 * 而 lcov 的 FN 记录只给行号。同一行上的两个定义（同一行的两个闭包）只能靠符号分开，同一个定义的几个实例也只能靠符号认成一个。
 * 只比「行号 + 现有可读名」两个方向都会错 —— 2026-10-21 实测：同一行两个闭包被并成一条，又有 8 组该并的没并上。
 *
 * <p>符号全部取自真实产物（rustc 1.97.1，照 run_local.sh 的 build_rust 参数构建）：
 * {@code rtcc_generics2} 是 src/test/resources/rust-generics/generics.rs 那个实验 crate，
 * {@code demo_service_rust} 是仓库里的演示服务，标准库泛型的实例化取自 {@code -C save-temps} 留下的目标文件。
 */
class RustSymbolTest {

    private static RustSymbol parsed(String sym) {
        RustSymbol s = RustSymbol.parse(sym);
        assertNotNull(s, "认不出结构：" + sym);
        return s;
    }

    private static String nameOf(String sym) {
        return String.join("::", parsed(sym).nameParts());
    }

    /** 这几个符号是同一个源码定义的不同实例：定义相同，名字都是 name */
    private static void instancesOf(String name, String... syms) {
        for (String sym : syms) {
            assertEquals(parsed(syms[0]).definition(), parsed(sym).definition(), "同一个定义的实例被认成了两个：" + sym);
            assertEquals(name, nameOf(sym), "名字里混进了实例的类型实参：" + sym);
        }
    }

    @Test
    void 泛型函数的各个实例是同一个定义且名字不带类型实参() {
        // 基本类型：larger::<f64> 与 larger::<i32> 只差类型字母 d / l
        instancesOf("rtcc_generics2::larger",
                "_RINvCsoL4aAQfs_14rtcc_generics26largerdEB2_",
                "_RINvCsoL4aAQfs_14rtcc_generics26largerlEB2_");
        // 具名类型：现有扫描法会把实参里的 Order 拼进名字（pick::Order）
        instancesOf("rtcc_generics2::pick",
                "_RINvCsoL4aAQfs_14rtcc_generics24pickNtB2_5OrderEB2_",
                "_RINvCsoL4aAQfs_14rtcc_generics24pickhEB2_");
        // 标准库类型与引用
        instancesOf("rtcc_generics2::describe",
                "_RINvCsoL4aAQfs_14rtcc_generics28describeNtNtCs8oYkXk2gzQW_5alloc6string6StringEB2_",
                "_RINvCsoL4aAQfs_14rtcc_generics28describeReEB2_",
                "_RINvCsoL4aAQfs_14rtcc_generics28describexEB2_");
        // const 实参：十六进制的值曾被当成长度前缀，解出 arr_len::_E 这种乱码
        instancesOf("rtcc_generics2::arr_len",
                "_RINvCsoL4aAQfs_14rtcc_generics27arr_lenKj2_EB2_",
                "_RINvCsoL4aAQfs_14rtcc_generics27arr_lenKj3_EB2_");
        // impl Trait 参数：实参里带着标准库的迭代器适配器与 main 里的闭包类型
        instancesOf("rtcc_generics2::sum_all",
                "_RINvCsoL4aAQfs_14rtcc_generics27sum_allINtNtNtCs8oYkXk2gzQW_5alloc3vec9into_iter8IntoIterlEEB2_",
                "_RINvCsoL4aAQfs_14rtcc_generics27sum_allINtNtNtNtCs55qC6OcLGgs_4core4iter8adapters3map3MapINtNtNtBK_3ops5range5RangelENCNvB2_4main0EEB2_");
        // 各种类型写法作实参：数组、for<'a> 函数指针、unsafe extern "C" 函数指针、函数指针、Box<dyn Iterator<Item = i32>>、
        // 带生命周期的结构体、裸指针（mut / const）、dyn 引用、切片引用、元组
        instancesOf("rtcc_generics2::ident",
                "_RINvCsoL4aAQfs_14rtcc_generics25identAhj4_EB2_",
                "_RINvCsoL4aAQfs_14rtcc_generics25identFG_RL0_eERL0_eEB2_",
                "_RINvCsoL4aAQfs_14rtcc_generics25identFUKClElEB2_",
                "_RINvCsoL4aAQfs_14rtcc_generics25identFlElEB2_",
                "_RINvCsoL4aAQfs_14rtcc_generics25identINtNtCs8oYkXk2gzQW_5alloc5boxed3BoxDNtNtNtNtCs55qC6OcLGgs_4core4iter6traits8iterator8Iteratorp4ItemlEL_EEB2_",
                "_RINvCsoL4aAQfs_14rtcc_generics25identNtB2_6HolderEB2_",
                "_RINvCsoL4aAQfs_14rtcc_generics25identOhEB2_",
                "_RINvCsoL4aAQfs_14rtcc_generics25identPhEB2_",
                "_RINvCsoL4aAQfs_14rtcc_generics25identRDNtNtCs55qC6OcLGgs_4core3fmt7DisplayEL_EB2_",
                "_RINvCsoL4aAQfs_14rtcc_generics25identRShEB2_",
                "_RINvCsoL4aAQfs_14rtcc_generics25identTlhEEB2_");
        // 泛型 impl 的方法：Self 类型 Wrapper<&str> / Wrapper<i32>
        instancesOf("rtcc_generics2::Wrapper::show",
                "_RNvMCsoL4aAQfs_14rtcc_generics2INtB2_7WrapperReE4showB2_",
                "_RNvMCsoL4aAQfs_14rtcc_generics2INtB2_7WrapperlE4showB2_");
        // 泛型函数里的闭包：实参夹在路径中间（NC I…E 0）
        instancesOf("rtcc_generics2::apply_all",
                "_RNCINvCsoL4aAQfs_14rtcc_generics29apply_allcE0B4_",
                "_RNCINvCsoL4aAQfs_14rtcc_generics29apply_alllE0B4_");
        // trait 的默认方法：命名的是 trait 里那段默认实现，不带实现类型。String 来自别的 crate，
        // 它那个实例的 trait 路径写成了完整路径，A / B 的则是指向 Self 类型内部的反向引用 —— 名字与定义都得展开它
        instancesOf("rtcc_generics2::Greet::hi",
                "_RNvYNtCsoL4aAQfs_14rtcc_generics21ANtB4_5Greet2hiB4_",
                "_RNvYNtCsoL4aAQfs_14rtcc_generics21BNtB4_5Greet2hiB4_",
                "_RNvYNtNtCs8oYkXk2gzQW_5alloc6string6StringNtCsoL4aAQfs_14rtcc_generics25Greet2hiBG_");
    }

    @Test
    void impl的Self类型不参与认定义() {
        // blanket impl：<Order as Tagged>::tag 与 <i32 as Tagged>::tag 是同一个 impl 块里的同一个方法
        assertEquals(parsed("_RNvXs2_CsoL4aAQfs_14rtcc_generics2NtB5_5OrderNtB5_6Tagged3tagB5_").definition(),
                parsed("_RNvXs2_CsoL4aAQfs_14rtcc_generics2lNtB5_6Tagged3tagB5_").definition());
        // 泛型 trait impl：Wrapper<fmt::Error> 那个实例的 trait 路径是指向实参内部的反向引用（NtBL_7Display），
        // Wrapper<u8> 那个是完整路径 —— 原文怎么比都对不上
        assertEquals(parsed("_RNvXs_CsoL4aAQfs_14rtcc_generics2INtB4_7WrapperNtNtCs55qC6OcLGgs_4core3fmt5ErrorENtBL_7Display3fmtB4_").definition(),
                parsed("_RNvXs_CsoL4aAQfs_14rtcc_generics2INtB4_7WrapperhENtNtCs55qC6OcLGgs_4core3fmt7Display3fmtB4_").definition());
    }

    @Test
    void 不同的定义不会因为名字相同被认成一个() {
        // 同一行（第 25 行）的两个闭包：名字都显示成 rtcc_generics2::chain，只有消歧号不同（第二个带 s_）
        String first = "_RNCNvCsoL4aAQfs_14rtcc_generics25chain0B3_";
        String second = "_RNCNvCsoL4aAQfs_14rtcc_generics25chains_0B3_";
        assertEquals(nameOf(first), nameOf(second), "前提：两个闭包的名字相同");
        assertNotEquals(parsed(first).definition(), parsed(second).definition());
        // 闭包与它所在的函数
        assertNotEquals(parsed(first).definition(), parsed("_RNvCsoL4aAQfs_14rtcc_generics25chain").definition());
        // 两个 impl 块各自实现 Greet::name：只差 impl 路径的消歧号（s0_ / s1_）
        assertNotEquals(parsed("_RNvXs0_CsoL4aAQfs_14rtcc_generics2NtB5_1ANtB5_5Greet4name").definition(),
                parsed("_RNvXs1_CsoL4aAQfs_14rtcc_generics2NtB5_1BNtB5_5Greet4name").definition());
        // 泛型函数里的闭包与这个泛型函数
        assertNotEquals(parsed("_RNCINvCsoL4aAQfs_14rtcc_generics29apply_allcE0B4_").definition(),
                parsed("_RINvCsoL4aAQfs_14rtcc_generics29apply_allcEB2_").definition());
    }

    @Test
    void 没有泛型的函数名字与改动前逐字相同() {
        // 演示服务的全部 13 个函数：报表页现在显示的就是这些名字
        assertEquals("demo_service_rust::read_target", nameOf("_RNCNvCs2r1QDoXLnWk_17demo_service_rust11read_target0B3_"));
        assertEquals("demo_service_rust::read_target", nameOf("_RNvCs2r1QDoXLnWk_17demo_service_rust11read_target"));
        assertEquals("demo_service_rust::json_escape", nameOf("_RNvCs2r1QDoXLnWk_17demo_service_rust11json_escape"));
        assertEquals("demo_service_rust::query_param", nameOf("_RNvCs2r1QDoXLnWk_17demo_service_rust11query_param"));
        assertEquals("demo_service_rust::main", nameOf("_RNvCs2r1QDoXLnWk_17demo_service_rust4main"));
        assertEquals("demo_service_rust::handle", nameOf("_RNvCs2r1QDoXLnWk_17demo_service_rust6handle"));
        assertEquals("demo_service_rust::send_json", nameOf("_RNvCs2r1QDoXLnWk_17demo_service_rust9send_json"));
        assertEquals("demo_service_rust::order::Order::new",
                nameOf("_RNvMNtCs2r1QDoXLnWk_17demo_service_rust5orderNtB2_5Order3new"));
        assertEquals("demo_service_rust::order::Store::new",
                nameOf("_RNvMs_NtCs2r1QDoXLnWk_17demo_service_rust5orderNtB4_5Store3new"));
        assertEquals("demo_service_rust::order::Store::handle_callback",
                nameOf("_RNvMs_NtCs2r1QDoXLnWk_17demo_service_rust5orderNtB4_5Store15handle_callback"));
        assertEquals("demo_service_rust::order::Store::query_order",
                nameOf("_RNvMs_NtCs2r1QDoXLnWk_17demo_service_rust5orderNtB4_5Store11query_order"));
        assertEquals("demo_service_rust::order::Store::refund",
                nameOf("_RNvMs_NtCs2r1QDoXLnWk_17demo_service_rust5orderNtB4_5Store6refund"));
        assertEquals("demo_service_rust::order::is_final_state",
                nameOf("_RNvNtCs2r1QDoXLnWk_17demo_service_rust5order14is_final_state"));
    }

    @Test
    void const实参与下划线开头的标识符不会解出乱码() {
        // 现有扫描法：flag::_、offset::_EB2_（把 const 的十六进制值当成了长度前缀）
        assertEquals("rtcc_generics2::flag", nameOf("_RINvCsoL4aAQfs_14rtcc_generics24flagKb1_EB2_"));
        assertEquals("rtcc_generics2::letter", nameOf("_RINvCsoL4aAQfs_14rtcc_generics26letterKc78_EB2_"));
        assertEquals("rtcc_generics2::offset", nameOf("_RINvCsoL4aAQfs_14rtcc_generics26offsetKln5_EB2_"));
        // 以下划线开头的标识符要带一个「_」分隔符（28_ + __rust_begin_short_backtrace）；
        // 现有扫描法把分隔符算进名字、又截掉了最后一个字符
        assertEquals("std::sys::backtrace::__rust_begin_short_backtrace", nameOf(
                "_RINvNtNtCslFVcyoAu48q_3std3sys9backtrace28___rust_begin_short_backtraceFEuuECsoL4aAQfs_14rtcc_generics2"));
    }

    @Test
    void 标准库泛型的各种实例化都认得() {
        // 从 -C save-temps 留下的目标文件里倒出来的：嵌套泛型 + 闭包类型 + 可变引用 + trait 项、数组 + const、
        // 切片、元组、dyn + 生命周期 + 反向引用的 trait 路径、const 实参，末尾都带着实例化所在的 crate
        for (String sym : List.of(
                "_RINvYINtNtNtCs55qC6OcLGgs_4core3str4iter5SplitNtB8_12IsWhitespaceENtNtNtNtBa_4iter6traits8iterator8Iterator8try_folduNCINvNvB12_4find5checkReQNtB8_10IsNotEmptyE0INtNtNtBa_3ops12control_flow11ControlFlowB2d_EECs2r1QDoXLnWk_17demo_service_rust",
                "_RNvXsa_NtCs55qC6OcLGgs_4core5arrayAhj4_NtNtB7_3fmt5Debug3fmtCsoL4aAQfs_14rtcc_generics2",
                "_RNvMNtCs55qC6OcLGgs_4core5sliceSh11starts_withCs2r1QDoXLnWk_17demo_service_rust",
                "_RNvXs1G_NtCs55qC6OcLGgs_4core3fmtTlhENtB6_5Debug3fmtCsoL4aAQfs_14rtcc_generics2",
                "_RNvXs1i_NtCs55qC6OcLGgs_4core3fmtRDNtB6_7DisplayEL_Bx_3fmtCsoL4aAQfs_14rtcc_generics2",
                "_RINvNvNtCs55qC6OcLGgs_4core3ptr25swap_nonoverlapping_bytes26swap_nonoverlapping_chunksKj8_ECs2r1QDoXLnWk_17demo_service_rust")) {
            assertNotNull(RustSymbol.parse(sym), sym);
        }
    }

    @Test
    void crate哈希超出long范围也认得() {
        // crate 根的消歧号是 64 位哈希：标准库的 lFVcyoAu48q 按无符号算约 1.76e19，超出 long。
        // 把它换算成 long 的话这个 crate 的符号全都认不出 —— 被测服务的 crate 撞上这样的哈希，合并就静默失效
        assertEquals("std::sys::net::connection::socket::TcpListener::bind::inner", nameOf(
                "_RNvNvMs7_NtNtNtNtCslFVcyoAu48q_3std3sys3net10connection6socketNtB7_11TcpListener4bind5inner"));
    }

    @Test
    void 认不出的写法返回null而不是抛异常() {
        for (String bad : new String[]{
                null, "", "plain_name", "_ZN4test3fooE",            // 不是 v0
                "_R",                                                // 只有前缀
                "_RNvCs2r1QDoXLnWk_17demo_service_rus",              // 截断：长度前缀超出符号
                "_R0NvCs2r1QDoXLnWk_17demo_service_rust4main",       // 带编码版本号（rustc 不产出）
                "_RNvCs2r1QDoXLnWk_17demo_service_rust4mainZZ",      // 路径之后多出认不得的东西
                "_RNvB2_4main",                                      // 反向引用没有指向更早的位置
                "_RINvCsoL4aAQfs_14rtcc_generics26largerdB2_",       // 实参表没有 E 收尾
                "_RNvCs2r1QDoXLnWk_17demo_service_rust4main\u0000",  // 控制字符
        }) {
            assertNull(RustSymbol.parse(bad), String.valueOf(bad));
        }
    }

    @Test
    void 反向引用绕成环或嵌套极深时不会打穿栈() {
        // 采集线程每 3 秒解析一轮；StackOverflowError 不是「认不出」，会把整轮采集带崩
        assertNull(RustSymbol.parse("_RNvB_4main"), "反向引用指回自己所在的路径");
        assertNull(RustSymbol.parse("_R" + "Nv".repeat(100_000) + "Cs_4test"), "嵌套十万层");
    }

    @Test
    void 一组实例的名字取公共前缀与公共后缀() {
        assertEquals(List.of("a", "b"), RustSymbol.commonName(List.of(List.of("a", "b"), List.of("a", "b"))));
        // blanket impl：一个实例的 Self 类型有标识符（Order），另一个没有（i32）
        assertEquals(List.of("rtcc_generics2", "Tagged", "tag"), RustSymbol.commonName(List.of(
                List.of("rtcc_generics2", "Order", "Tagged", "tag"), List.of("rtcc_generics2", "Tagged", "tag"))));
        // 泛型 trait impl：一个实例的 Self 类型实参带着 core::fmt::Error，trait 路径因此成了反向引用
        assertEquals(List.of("rtcc_generics2", "Wrapper", "core", "fmt", "Display", "fmt"), RustSymbol.commonName(List.of(
                List.of("rtcc_generics2", "Wrapper", "core", "fmt", "Error", "Display", "fmt"),
                List.of("rtcc_generics2", "Wrapper", "core", "fmt", "Display", "fmt"))));
        // 一个是另一个的前缀：共同的部分不能前后各算一遍
        assertEquals(List.of("a", "b"), RustSymbol.commonName(List.of(List.of("a", "b"), List.of("a", "b", "b"))));
    }
}
