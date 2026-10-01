# ChunkyFly 2.3.4（Minecraft 26.2）

仓库改名：**litematica 风格的后缀去掉了，现在就是 [`sd-dt/chunkyfly`](https://github.com/sd-dt/chunkyfly)**。
这一版把模组里所有指向旧地址的引用都换成了新地址（旧链接 GitHub 会自动重定向，不会失效）。

## 改了什么

| 位置 | 变化 |
|---|---|
| 仓库地址 | `sd-dt/chunkyfly-Azusa` → **`sd-dt/chunkyfly`**（旧的 Release / 提交链接自动重定向） |
| `fabric.mod.json`（26.2 与 1.21.11 两条线） | `description` 里的项目地址、`contact` 的 `homepage`/`sources`/`issues` 全部指向新地址 |
| 版本 | **2.3.4+26.2**（功能与 2.3.3 完全一致，只改元数据） |

## 安装

1. **Minecraft 26.2** + **Fabric Loader ≥ 0.19.5**
2. 把 `chunkyfly-2.3.4+26.2.jar` 放进 `mods/`
3. 推荐装 **Fabric API**（获得带 tab 补全的 `/chunkyfly` 客户端指令）
4. 需要：能滑翔的胸甲（按 `glider` 组件判定）+ 背包里有非爆炸烟花

> 同一时间只允许一个 chunkyfly jar 启用（重复 mod id 会让 Fabric 崩游戏）；旧版本改名 `.disabled` 保留即可。

## 校验

- `javac` exit=0，逐条目重建校验与发布件**逐字节一致**
- `fabric.mod.json`：版本 `2.3.4+26.2`、`icon` → `assets/chunkyfly/icon.png`、`contact` 三个链接均指向 `https://github.com/sd-dt/chunkyfly`

图标为二次创作（五角星取自 GPLv3 项目 Chunky 的标志、鞘翅为 Mojang 原版贴图），详见仓库 README 的「图标来源」。除图标外的代码与文档均为原创，MIT 授权。
