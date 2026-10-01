# ChunkyFly 2.3.3（Minecraft 26.2）

**模组图标换成「五角星 + 鞘翅」**（512×512 PNG）。功能与 2.3.2 完全一致，只动图标资源。

## 图标怎么来的

| 元素 | 来源 | 处理 |
|---|---|---|
| 五角星（内部是地图纹理） | **Chunky** 模组的标志素材（<https://github.com/pop4959/Chunky>，pop4959，**GPLv3**），取自其 jar 内的 `icon.png`（256×256） | LANCZOS 放大到 1024 |
| 鞘翅 | **Minecraft** 原版物品贴图 `assets/minecraft/textures/item/elytra.png`（16×16） | NEAREST 整数倍放大 **32 倍 → 512px**（保持像素感） |
| 描边 / 阴影 | 由鞘翅 alpha 掩码膨胀 + 高斯模糊生成 | 让鞘翅在蓝绿地图纹理上也能看清 |

贴在**星形掩码的重心**（256 基准下约 `(127.4, 141.0)`），也就是"五角星中间"。成品同时输出 1024 / 512 / 256 三个尺寸，模组用的是 512 那张。

> ⚠️ **素材归属**：五角星是 GPLv3 项目 Chunky 的标志素材、鞘翅是 Mojang 的原版贴图，**都不是本项目的原创素材**，仅作个人/社区标识用途。要用于商业或需要严格许可的场合请自行替换为原创图；仓库根目录的 `README.md` 里也写了这一条。

## 安装

1. **Minecraft 26.2** + **Fabric Loader ≥ 0.19.5**
2. 把 `chunkyfly-2.3.3+26.2.jar` 放进 `mods/`
3. 推荐装 **Fabric API**（获得带 tab 补全的 `/chunkyfly` 客户端指令）
4. 需要：能滑翔的胸甲（按 `glider` 组件判定）+ 背包里有非爆炸烟花

> 同一时间只允许一个 chunkyfly jar 启用（重复 mod id 会让 Fabric 崩游戏）；旧版本改名 `.disabled` 保留即可。

## 校验

- `javac` exit=0，逐条目重建校验与发布件**逐字节一致**
- `fabric.mod.json`：版本 `2.3.3+26.2`、`icon` → `assets/chunkyfly/icon.png`、`contact` 三个链接指向本仓库

完整源码见仓库；许可 MIT（图标素材归属见上）。
