# ChunkyFly 2.3.1（Minecraft 26.2）

类 Chunky 的**纯客户端** Fabric 模组：不装服务端插件，靠**鞘翅自动飞行**把目标区域飞一遍，让服务端加载 / 生成区块。
烟花全自动（快捷栏 → 背包 → 潜影盒），跑完自动返航并落回起飞点附近。

## 安装

1. **Minecraft 26.2** + **Fabric Loader ≥ 0.19.5**
2. 把 `chunkyfly-2.3.1+26.2.jar` 放进 `mods/`
3. 推荐装 **Fabric API**（能获得带 tab 补全的客户端指令 `/chunkyfly`；没装会自动退回聊天栏 `chunkyfly ...`）
4. 需要：一件能滑翔的胸甲（按 `glider` 组件判定）+ 背包里有非爆炸烟花

> 同一时间只允许一个 chunkyfly jar 启用（重复 mod id 会让 Fabric 崩游戏）；旧版本改名 `.disabled` 保留即可。

## 主要功能

**飞行与遍历**
- 自动起飞（净空检查 + 脉冲跳 / 空中直接发 `START_FALL_FLYING` 包）、爬升到巡航高度（默认 Y=1000）
- 蛇形航线遍历，行距 = 2×覆盖半径；覆盖半径取「客户端渲染距离」与「实测收到的区块半径」较小值再留余量
- **补漏航线**：主航线飞完仍有漏格时自动补飞（最多 3 轮），不漏"单区块宽的带"
- **预测式避让**：按速度预测撞墙风险，提前拉起 + 侧转 + 补烟花
- 任意矩形区域：`/chunkyfly corner <x1> <z1> <x2> <z2>`

**降落**
- 自动返航（可 `/chunkyfly return off` 改为就地下降）
- 高空直接朝落点温和下降，接近地面才小半径盘旋滑行
- **落到降落点 16 格内、贴地即完成，允许高度差**；贴地 8 格内不再避让，下降超时切强制下降，绝不空中交还控制权

**烟花与物资**
- 只认非爆炸烟花（`explosions` 为空），1/2/3 速分档并优先三速
- 全自动补给：快捷栏 → 背包 → **潜影盒**（离线挑盒、背包界面内右键开盒、`QUICK_MOVE` 搬运、自动关屏与冷却）
- 按寿命窗口补射（`10×时长+12` tick）

**稳定性与安全**
- 飞行期间渲染距离保护（默认压到 8，可配 / 可关）＋ 持续低帧率自动暂停
- 原版 `DynamicUniformStorage` 扩容整数溢出兜底（拦掉 `Buffer size must be greater than zero` 崩溃）
- **崩溃续跑**：任务中每 5 秒存档，崩溃重进后用 `/chunkyfly recover` **从上次航点接着飞**
- 光敏性癫痫与崩溃提示；本地消息优先发聊天栏（`Hud.chat` 私有，用可选 mixin 接入，未生效自动退回动作栏）

## 指令速查

```
/chunkyfly <半径>                       加载该半径（方块）内的区块
/chunkyfly radius <半径> [chunks]       同上，chunks 表示按区块算半径
/chunkyfly corner <x1> <z1> <x2> <z2>   加载两个角点之间的矩形区域
/chunkyfly pause | continue | cancel    暂停 / 继续 / 取消
/chunkyfly status                       进度 / 阶段 / 烟花用量
/chunkyfly recover | forget             崩溃后续跑 / 丢弃存档
/chunkyfly hud | cruise | rd | return   界面、巡航高度、渲染距离、是否返航
/chunkyfly help                         指令列表 + 每条功能
```

## 校验

- `javac` exit=0，**24 个 class**；`fabric.mod.json` 版本 `2.3.1+26.2`
- `tools` 侧校验：mixin 注入点 9 个 + `@Shadow` 3 个全部 0 问题（含可选聊天栏 mixin）
- 航线覆盖回归仿真（`docs/route-coverage-sim.py`）：修复后在 6 组飞行参数下覆盖率均为 100%

## 注意

- 飞行期间不要同时开打印机模组的自动放置（两者都抢视角）
- 装 Voxy / Sodium / Iris 时建议飞行前关掉 Voxy 远景
- ⚠️ **光敏性癫痫**：本模组自动转视角较生硬、画面可能频闪，相关病史者请谨慎使用（`/chunkyfly cancel` 可立即停止）
- ⚠️ 飞大范围时**可能导致游戏崩溃**，崩了重进游戏即可，重进后用 `/chunkyfly recover` 继续

完整源码见仓库；许可 MIT。
