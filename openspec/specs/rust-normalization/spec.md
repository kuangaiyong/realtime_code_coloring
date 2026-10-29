# rust-normalization Specification

## Purpose
把 llvm-cov 给出的 Rust 覆盖数据归一化成与其他语言同一套口径的方法明细：泛型被单态化出的几个实例按源码定义合并，方法明细的条数与文件级方法数对得上；并写明 Rust 泛型按源码合并、与 C++ 模板按实例分开计是有意的差异 —— 宁可如实说明口径不同，也不让两层数字在页面上互相矛盾。

## Requirements

### Requirement: 同一个源码定义的几个实例在方法明细里只占一条

Rust 的泛型会被单态化成好几个实例，每个实例各有一份机器码、各有一条函数记录。平台 SHALL 把同一个源码定义
（泛型函数、泛型 impl 的方法、trait 的默认方法、blanket impl 的方法、泛型函数里的闭包、const 泛型函数）的几个实例
在方法明细里合成一条：首行号是这个定义的首行，任一实例被调用过即为已调用。
不同的源码定义 SHALL NOT 因为名字相同、首行号相同就被合成一条。
每个文件方法明细的条数 SHALL 等于该文件的方法数（已调用 + 未调用），其中已调用的条数 SHALL 等于该文件的已调用方法数。

#### Scenario: 泛型函数只有一个实例被调用

- **WHEN** 演示服务的手续费接口只以默认方式调用（不带 `unit=cents`）：泛型 `clamp_to` 只有 `i64` 那个实例跑过，`Cents` 那个实例一次都没跑
- **THEN** `demo-service-rust/src/pricing.rs` 的方法明细里 `demo_service_rust::pricing::clamp_to` 恰好出现一次、是已调用，首行号是 `clamp_to` 签名所在的行
- **AND** 该文件的方法是已调用 2、未调用 0（`Cents` 那个实例不会多出一个未调用的方法）

#### Scenario: 每个文件的方法明细与文件级方法数对得上

- **WHEN** 采集一轮之后取每个 Rust 文件的方法明细
- **THEN** 每个文件的方法明细条数等于它的已调用方法数加未调用方法数，已调用的条数等于已调用方法数

#### Scenario: 各种泛型写法都按源码定义合并

- **WHEN** 一个文件里有：分别以基本类型、具名类型、标准库类型、const 值、数组、切片、元组、引用、裸指针、函数指针、dyn 作类型实参实例化的泛型函数；泛型 impl 的方法；被三个类型（其中一个来自别的 crate）用到的 trait 默认方法；blanket impl 的方法；泛型 trait impl 的方法；泛型函数里的闭包 —— 共 52 条函数记录，llvm-cov 给出的文件级方法数是 29、已调用 27
- **THEN** 方法明细共 29 条、其中已调用 27 条
- **AND** 每个泛型定义在方法明细里只有一条

#### Scenario: 同一行上的两个闭包各占一条

- **WHEN** 一个函数在同一行里写了两个闭包（llvm-cov 把它们算成两个方法）
- **THEN** 方法明细里这一行有两条

#### Scenario: 没被执行的泛型函数不会消失

- **WHEN** 一个泛型函数被代码引用但没被执行，另一个泛型函数完全没被引用（只有一个占位实例）
- **THEN** 两者在方法明细里各有一条、都是未调用

### Requirement: 方法名不带实例的类型实参

方法明细里的名字 SHALL 由源码路径上的标识符组成：函数自己的泛型实参、const 值 SHALL NOT 出现在名字里；
trait 默认方法的名字 SHALL 是「trait 路径 + 方法名」，不带实现类型。没有泛型的函数，名字 SHALL 与改动前逐字相同。

#### Scenario: 具名类型实参不进名字

- **WHEN** `clamp_to` 以具名类型 `Cents` 实例化
- **THEN** `demo-service-rust/src/pricing.rs` 的方法明细里没有一条名字里出现 `Cents`

#### Scenario: const 实参不进名字

- **WHEN** const 泛型函数 `flag` 以 `true`、`offset` 以 `-5` 实例化
- **THEN** 名字分别是 `rtcc_generics2::flag` 与 `rtcc_generics2::offset`，不是把常量值错当成标识符的 `flag::_`、`offset::_EB2_`

#### Scenario: trait 默认方法的名字不带实现类型

- **WHEN** trait `Greet` 的默认方法 `hi` 被 `A`、`B`、`String` 三个类型用到
- **THEN** 方法明细里只有一条 `rtcc_generics2::Greet::hi`

#### Scenario: 没有泛型的函数名字不变

- **WHEN** 取演示服务现有函数的名字
- **THEN** 与改动前相同，如 `demo_service_rust::order::Store::query_order`、`demo_service_rust::read_target`、`demo_service_rust::order::is_final_state`

### Requirement: Rust 泛型的行按源码合并，与 C++ 模板按实例分开计是有意的差异

Rust 的行覆盖 SHALL 按源码合并：一个泛型定义的任一实例执行过的行即为已覆盖，Rust 没有部分覆盖这一态；文件级方法数里一个源码定义只算一个。
这与 C++ 模板「某个实例在这一行上没跑过就判部分覆盖、每个实例各算一个方法」不同。差异是有意的：llvm-cov 给出的逐行数据已经把各实例的计数相加，
要按实例区分就得改用另一种导出格式重写 Rust 的归一化，并且会改变既有 Rust 项目的覆盖数字。

#### Scenario: 只有一个实例跑过时，跑过的行是已覆盖

- **WHEN** 手续费接口只以默认方式调用，金额（30000，即 300 分手续费）落在 `clamp_to` 的上下限 50 与 5000 之间
- **THEN** `clamp_to` 里的 `value` 那一行是已覆盖（不是部分覆盖）
- **AND** `return lo;`、`return hi;` 两行是未覆盖
- **AND** 只有 `Cents` 那条路才走的 `return clamp_to(Cents(amount / 100), Cents(50), Cents(5000)).0;` 是未覆盖

### Requirement: 认不出结构的函数符号不拖累其余方法，条数对不上时留痕

函数符号的结构认不出来时（编译器将来的新写法、或记录不完整），平台 SHALL 让它单独成一条、名字尽力取出可读的部分，其余方法照常合并；
SHALL NOT 因此整轮拒绝出报告 —— 方法明细不进覆盖率数字与门禁，此时行覆盖与文件级方法数仍然是对的。
某个文件的方法明细条数或已调用条数与文件级方法数对不上时，平台 SHALL 在日志里留一条警告，点名文件与两边的数字。

#### Scenario: 认不出的符号单独成一条

- **WHEN** 一个文件里同一行有两个认不出结构的函数符号，另有一个正常的函数
- **THEN** 方法明细共三条：两个认不出的各自一条，正常的那个照常
- **AND** 这一轮的报告照常给出，行覆盖不受影响

#### Scenario: 条数对不上时说得出是哪个文件

- **WHEN** 某个文件的方法明细条数与文件级方法数不一致
- **THEN** 平台给出的警告里有这个文件的路径、方法明细的条数与文件级方法数
- **AND** 所有文件都对得上时不给警告
