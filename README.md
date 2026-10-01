# ChunkyFly

> 类 Chunky 的**纯客户端** Fabric 模组：**不装服务端插件**，靠**鞘翅自动飞行**把目标区域飞一遍，让服务端加载 / 生成区块。
> 烟花全自动（快捷栏 → 背包 → 潜影盒），按最快方式飞行，左上角实时显示进度，跑完**自动返航并落回起飞点附近**。

- 目标环境：**Minecraft 26.2** + Fabric（`fabricloader >= 0.19.5`）
- **纯客户端**模组：不依赖 Fabric API，也不依赖 MixinExtras（只用原版 Mixin）；装了 Fabric API 时会注册真正的**客户端指令**（有 tab 补全、不会发到服务器），没装则自动退回聊天拦截
- 只需要：一件能滑翔的胸甲（按 `glider` 组件判定，兼容"鞘翅 + 盔甲融合"插件）+ 背包里有非爆炸烟花
- 另有 **1.21.11** 构建线（intermediary 名编译）作为存档保留，源码在 `versions/1.21.11`

---

## 特性

### 飞行与遍历

| 功能 | 说明 |
|---|---|
| **自动起飞** | 起飞前做 3×3 柱体净空检查；地面用脉冲跳、空中直接发原版 `START_FALL_FLYING` 包开鞘翅，不赌按键时序 |
| **爬升到巡航高度** | 默认 Y=1000（可配）；到不了那么高会自动改用能达到的高度，爬升卡住也有保护 |
| **蛇形航线遍历** | 行距 = 2×覆盖判定半径，相邻两行扫过的带刚好拼满，不重复飞 |
| **覆盖判定半径自适应** | 取「客户端渲染距离」与「**实测已收到的区块半径**」的较小值再留 1 格余量，服务器视距偏小时也不会成排漏格 |
| **补漏航线** | 主航线飞完若仍有区块没扫到（转弯切角 / 被吹偏），自动插一条补漏航线飞过去，最多 3 轮；飞行顺畅时零额外开销 |
| **预测式避让** | 按当前水平 / 垂直速度推算"到达每一列采样点时自己会有多高"，地形顶面高出预测高度就提前拉起 + 侧转 + 补烟花（修"下降时撞墙"） |
| **矩形区域** | `/chunkyfly corner <x1> <z1> <x2> <z2>`，两个角点之间任意矩形 |

### 降落

| 功能 | 说明 |
|---|---|
| **自动返航** | 遍历结束飞回出发坐标；可用 `/chunkyfly return off` 关掉，改成**就地**下降 |
| **温和下降** | 高空段直接朝落点飞（不绕大圈），接近地面才小半径盘旋滑行 |
| **成功标准宽松** | 落到**降落点周围 16 格以内、贴地即算完成**，**允许高度差**（旁边的房顶 / 树上 / 山坡都行） |
| **不会"落不下去"** | 贴地 8 格内不再避让；避让有次数上限；下降超时会切**强制下降**，绝不把控制权在空中交还（真交还了也会明确警告） |

### 烟花与物资

| 功能 | 说明 |
|---|---|
| **只认非爆炸烟花** | 组件的 `explosions` 为空才算；1/2/3 速分档统计并**优先三速** |
| **全自动补给** | 快捷栏 → 背包 → **潜影盒**（离线读 `container` 组件挑盒、背包界面内右键开盒、`QUICK_MOVE` 搬运、自动关屏与冷却） |
| **按寿命窗口补射** | `10×时长+12` tick 补一发，不白烧 0~12 tick 的窗口 |

### 稳定性与安全

| 功能 | 说明 |
|---|---|
| **渲染距离保护** | 飞行期间把渲染距离临时压到 8（可配 / 可关），暂停、结束、取消、退出世界自动恢复；你自己中途改过则不覆盖 |
| **帧率保护** | 持续 15 秒低于 8 FPS 自动暂停任务 |
| **原版动态缓冲溢出兜底** | 1.21.9+ 的 `DynamicUniformStorage` 扩容时 `blockSize × capacity` 会 int 溢出直接崩游戏；本模组在即将溢出时取消扩容并回绕写指针，并把"疯狂写入者"的调用栈归类写进日志（前缀 `[uniform-guard]`） |
| **崩溃续跑** | 任务运行中每 5 秒把区域 / 航点 / 进度写进 `config/chunkyfly-task.json`；崩溃后重进游戏，用 `/chunkyfly recover` **从上次的航点接着飞**（`/chunkyfly forget` 丢弃） |
| **安全提示** | 任务开始前提示光敏性癫痫风险与崩溃提示；运行期间 HUD 第三行常驻显示；本地消息优先发聊天栏（26.2 的 `Hud.chat` 是私有的，用可选 mixin 接入，未生效时自动退回动作栏 + HUD 常驻显示） |

---

## 指令

装了 **Fabric API** 时是真正的客户端指令（tab 补全、不发到服务器）；没装时在聊天栏直接输入 `chunkyfly ...`（带不带 `/` 都行）。

| 指令 | 作用 |
|---|---|
| `/chunkyfly <半径>` | 从当前位置起飞，加载该半径（**方块**）内的区块，例：`/chunkyfly 1000` |
| `/chunkyfly radius <半径> [chunks]` | 同上；末尾加 `chunks` 表示半径按**区块**算 |
| `/chunkyfly chunks <半径>` | 半径按区块算（例：`/chunkyfly chunks 64` ≈ 1024 格） |
| `/chunkyfly corner <x1> <z1> <x2> <z2>` | 加载**矩形区域**：四个参数是两个角点的方块坐标 |
| `/chunkyfly pause` / `stop` | 暂停飞行（保留进度） |
| `/chunkyfly continue` | 继续上次任务 |
| `/chunkyfly cancel` | 取消任务（丢弃进度，同时清掉崩溃存档） |
| `/chunkyfly status` | 查看进度 / 阶段 / 烟花用量 |
| `/chunkyfly recover` | **崩溃后续跑**：从上次没飞完的航点接着飞 |
| `/chunkyfly forget` | 丢弃上次的任务存档 |
| `/chunkyfly hud <x> <y>` | 挪动左上角进度文字（像素；`hud reset` 复位） |
| `/chunkyfly cruise <Y>` | 巡航高度（默认 1000） |
| `/chunkyfly rd <n\|off>` | 飞行期间临时压到的渲染距离（默认 8） |
| `/chunkyfly return <on\|off>` | 飞完之后是否返回出发点（默认 on；off = 就地下降落地） |
| `/chunkyfly help` | 指令列表 + 每条对应功能 |

**别名**：`radius` = `start` = `fly`；`status` = `info`；`pause` = `stop`；`continue` = `resume`；`cruise` = `cruisey`；`rd` = `renderdistance`；`corner` = `rect`；`recover` = `restore`；`forget` = `discard`；`return` = `back` = `home`。

---

## 安装

1. 装 **Fabric Loader ≥ 0.19.5** 的 **Minecraft 26.2** 实例
2. 把 `chunkyfly-<版本>+26.2.jar` 放进 `mods/`
3. （可选，推荐）装 **Fabric API**：能获得真正的客户端指令与 tab 补全
4. 进游戏：`/chunkyfly help` 看指令列表；穿鞘翅、背包备好非爆炸烟花，然后 `/chunkyfly 1000`

> **同一时间只允许一个 chunkyfly jar 处于启用状态** —— 重复 mod id 会让 Fabric 直接崩游戏。旧版本改名成 `.disabled` 保留即可。

---

## 构建

不依赖 Gradle / Loom：直接 `javac` 对标 MC jar 编译，再用 `jar.exe` 打包。

```powershell
# 26.2 线（默认；JDK 25，class 版本 69）
powershell -File scripts\build.ps1
powershell -File scripts\build.ps1 -Version 2.3.2+26.2   # 发新版本
powershell -File scripts\build.ps1 -NoPackage            # 只编译不打包
powershell -File scripts\verify-rebuild.ps1              # 重建校验（与 dist 里的产物逐条目比对）

# 1.21.11 存档线（intermediary 名 / JDK 21 / class 版本 65）
powershell -File scripts\build.ps1 -Line 1.21.11
```

- JDK 位置：脚本按 `scripts\jdk-<主版本>.path` → 常见安装路径自动探测；也可用 `-Jdk <主目录>` 指定
- 需要自备的第三方 jar（版权原因不随仓库分发）见 [`deps/README.md`](deps/README.md)
- 构建脚本会把源文件列表与 classpath 写进 `build\javac-args.txt`（避开 Windows 命令行长度限制），并校验 jar 内条目与 class 数

---

## 配置

`config/chunkyfly.json`（用指令修改会自动写盘）：

| 键 | 默认 | 说明 |
|---|---|---|
| `hudX` / `hudY` | `4` / `4` | 进度文字的像素偏移（向右 / 向下） |
| `cruiseY` | `1000` | 巡航高度（下次起飞生效） |
| `flightRenderDistance` | `8` | 飞行期间压到的渲染距离；`0` = 不改（关闭该保护） |
| `returnToStart` | `true` | 飞完是否返回出发点；`false` = 就地下降落地 |

任务进度存档：`config/chunkyfly-task.json`（崩溃续跑用，正常完成或 `cancel` 时自动清除）。

---

## 已知注意事项

- **飞行期间不要同时开打印机模组的自动放置**（`workingSwitch`），两者都抢视角。
- 高空完全没有遮挡时一帧内可见区块段最多：装了 **Voxy / Sodium / Iris** 建议飞行前关掉 Voxy 远景或调小；本模组会自动把渲染距离压到 8，并在持续低帧率时自动暂停兜底。
- 覆盖统计是"客户端看到区块"的近似，不是服务端数据；服务端视距明显更小时建议分多次飞（覆盖判定半径已会自动适配服务端侧实测半径）。
- ⚠️ **光敏性癫痫提示**：本模组会自动、且比较生硬地转动视角（每 tick 直接设 yaw/pitch），高空飞行时画面还有大量区块刷新，**可能出现频闪**；有相关病史者请谨慎使用，随时可用 `/chunkyfly cancel` 立即停止。
- ⚠️ 本模组在飞大范围时**可能导致游戏崩溃**（渲染压力 / 原版动态缓冲那类问题），崩了重进游戏即可；重进后用 `/chunkyfly recover` 可以接着上次没飞完的任务继续。

---

## 目录结构

```
chunkyfly/
├─ README.md
├─ LICENSE                       MIT
├─ versions/
│  ├─ 26.2/                      当前维护线（Mojang 官方名 / JDK 25 / class 69）
│  │  ├─ src/com/chunkyfly/      18 个 .java
│  │  └─ resources/              fabric.mod.json + 3 个 mixin 配置
│  └─ 1.21.11/                   存档线（intermediary 名 / JDK 21 / class 65）
├─ scripts/                      构建 / 安装 / 重建校验 / 脚本编码修复
├─ deps/README.md                编译需要自备哪些第三方 jar
└─ docs/
   ├─ BUILD-LOG.md               逐版本改动与验证记录
   └─ route-coverage-sim.py      航线覆盖仿真（回归工具，验证"不漏单区块宽带"）
```

---

## 许可

[MIT](LICENSE)。可自由使用、修改、再分发，保留版权与许可声明即可。

模组里对原版行为的名称与机制参考了 **Minecraft**（Mojang）与 **Fabric**（FabricMC）的公开 API；"类 Chunky" 指功能形态参考了 Chunky 系列工具的设计思路，本模组为独立实现、不含其代码。

### 图标来源

模组图标是**二次创作**：五角星取自 **Chunky**（<https://github.com/pop4959/Chunky>，作者 pop4959，**GPLv3**）的标志素材，中间的鞘翅是 **Minecraft** 原版物品贴图（© Mojang）。两处都不是本项目的原创素材 —— 仅作个人/社区标识用途；如果要把本项目或其图标用于商业或需要严格许可的场合，请自行替换为原创图（`versions/26.2/resources/assets/chunkyfly/icon.png`，512×512 PNG）。除图标外的全部代码与文档均为本项目原创，按 MIT 授权。
