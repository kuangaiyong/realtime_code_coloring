# 项目约定（Codex 入口）

**开始任何任务前，先读本仓库根目录的 `CLAUDE.md`** —— 输出语言（一律中文）、SDD 工作流入口、
分支策略（只有 main / dev 两个分支）、核心功能清单（E2E 覆盖率 100%）、反复踩到的坑都在那里。
本文件只是指路，项目约定以 `CLAUDE.md` 为唯一事实源，冲突时以它为准。

两点翻译约定：

- `CLAUDE.md` 里 `Skill(xxx)` 的写法意为「按名加载 skill xxx」（如 `rtcc-probe-setup`，
  仓库级 skills 在 `.agents/skills/` 下，全局工作流 skill 在 `~/.codex/skills/` 下）。
- `/sdd <目标>` 在 Codex 里对应 `$sdd <目标>`（`sdd` skill 是整条流水线的编排入口）。