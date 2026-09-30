# deps —— 编译需要自备的第三方依赖

这些 jar 都是第三方产物（Mojang / FabricMC / 各自的作者），**按许可不随本仓库分发**，需要你自己放到这里。
`scripts\build.ps1` 会把 `deps\mc-26.2\`（或 `deps\mc-1.21.11\`）下的所有 jar 自动加进 classpath，目录结构与文件名不重要，**只要 jar 在就行**。

## 26.2 线（默认）

放到 `deps\mc-26.2\` 下：

| 需要什么 | 从哪里来 |
|---|---|
| **Minecraft 26.2 客户端 jar** | 官方启动器 26.2 版本目录里的 `26.2.jar`（重命名成 `26.2-client.jar` 之类均可） |
| **Minecraft 26.2 的官方库**（guava、gson、fastutil、brigadier、datafixerupper、authlib、jspecify…） | 官方启动器下载的 libraries 目录；整份拷进 `deps\mc-26.2\libs\` 即可 |
| **Fabric Loader ≥ 0.19.5** | FabricMC 官方 maven / 任意 Fabric 实例的 `fabric-loader-*.jar` |
| **Sponge Mixin**（`sponge-mixin-*.jar`） | 同上（Fabric 实例 libraries 里就有） |
| **Fabric API 26.2 的展开模块**（`fabric-command-api-v2`、`fabric-api-base`） | Fabric API 的 jar 是 JiJ 打包，类在里层；把 `META-INF/jars/` 里的模块 jar 解出来放进 `deps\mc-26.2\fapi\`。**只在用 `FabricCommands`（客户端指令）时需要** |

也可以直接把整个 Fabric 实例的 `mods/` 目录内容拷进来 —— 多几个 jar 不影响编译。

## 1.21.11 线（存档）

放到 `deps\mc-1.21.11\` 下：Minecraft 1.21.11 的 **intermediary** jar（Fabric Loom 会缓存成
`minecraft-merged-intermediary-1.21.11-*.jar`）、Fabric Loader、Sponge Mixin、Fabric API 的
`fabric-command-api-v2` / `fabric-api-base` 模块，以及 MC 自带的那批官方库。

## JDK

- 26.2 线：**JDK 25**（产物 class 版本 69）
- 1.21.11 线：**JDK 21**（产物 class 版本 65）

`scripts\build.ps1` 会按 `scripts\jdk-25.path` / `scripts\jdk-21.path`（各写一行 JDK 主目录）或常见安装路径自动探测，
也可以用 `-Jdk <JDK主目录>` 显式指定。

## 编译一次看看

```powershell
powershell -File scripts\build.ps1 -NoPackage     # 只编译不打包
```

javac 报"找不到类"基本就是上面某个 jar 没放齐。
