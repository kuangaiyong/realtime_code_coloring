---
name: rtcc-artifact-store
description: 代码染色平台的产物仓库（容器化部署时 CI 按 buildId 把编译产物推给平台，artifact-source: uploaded）：四条硬约定（不收 -dirty、取不到产物一律拒绝出报告、只要这一轮有实例的语言、只留最近 N 个构建）、buildId 与 project 的路径校验、解压防膨胀的两道闸、写接口的令牌（为什么在过滤器里判、没配就关闭）、uploaded 项目的自检为什么与采集走同一条解析路径。改 ArtifactStore、/api/artifacts、ArtifactAuthFilter、uploaded 模式的采集或自检、向导 / 设置 / 接入页里 uploaded 那一支、e2e_artifact.py 时读这份。
---

# 产物仓库（只有容器化部署才用得上）

平台要靠被测服务的**编译产物**才解得出行号：Java 要 `.class`、C++ 要 `.gcno`、
Rust 要产物本身（Go 不用，它的覆盖数据自包含）。裸机部署时这些就在本机路径上，
配置里直接填路径即可；**容器化之后平台够不着容器的文件系统**，所以改由 CI
在构建后把产物推给平台。

| 取法 | `artifact-source` | 产物从哪来 |
|---|---|---|
| 裸机（默认） | `local` | `classes-dir` / `cpp-objects-dir` / `rust-binary` 这几条本地路径 |
| 容器 | `uploaded` | 按 buildId 从产物仓库取，CI 用 `POST /api/artifacts/<buildId>?project=<id>&lang=<java\|cpp\|rust>`（请求头带 `Authorization: Bearer <令牌>`）推上来 |

**索引键是 buildId，也就是实例自报的那个 sessionid / `COVERAGE_BUILD_ID`**，
两端天然对齐、不需要新的版本标识。按固定路径存的话，推上去的是 commit A 的产物、
容器里跑的是 commit B，平台会拿 A 的字节码解 B 的探针数据 ——
算出**行号错位却看起来完全正常**的报告，正是本平台最要消灭的那种错。

四条由此而来的硬约定：

- **不收 `-dirty` 的 buildId**（上传侧拒、读取侧也拒）。工作树脏时同一个 commit
  能对应无数份不同的产物，取出来的可能不是这个容器加载的那一份
- **取不到产物一律拒绝出报告**，不降级、不跳过那门语言 —— 跳过的话界面上表现为
  「这些代码没被调用过」，与真相完全相反，而且看不出是缺产物
- **只要这一轮真有实例的那几种语言**。C++ 两台都掉线时不该因为「缺 cpp 产物」
  把整轮打成 ANALYZE_ERROR，那会把「少几台降级成 PARTIAL、照常出报告」
  变成「完全没有报告」
- 只保留最近 `artifact-keep` 个构建（默认 10，**至少 1**），存新的时顺手清旧的

**页面上建 / 改 uploaded 项目**（向导第 4 步「产物来源」、项目设置、服务接入页）：

- **自检不要三条本地产物路径**，改为全部实例连上之后，按它们自报的构建走
  `ArtifactStore.resolveInto` 取产物 —— **与采集同一条路径**，否则迟早「自检说行、采集说缺产物」。
  所以 `resolveInto` **不许加只该在采集时发生的副作用**（向导每点一次「检查这一步」都会触发）。
  判「版本统一」同理共用 `BuildVersion.unified`；取回的 Java 产物照样核对「是不是同一个工程」，
  且容忍包里的前缀目录（`zip -r target/classes` 打的包，JaCoCo 按类名认、不在乎这一层）
- 另报一项「产物上传接口」：平台没配令牌时不通过 —— 此刻产物仓库可能因为旧产物还在而通过，
  要到下一个构建才出事；纯 Go 项目不推产物，不出这一项
- 自检要求产物**先**在仓库里，而项目还没建出来、进不了接入页 —— 所以推产物命令在向导第 4 步就给
- 命令生成器在 `onboard-data.js`（`CI_UPLOAD` / `ciUploadLines`），向导与接入页共用一份。
  形状每处都有讲究：Java 用 `jar`（构建 Java 的地方一定有 JDK）；C++ 递归找 `.gcno` 再压平
  （归一化只看对象目录顶层）；路径与上传地址都加引号（地址不加引号时 `&` 把命令拆成后台任务）

**安全边界：上传 / 删除接口能写平台磁盘，所以要令牌（见文末）；平台其余部分仍假设部署在内网。** buildId 与 project
都直接参与磁盘路径，两者各有各的校验：buildId 限死 40 位小写 hex，
project **做包含性检查**（归一化后必须仍落在产物根之下 —— 它由用户自取，
现网已有中文项目名，限字符集会把合法项目挡在外面）；zip 条目另挡了 Zip Slip。

解压膨胀另有两道闸，因为上传包本身的 200MB 上限拦不住它 ——
压着几十 KB、解开能把磁盘写满，而平台的磁盘是共享的（趋势入库、gcov 临时文件、
日志全要写盘）：`artifact-max-unzipped-bytes`（默认 1 GiB）挡「少数巨大条目」，
`artifact-max-entries`（默认 10 万）挡「海量空条目」—— 后者总字节几乎为零，
字节闸一点都拦不住。两道都是**边写边判**：撞上上限时这一批字节还没落盘，
而不是写完整个条目再报错（那样一个足够大的条目在被发现之前就已经把磁盘写满了）。
撞闸回 **400 不是 5xx**：CI 据此知道该换包，而不是把同一个包一遍遍重推。

**写接口的令牌**（`ArtifactAuth` + `ArtifactAuthFilter`）：

- 令牌取自 `COVERAGE_ARTIFACT_TOKEN`（yml 键 `coverage.artifact-token`），请求头 `Authorization: Bearer <令牌>`。
  **没配就关闭（403），不是放行** —— 放行等于默认敞着，「补鉴权」只对记得配的人生效。
  没带、写法不对、带错一律 401 并带 `WWW-Authenticate: Bearer`；报错体由 `ApiExceptionHandler.error` 构造。
  列表（GET）不要令牌：只有 buildId 与语言，没有内容
- **在 servlet 过滤器里判，不在控制器里**：控制器拿到 `MultipartFile` 时整个请求体已被收下、落成临时文件。
  过滤器只读请求头与路径，Spring 这一层不落盘；但 Tomcat 仍按 `max-swallow-size`（200MB）吞剩余请求体，只占带宽
- **路径用 MVC 同一套 `RequestPath` 解析逐段比**，不比字符串前缀 —— `/api;x=1/artifacts/…`、`/%61pi/artifacts/…`
  在 MVC 眼里都是产物接口。在别的路径下新增产物写接口，要一并纳入这个过滤器
- `run_local.sh` 没拿到这个变量时生成一份存在 `.run/artifact-token`：平台与各套验收由不同进程拉起，必须同一个值

**仍没堵的**：页面与其余接口（项目增删改、清零、场景）没有登录 —— 暴露到公网前要补的是平台整体的鉴权，
不只是这两个接口。
