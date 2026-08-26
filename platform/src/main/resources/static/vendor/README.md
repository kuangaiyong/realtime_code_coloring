# vendor —— 第三方前端库的 UMD 产物

这四个文件是从 npm 取的**未经修改**的发布产物，直接提交进仓库。

| 文件 | 来源包 | 版本 | 许可证 |
|---|---|---|---|
| `vue.global.prod.js` | `vue` → `dist/vue.global.prod.js` | 3.5.41 | MIT |
| `element-plus.full.min.js` | `element-plus` → `dist/index.full.min.js` | 2.14.5 | MIT |
| `element-plus.css` | `element-plus` → `dist/index.css` | 2.14.5 | MIT |
| `element-plus-icons.iife.min.js` | `@element-plus/icons-vue` → `dist/global.iife.min.js` | 2.3.2 | MIT |

## 为什么提交进仓库而不是走 CDN

平台面向**内网测试环境**，不能假设有外网。CDN 挂掉或不可达时，页面会退化成
一个空白的挂载点 —— 而这正是最难排查的那种故障：HTTP 200、控制台一片红。

已确认这几个文件**没有任何外部引用**：`element-plus.css` 里的 `url()`
只有 `data:` URI 和内部 SVG 片段 id，不拉字体、不拉图片。

## 为什么是 UMD 而不是 ESM + 构建

零构建是明确的选型：不引入 node / npm / Vite，`pom.xml` 与 `run_local.sh`
一行不用改，`mvn package` 出来的 jar 里直接就有可用的前端。
代价是全量引入 Element Plus（约 1MB），内网无带宽问题。

## 怎么升级

```bash
npm pack vue@3 element-plus @element-plus/icons-vue
# 解包后按上表把四个文件复制过来，并更新本表的版本号
```

升级后必须跑一遍 `node scripts/ui_verify.js`：它开真实 Chrome，断言页面上的
`data-testid` 钩子仍在、各视图渲染出的数字与 API 一致、染色链路端到端延迟仍 ≤5s。
