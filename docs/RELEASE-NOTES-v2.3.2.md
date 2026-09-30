# ChunkyFly 2.3.2（Minecraft 26.2）

模组信息面板这次有两处变化：

- **图标**：换成新头像（512×512 PNG，`assets/chunkyfly/icon.png`）—— 之前没有图标，Fabric 面板显示的是默认占位图。
- **简介指向 GitHub**：描述末尾附上项目地址，并补上 `contact` 字段（`homepage` / `sources` / `issues`），面板里会多出可点击的仓库链接。

```
类 Chunky 的客户端模组：自动用鞘翅飞行来加载/生成区块，自动从快捷栏与背包取非爆炸烟花，
按最快方式飞行，左上角显示进度，跑完自动返航降落。
项目地址：https://github.com/sd-dt/chunkyfly-Azusa
```

功能上与 2.3.1 完全一致（只动元数据与资源，没改任何代码）。

## 安装

1. **Minecraft 26.2** + **Fabric Loader ≥ 0.19.5**
2. 把 `chunkyfly-2.3.2+26.2.jar` 放进 `mods/`
3. 推荐装 **Fabric API**（获得带 tab 补全的 `/chunkyfly` 客户端指令）
4. 需要：能滑翔的胸甲（按 `glider` 组件判定）+ 背包里有非爆炸烟花

> 同一时间只允许一个 chunkyfly jar 启用（重复 mod id 会让 Fabric 崩游戏）；旧版本改名 `.disabled` 保留即可。

## 校验

- `javac` exit=0，逐条目重建校验与发布件**逐字节一致**（含新增的图标资源）
- `fabric.mod.json`：版本 `2.3.2+26.2`、`icon` 指向 `assets/chunkyfly/icon.png`、`contact` 三个链接均指向本仓库

完整源码见仓库；许可 MIT。
