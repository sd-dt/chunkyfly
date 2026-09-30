# ChunkyFly 构建记录

> 记录位置：`docs\BUILD-LOG.md`（迁移前的原始记录存档在 `docs\BUILD-LOG-原.md`）。
> 每次构建都要在下面追加一行：版本、产物路径、大小、sha256、关键改动、验证方式。

## 构建方式

不用 Gradle / Loom，直接 `javac` 对标 MC jar 编译，再用 `jar.exe` 打包。

> **约定（2026-09-24 起）：后续只编译 26.2。** `-Line` 默认值已经是 `26.2`，不带参数即可；
> `1.21.11` 线仅作存档，需要时才显式指定。

```powershell
powershell -File scripts\build.ps1                      # 默认 26.2：Mojang 官方名 / JDK 25 / class 69
powershell -File scripts\build.ps1 -Version 2.0.1+26.2  # 发新版本
powershell -File scripts\verify-rebuild.ps1             # 默认校验 26.2
powershell -File scripts\build.ps1 -Line 1.21.11        # 旧线（存档）：intermediary 名 / JDK 21 / class 65
```

脚本流程：定位 JDK → 重新生成 `build\sources.txt` → 组装 classpath 写进 `build\javac-args.txt`
（**不能嵌套 `@file`**、**`-J` 不能进 argfile**）→ `javac` → 门禁（`exit=0` 且产出 class）→
`resources` + `classes` 合成 `build\stage` → `jar --create`（**不要用 .NET ZipFile**）→
校验 jar 内条目与 class 数 → 打印大小与 sha256。

- `fabric.mod.json` 的 `version` 自 **1.1.0** 起与构建号一致（1.0.x 时代固定写 `1.0.0`，只靠文件名区分）
- 打包前门禁：`javac exit=0` 且 class 数 > 0；jar 内必须含 `fabric.mod.json`、两个 mixin 配置、入口 class
- 覆盖统计是"客户端看到区块"的近似（半径由渲染距离推出，取 2~8 区块），不是服务端数据
- 飞行期间不要同时开打印机模组的自动放置（两者都抢视角）

## 产物清单

> 当前维护的是 **26.2 线**（下一节）；1.21.11 线自 2026-09-24 起**只作存档**，不再继续编译。

### 1.21.11 线（`versions\1.21.11\dist\`）—— 存档

| 版本 | 大小 (B) | SHA-256 | 关键改动 |
|---|---|---|---|
| 1.0.0 | 18719 | `5303B012E386BC0EE662054C37BC072713CD0E41CB33A8158B78FBAE410992E4` | 首版：`/chunkyfly <半径>` 聊天拦截、自动起飞、脚本化蛇形航线、左上角百分比 |
| 1.0.1 | 21251 | `967F55FE2132CB94667BE94044A6B30A7D64F85DCA19A84B03DB447B35646080` | 注册**真正的客户端指令**（`fabric-command-api-v2`）：有 tab 补全、不发到服务器、不再报"未知指令"红字；无 Fabric API 时自动退回聊天拦截 |
| 1.0.2 | 26564 | `FCD897DE2CA61CAC9EEF07AD6722907760F531052DBA99D5E8B1A09C2DB73885` | 起飞前**头顶净空检查**（3×3 柱体向上 400 格）；先爬升到 `CRUISE_Y=1000` 再遍历；真实地形扫描避障（前向 5 点 + 22 格余量 + 左右绕行 + 岩浆/天花板/硬地板保护） |
| 1.0.3 | 27042 | `57D7C7971E13034388064A2D3599F805E216CD6A0C24B383B4FA28BFEEE4AFEB` | 滑翔装备判定改为按 **`glider` 组件**（`DataComponents.GLIDER`），兼容"鞘翅+盔甲融合"插件；判定不通过**不阻断**任务 |
| 1.0.4 | 27111 | `AD22C06D9F2BA443ADF39986140997AB52322FFA81DF2D9E88E2874021DF8F3E` | **俯仰角符号修正**（MC 里 pitch 正值 = 低头 → 之前"爬升"其实在俯冲）；跳跃键 `jumpKeyOwned` 归属管理，完成/取消一定松开（修"停止后无法跳跃"） |
| 1.0.5 | 28122 | `861F0471279347561A1E308C3F4C3E7CB9D549410077BF30BD36536BBC8E5C02` | HUD 颜色补 **alpha 字节**（`0xFFFFFF55`）+ 注入点 TAIL→**HEAD**（1.21.9+ 分层渲染中途 flush）+ 动作栏兜底；烟花按**寿命窗口** `10*时长+12` 补射；分档统计 1/2/3 速并优先三速；起飞改为"按住空格直到滑翔成功" |
| 1.0.6 | 32045 | `1251B0131687E4170E5DC6F0528BADE15995860762425FFA410B3231D104D3FC` | **自动从潜影盒取烟花**：离线读 `container` 组件挑盒 → 背包界面内右键开盒 → `QUICK_MOVE` 搬运（≤6/秒）→ 自动关屏 → 2 秒冷却；HUD 增 `盒中N` |
| 1.0.7 | 32187 | `926AF29C59A987410245AFE67825E37BA370E5F22835603643D0764768DFD689` | 修**进度恒 0%**（`markAround` 覆盖统计被漏调用）；修**爬升螺旋**（只在开始时对准一次偏航，爬升角 55°→80°）；航点循环推进，小半径开飞即完成 |
| 1.0.8 | 32442 | `A89F230140F1093A7846BC9BC55C2CDEEFC4047B0B9C433A3ED1133AD167CF91` | 修**自动起飞**：地面改**脉冲**跳（按住只会跳一次）、空中 `velocity.y<=0` 时**直接发 `START_FALL_FLYING` 包 + `startFallFlying()`**，不再赌按键时序 |
| 1.0.9 | 33956 | `538B80AD895E30506709E488EC9895434B2FF62CA30CC6BDED5D47EA5F57A19E` | **跑完自动返航**：新增 `RETURNING` / `LANDING` 阶段，飞回出发坐标并**盘旋降落**，落地即 `DONE`；返航/降落各有超时兜底 |
| 1.0.10 | 34425 | `0A8694E8472FC970E27D3F6EF51B6AD97FCAE4A6E2E8B9892E2A37180535324D` | **返航/降落安全性强化**：降落俯角按离地高度分档（45°/30°/18°/10°/5°）；盘旋半径随高度收紧 `min(70, max(12, 离地×0.8))`；降落与返航都做前方地形检查；失速保护（>40 格且水平速度 <14 m/s 补一发）；结束判定加"**已经不在滑翔**" |
| 1.1.0 | 38553 | `8ECFB654BD9B9865ED907EFEC9D5D52414658C2F8BBBE0927F1B307E206DA1EE` | ① **版本号与构建号对齐**；② **爬升卡住保护**（已滑翔下连续 5 秒没升高 → 改用"当前高度 −5"巡航）；③ **HUD 偏移配置**：新增 `ChunkyFlyConfig`（`config/chunkyfly.json`，手写正则读写，不引入 gson），`/chunkyfly hud <x> <y>` |
| 1.2.0 | 42647 | `FF9FFEE0DDF05FEB7C8930184576901F5F66F9447BB21FDC456D4099385137F1` | **渲染压力保护**（针对 2026-09-19 01:53 的 `Buffer size must be greater than zero` 崩溃）：① 飞行期间渲染距离临时压到 **8**（`/chunkyfly rd`，暂停/结束/取消/退出世界自动恢复，用户中途自己改过则不覆盖）；② **帧率保护**（持续 15 秒 <8 FPS → 自动暂停）；③ `/chunkyfly cruise <Y>`；④ 起飞时检测 Voxy / 半径 ≥1500 提示 |
| **1.3.0**（当前） | 49211 | `405310E19F207089A8FA31F9C612DD503FF259E3759802A20EDE7A9B8AB4D2DE` | **拦住原版整数溢出崩溃 + 定位"疯狂写入者"**：① `MixinDynamicUniformStorage` 注入 `resizeBuffers`，`blockSize(256)×newCapacity` 溢出 int 时取消扩容并把写指针回绕（正常游戏走不到该分支）；该 mixin 单独放在 `chunkyfly.uniform.mixins.json`（`required:false`）；② 容量 >65536 后每秒采一次调用栈，把首个非原版/Fabric/chunkyfly 的模组帧归类写进 `logs/latest.log`（`[uniform-guard]`）；③ `MixinDynamicUniforms` 记录批量写入来源 —— 查清**唯一**批量写入方是**投影 0.26.12 的 `WorldRendererSchematic.prepareBlockLayers`** |

### 26.2 线（`versions\26.2\dist\`）

| 版本 | 大小 (B) | SHA-256 | 说明 |
|---|---|---|---|
| **2.0.0+26.2** | 49956 | `FE961ABE947667642B4B08529AD28706034B4121CA1686C49A6F8CAC08211BB9` | MC 26.2 / Fabric Loader ≥0.19.5 首个移植版，22 class；曾装入 26.2 实例并 4 次成功启动进过世界 |
| **2.1.0+26.2** | 54863 | `3EB76D09B41F3C4F0BE8DA58C2E1D5829E216B521216C1DE6E8FDC15FFC53626` | ① 新增 **`/chunkyfly corner <x> <z>`**：矩形区域（对角 = 你所在区块 与 (x,z) 所在区块），`FlyTask` 支持任意矩形；② **修"偶尔有一条单区块宽的带没被加载到"**：覆盖判定半径改为「客户端渲染距离」与「实际收到的区块半径」取小再留 1 格余量、航线向区域外扩 1 区块、主航线飞完后自动插**补漏航线**（最多 3 轮）；③ **修降落落点**：螺旋半径收紧到 2 格 + 新增**最后进近**（对准出发坐标压下滑线），冲过头自动复飞（最多 3 次） |
| **2.1.1+26.2** | 55010 | `344D01599DC24202DC7D94AB4BCFC5DC1B48059464ADF3DD9E426F05B1E6CBDA` | 修正 **`corner` 指令参数**：改成四个参数 `/chunkyfly corner <x1> <z1> <x2> <z2>`（两个角点的方块坐标），区域不再依赖玩家所在位置 |
| **2.1.2+26.2** | 55773 | `360C8744DB75708D9BB0FB529B7408B7CCBEA461381E53E1AE3FEEE493D98E24` | 修 **"下降时有概率撞墙"**：① `TerrainScanner` 新增**预测式碰撞判定** —— 按当前水平/垂直速度算出"到达每一列采样点时自己会有多高"，地形顶面高出该预测高度（留 6 格余量）即判为撞墙风险；② 采样距离补 5 格近距项；③ 降落阶段接上该判定：风险近于 40 格就**大幅拉起（−45°）+ 侧转绕开 + 立刻补烟花**，正在最后进近时视为"进近被挡"改为复飞；④ 陡降角 45°→35°、最后进近下滑角上限 30°；⑤ 返航与巡航阶段的爬升/绕行也接入该判定 |
| **2.2.0+26.2** | 57706 | `C5E059BA59F675BE1A5503695AF54FF8F11631813ED3E2172F2A8B1CF9F40890` | ① 修 **"最后没落地就把控制权交还了"**：降落超时不再直接 finish，而是切到**强制下降**（对准落点、固定 8° 俯角）一路降到落地；新增**贴地提交高度**（离地 ≤8 格不再避让/复飞，否则树和矮墙会让它反复拉起来落不下去）与**避让次数上限**（6 次）；若最终仍在空中交还控制权会明确警告。② 新增 **`/chunkyfly return on|off`**（配置键 `returnToStart`，默认 on）：off = 飞完不返航，就地盘旋下降落地。③ 下降手感：盘旋半径 70→**28**、高空段（离地 >90 格）**直接朝落点飞不绕圈**、接近地面才小半径盘旋滑行；俯角整体变温和（陡降 35→20、中段 30→16、低空 10→6、末段 5→3，最后进近上限 30→20） |
| **2.2.1+26.2** | 58266 | `9DE45C3BADD2A09E928EF39BA5FACD2240A3A6BE2E297EBFEABCAC55E00B024C` | 新增**启动提示（光敏性癫痫警告）**：模组初始化时写进日志，进世界后 3 秒 / 23 秒 / 43 秒各在动作栏显示一次（26.2 的 `Hud.chat` 是私有的，拿不到聊天栏，只能走动作栏；分几次显示避免被读图盖住），文本同时提示可用 `/chunkyfly cancel` 立即停止 |
| **2.2.2+26.2** | 57695 | `2108A077CEC0E5E3F29060CA5130923DD1177FDF8F09C76BD599608390E9E069` | **简化降落**：成功率优先 —— 只要落到**降落点周围 16 格以内**就算成功，**允许高度差**（房顶/树上/山坡都行）。做法改为"把飞机圈在 16 格圆环里一直降到贴地"（远了朝里飞 / 略远斜收 / 太近外切 / 圈内切向绕圈），**删掉"最后进近 + 冲过头复飞"**（那套在楼多树多的地方会反复"快到点又拉起来"落不下去）；只保留"预测到真要撞墙才拉起避让"（次数上限 + 贴地 8 格内不避让）；收尾提示会报"落地偏离降落点 N 格（接受范围 16 格内、允许高度差）" |
| **2.2.3+26.2** | 57459 | `D7500683E096BCC5BA1C7D4CE46FC34B69D9F7E43FCF87ACC3E7DDA03EA500D3` | **光敏性癫痫提示改到"任务开始前"**：不再在进入游戏时刷（原来的 3 次动作栏提示与 tick 调度全部移除），改为每次下指令、任务真正跑起来之前提示一次（`beginTask` 的最后一条提示，动作栏会停在它上面），日志同步记录 |
| **2.3.0+26.2** | 62905 | `D46BFCC97D6929F345A6A98871BA8E389A4A238077888AF636F25C9A01B5AE37` | ① **运行时常驻提示**：HUD 增加第三行（橙黄色）`⚠ 光敏性癫痫：自动转向生硬、可能频闪 | 本模组可能崩溃，重进可继续`，任务运行期间一直显示；② 新增**崩溃提示**：任务开始前与光敏提示一起提示 —— "本模组在飞大范围时可能导致游戏崩溃，崩了重进游戏即可，重进后用 /chunkyfly recover 可接着上次没飞完的任务继续"；③ 新增**崩溃续跑**：新增 `TaskStore`（`config/chunkyfly-task.json`）每 5 秒记录区域范围/出发坐标/覆盖判定半径/当前航点下标，正常完成或 cancel 时清档；重进游戏后自动提示一次"检测到上次没飞完的任务"，用 **`/chunkyfly recover`** 从存档航点接着飞（`FlyTask.premarkRoute` 会按航线几何把飞过的那些行预先补标，进度不从 0 开始），**`/chunkyfly forget`** 丢弃存档 |
| **2.3.1+26.2**（当前） | 65402 | `44766A1EEFE4C144D388913DE1B9547BFF1393B3185A9241E9E0B82B563F12FF` | **修"指令列表只显示最后一行"**：26.2 的 `Hud.chat` 是私有字段、`Gui` 也没有 `getChat()`，原来的本地消息只能进动作栏（一次一行），所以 `/chunkyfly help` 的十几行实际只看得到最后一行。新增**可选 mixin** `MixinHudChat`（单独配置 `chunkyfly.chat.mixins.json`，`required:false` + `defaultRequire:0`：签名变了只记日志、不影响启动）影子出 `chat` 并暴露 `chunkyfly$addClientMessage`；`ChunkyFlyMod.info/warn` 改为优先发聊天栏（可翻看、不互相顶掉），不可用时自动退回动作栏 + **HUD 常驻显示 20 秒**；`/chunkyfly help` 重写为分组的**指令列表 + 每条对应功能**（任务 / 崩溃后续跑 / 设置 / 说明） |

> **2.1.0 的三项改动均有验证**：
> - 覆盖问题用航线仿真对比（`FlyTask`/`FlyController` 的航点规则 1:1 复刻到 Python）：旧规则在"转弯迟钝"参数下会漏出
>   **z=5 的一整条单区块宽带**（与用户反馈一致，极端参数下甚至 98.45% 且不收敛）；新规则在 6 组参数下**全部 100%**，
>   且飞行顺畅时补漏轮数为 0（**零额外开销**）。
> - `tools\check_mixins.py` / `check_mixin_sigs.py` / `check_shadows.py`：9 个注入点 + 3 个 `@Shadow` 全部 0 问题。
> - jar 内 `fabric.mod.json` 版本 = `2.1.0+26.2`，22 个 class（与 2.0.0 相同），类文件里确认含 `corner` / `buildFillWaypoints` /
>   `startCorner` / `FINAL_APPROACH` 等新符号。
> - 安装：游戏正在运行导致旧 jar 被锁 → 由后台任务等游戏退出后自动替换为 2.1.0。

> 26.2 线实例核对：`versions\26.2-Fabric\mods\chunkyfly-2.0.0+26.2.jar` = 49956 B，
> 与 `versions\26.2\dist\` 里那份同版本同大小；该实例环境为 MC 26.2 + Fabric Loader 0.19.5（62 模组）。
> 该实例出现过的 3 次崩溃均与本模组无关（启动参数错误 / Camera NPE / satella 模组 mixin 失败）。

> **2.1.1 / 2.1.2 的验证**：
> - 撞墙问题用数值对照（俯冲角 × 墙顶高度 → 触发时距墙还剩多少格）：旧规则只看"地形顶面是否高过当前高度"，
>   20° 浅俯冲、墙顶高 30 格时要到**贴身（0 格）才触发** —— 这就是"有概率撞墙"；新规则按预测高度，
>   同一情形在 **72 格**（最远采样距离）就触发；45°/35° 俯冲分别多出 18 / 26 格以上拉起余量；
>   而真正能安全越过的情形（墙顶低于预测航线）不会误触发，不做无谓爬升。
> - jar 内 `fabric.mod.json` 版本 `2.1.2+26.2`、22 个 class；类文件确认含 `impactRisk` / `impactDistance` / `impactTopY` / `PITCH_IMPACT`。
> - `tools\check_mixins.py` / `check_mixin_sigs.py` / `check_shadows.py`：9 个注入点 + 3 个 `@Shadow` 全部 0 问题。

## 迁移与可复现性验证（2026-09-24）

把工作区从 `D:\1sd_dt\222` 迁到 `D:\1sd_dt\chunkyfly` 之后，用工作区内的源码 + `deps\` + 记录的 JDK
重新构建，与 `dist\` 里的发布件**逐条目**比对（`scripts\verify-rebuild.ps1`）：

| 构建线 | JDK | class 数 | 比对条目 | 结果 |
|---|---|---|---|---|
| 1.21.11 | BellSoft Liberica **21.0.3**（与原构建同款） | 22 | 26 / 26 | **逐字节一致** |
| 26.2 | PCL Zulu **25.0.4.1**（与原构建同款） | 22 | 26 / 26 | **逐字节一致** |

- 比对对象：jar 内全部条目（22 个 `.class` + `fabric.mod.json` + 两个 mixin json + `META-INF/MANIFEST.MF`）
- zip 容器自带时间戳，所以**重建 jar 的整体 sha256 每次都会变**；判断一致性以 jar 内条目为准
- 1.21.11 线用 JDK 21 直接编译（**不加 `--release`**）：加了 `--release 21` 会触发
  `Cannot attach type annotations @org.jspecify.annotations.Nullable to ...` 而编译失败；
  不加 `--release` 时 JDK 21 的默认 target 就是 21，产物 class 版本 65，与原发布件一致
- 26.2 线用 JDK 25 + `--release 25`，产物 class 版本 69

## 已安装版本

### 1.21.11 实例 `D:\1sd_dt\mc\-shot 2.6.8\.minecraft\versions\Petra\mods`

- 当前启用：**`chunkyfly-1.3.0.jar`**（sha256 `405310E19F207089…`，49211 B，启用数量 = 1，已核对与 dist 一致）
- 该目录里目前**没有**其它 chunkyfly jar（历史版本曾按约定改名 `.disabled` 保留，现已被清理，只剩当前版本）
- 26.2 产物**不应该**装进这个实例

### 26.2 实例

| 实例 | mods 路径 | 当前启用 | 说明 |
|---|---|---|---|
| **XPlus PerioTable Modpack (Fabric)**（用户主用） | `...\versions\XPlus PerioTable Modpack (Fabric)\mods` | **`chunkyfly-2.3.1+26.2.jar`**（65402 B，`44766A1EEFE4C144…`） | MC 26.2，151 个模组（含 XaeroPlus / Sodium / Iris / Litematica / MiniHUD / 打印机） |
| 26.2-Fabric（移植测试用） | `...\versions\26.2-Fabric\mods` | **`chunkyfly-2.3.1+26.2.jar`**（同哈希） | MC 26.2 + Fabric Loader 0.19.5，62 模组；旧版均已改名 `.disabled` |

- 两个实例的 `config\chunkyfly.json` 都在；缺 `cruiseY` / `flightRenderDistance` 键的旧格式配置也能用（读不到就用默认值 1000 / 8，改一次即补全）
- 1.21.11 产物**不应该**装进这两个实例

> 注：这两个实例目录都在工作区之外，用工具写入需要提权（沙箱默认只允许写工作区 `D:\1sd_dt\chunkyfly`）。

## 备注

- 覆盖统计是"客户端看到区块"的近似，不是服务端数据；服务端视距明显更小时建议把半径调小或分多次飞
- 飞行期间不要同时开打印机的自动放置（`workingSwitch`），两者都会抢视角
- 26.2 的 mixin 注入点已做静态签名校验（`javap` 对着 `deps\mc-26.2\26.2-client.jar` 核对），
  但**运行时行为**仍需在真实 26.2 实例里验证

---

## 2026-10-03 GitHub 项目建立（chunkyfly-Azusa）

用户要求「把这个模组建立一个新项目上传到 GitHub」。成果：

| 项 | 值 |
|---|---|
| 仓库 | <https://github.com/sd-dt/chunkyfly-Azusa>（public，MIT） |
| 推送目录 | `ghrepo\`（两条线源码 34 个 java + 资源 + 脚本 + 文档 + LICENSE + README，本地 53 个文件） |
| 线上提交 | `9209781c`（种子提交 + 其余文件）、`76809b12`（API 推送脚本与 Release 说明） |
| Release | <https://github.com/sd-dt/chunkyfly-Azusa/releases/tag/v2.3.1>，附件 `chunkyfly-2.3.1+26.2.jar`（65402 B） |
| 推送方式 | **本机 `git push` 不通**（github.com:443 连接被重置：`schannel: SEC_E_NO_CREDENTIALS`、`Recv failure: Connection was reset`），改用 GitHub API（blobs → tree → commit → ref）；**空仓库要先经 Contents API 播种一个文件**，否则 Git Data API 返回 409 `Git Repository is empty`。脚本：`ghrepo\scripts\gh-api-push.ps1` |

过程中确认的两件事：

1. **工作区 ACL 完全正常** —— 用 `diagnose-windows-sandbox-acl` 技能脚本查过（`docs\github` 尚未创建 → 只做检查；工作区根含子树 45 个对象、`writeDac`/`writeOwner` 齐全、无外来包条目 → 结论 `NOT_THIS_CLASS`，脚本零改动）。
   `pwsh` 工具写不进 `docs\github\repo` 是**沙箱完整性级别**问题（DSH 写入通道创建的是中完整性对象，沙箱进程写不进去），而在**沙箱自己创建的目录**（`ghrepo\`）里读写与复制全部正常 —— 所以仓库目录最终放在 `ghrepo\`。
2. `gh repo create --source .` 在本会话里不认当前目录（改用 `gh api --method POST user/repos` 直接建仓）；`--jq` 表达式里**不能带 `|`**（PowerShell 会拆参数 → `accepts 1 arg(s), received N`）；脚本里带中文的 JSON body 要先写成临时文件再 `--input`，避免经控制台代码页转码。

---

## 2026-10-03 约定：以后每次大型修改后**自动同步 GitHub**

用户要求「以后每次大型修改后都自动同步 github」，无需再问。已固化为一条命令：

```powershell
powershell -File ghrepo\scripts\gh-sync.ps1 -Message "本次改了什么"
```

脚本（`ghrepo\scripts\gh-sync.ps1`，纯 ASCII —— PS 5.1 读无 BOM 的 `.ps1` 会按本地代码页解码，带中文必须存 UTF-8 带 BOM）依次做六件事：

| 步骤 | 内容 |
|---|---|
| 1 | `scripts\build.ps1` 重编 26.2 线（`-SkipBuild` 可跳过） |
| 2 | `scripts\verify-rebuild.ps1` 逐条目重建校验（`-SkipVerify` 可跳过） |
| 3 | 从 `fabric.mod.json` 读版本号、定位 `dist\chunkyfly-<版本>.jar`，tag = `v<主版本>`（`2.4.0+26.2` → `v2.4.0`） |
| 4 | 镜像到 `ghrepo\`：两条线的 `src`/`resources`、`docs\BUILD-LOG.md`、`tools\route-coverage-sim.py`、四个构建脚本 |
| 5 | `gh-api-push.ps1` 走 API 建 commit（`git push` 在本机不通） |
| 6 | Release：tag 已存在就 `gh release upload --clobber` 换附件，否则新建（说明优先取 `docs\RELEASE-NOTES-v<tag>.md`） |

首次实跑：commit `346ce719e5b164d793e826f7ea6850d7778ec611`，Release `v2.3.1` 附件替换为同一份 `chunkyfly-2.3.1+26.2.jar`（65402 B，sha256 `44766a1e…`）。

> 约定细节：**改到源码/资源/构建脚本级别的大型修改**才推（每次同步都会产生一个线上 commit 与可能的 Release）；纯本地实验、调试输出不进仓库。发布件（jar）只走 Release，不进仓库。README 与 `deps/README.md`、`LICENSE`、`.gitignore` 由 `ghrepo\` 里手工维护（镜像步骤不会覆盖它们）。
