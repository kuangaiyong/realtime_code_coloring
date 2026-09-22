# probe-collection Specification

## Purpose
TBD - created by archiving change probe-transient-failure. Update Purpose after archive.

## Requirements

### Requirement: 探针瞬时连接失败必须与实例掉线区分开

平台连接探针被**拒绝**（对端 RST）时，必须重试一次再下判断；
只有重试后仍失败，才可以把该实例判为掉线。

这条要求的存在理由是：把一台健康实例误报成掉线，与「出一份静默错误的报告」
是同一类错误 —— 它会把人引向一台根本没有问题的机器，
并让依赖实例齐全的断言（多实例并集语义）失去前提。

#### Scenario: 首次连接被拒、重试成功

- **WHEN** 平台连接某个 Java 探针时收到连接拒绝，而重试时对端已可接受连接
- **THEN** 该实例的 dump 正常返回，状态为 `CONNECTED`
- **AND** 不产生任何掉线事件

#### Scenario: 重试后仍被拒

- **WHEN** 平台两次连接同一个探针都被拒绝
- **THEN** 该实例状态为 `DISCONNECTED`
- **AND** 错误信息中注明已经重试过，以便与「一次都没试成」区分

#### Scenario: 连接超时不触发重试

- **WHEN** 平台连接探针时等满超时而非被拒绝
- **THEN** 立即判定失败，不进行重试
- **AND** 单轮采集的最坏耗时不因本要求而增加

理由：超时是实例真掉线的信号。对超时重试会让单轮采集最坏耗时翻倍
（8 个实例 × 3s = 24s 变 48s），威胁端到端延迟口径，且顶破 E2E 的客户端超时。
