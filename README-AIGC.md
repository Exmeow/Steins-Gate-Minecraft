# 极限检查点（Hardcore Checkpoints）

Minecraft Java Edition 1.21.1 Fabric Mod，命名空间为 `hardcore_checkpoints`。项目使用 Mojang 官方 Mappings、Java 21 字节码目标和 Balm。

## 当前实现

已实现：

- 始终注册的存档核心方块、物品、方块实体、配方、语言、模型、纹理和掉落；
- 世界 lineage / instance / activation 三层身份与世界外权威侧车控制根；
- SHA-256 信封、flush、临时文件恢复、原子替换、路径绑定和启用周期隔离；
- 停服 ZIP 导出/导入，导入生成新的 `worldInstanceId`；
- 服务端阶段、roster、epoch、READY、2秒稳定期，以及客户端可见的5秒恢复倒计时；
- Fabric Configuration / Play 协议握手、唯一控制会话、请求 ID 和乐观 roster 版本检查；
- 原版 tick rate 冻结、玩家和载具锁、客户端输入锁及已知 Play C2S 操作门控；
- 5 秒检查点条件检测、每名玩家最近核心并集、核心补偿日志、世界 flush、临时快照、manifest 校验和原子指针发布；
- 最新和上一份有效检查点保留；
- `/checkpoint` 专用服务器命令、Configuration/Play 状态页、roster 管理页和 ESC 入口；
- 单人新建极限世界开关、存档列表管理入口、停服导出/导入和不完整控制数据载入拦截；
- 首名最终死亡事务、固定20秒单调倒计时、完整Vanilla旁观、倒计时重连门禁和死亡阶段roster规则；
- 停服后manifest/哈希/身份校验、同卷临时恢复、原子目录切换、救援副本、启动健康提交和`RECOVERY_FAILED`同事务重试；
- 单人自动停服、离线恢复、崩溃启动扫描和自动重新打开世界；
- Java 21监督程序、Mod内嵌自动交接、随机回环控制令牌、只读状态端口、60秒死亡停服、子进程树处理、恢复重启和客户端自动重连；
- 已激活核心的青色吸入粒子、选中核心5秒读条渐强粒子、多人回档等待音乐，以及解冻后的全局存档/回档音效。

Phase 7和Phase 9的代码路径已实现；本次实机多人死亡已验证事务恢复和服务器重启成功。项目当前不包含Gradle自动化测试源码或测试依赖，修复后的客户端等待页接管/自动重连、强制终止和大型世界恢复仍需在实际专用服务器部署中复验。

NeoForge 尚未实现，也没有 NeoForge 子工程、依赖、入口或元数据。

## 模块

- `control-core/`：纯Java身份、原子持久化、快照校验、导入导出、死亡事务和离线恢复；不依赖Minecraft、Fabric、Balm或NeoForge。
- `common/`：加载器中立的Minecraft/Balm业务、状态机、网络模型和资源；不导入Fabric或NeoForge API。
- `fabric/`：Fabric生命周期、Configuration/Play网络、命令、Mixin、客户端输入、恢复界面和监督桥。
- `supervisor/`：独立Java 21子进程监督、HTTP状态/控制端点、恢复编排和CLI。

## 构建

Windows PowerShell 或 CMD：

```powershell
.\gradlew.bat build
```

Git Bash 或 Linux：

```bash
./gradlew build
```

生成的发行产物：

```text
fabric/build/libs/hardcore-checkpoints-fabric-0.1.0.jar
supervisor/build/distributions/supervisor-0.1.0.zip
supervisor/build/distributions/supervisor-0.1.0.tar
```

运行时需要 Fabric Loader、Fabric API 和 Balm。

## 控制入口

专用服务器管理员命令：

```text
/checkpoint status
/checkpoint enable
/checkpoint disable
/checkpoint roster list
/checkpoint roster add <uuid>
/checkpoint roster remove <uuid>
/checkpoint admin list
/checkpoint admin add <uuid>
/checkpoint admin remove <uuid>
```

专用服务器可以按普通Fabric命令启动，例如`java -Xmx4G -jar fabric-server-launch.jar nogui`。若Mod检测到监督环境完全缺失，会先阻止入场并保存世界，从Mod JAR原子提取监督程序到服务器根目录的`.hardcore_checkpoints/runtime/supervisor.jar`，再启动监督并安全停服。监督stdin使用父JVM持有的管道；旧Minecraft原有的单一控制台线程在交接后不再执行旧服务端命令，而是把每一行输入转发给监督及其Minecraft子进程。父JVM另有非守护生命周期继电线程等待监督结束；监督程序等待旧Minecraft释放`session.lock`后，以原Java/JVM/Fabric参数重新启动Minecraft并完成健康握手。后续启用、关闭和死亡恢复均由监督程序编排。玩家或控制台执行普通`stop`且没有受控事务时，命令经代理到达新Minecraft，新Minecraft退出码为0，监督程序随之退出，最后原JVM继电线程结束并让启动命令返回。

自动交接配置首次启动时写入`config/hardcore_checkpoints-supervisor.properties`：

```properties
auto-launch=true
status-bind=0.0.0.0
status-port=25566
health-timeout-seconds=300
```

也可以关闭`auto-launch`并显式使用独立发行包启动：

```text
supervisor\bin\supervisor.bat run --world D:\server\world --status-bind 0.0.0.0 --status-port 25566 -- java -Xmx4G -jar fabric-server-launch.jar nogui
```

Linux使用`supervisor/bin/supervisor`。状态端口默认`25566`，需要按部署需求在服务器防火墙中显式开放；内部控制端点始终只绑定回环地址，随机令牌仅通过子进程环境传递。部分存在或无效的监督环境变量会被视为配置错误，Mod不会递归启动第二个监督程序。第三方服务器面板必须允许原Java进程在交接后继续持有生命周期继电线程，并且不得在该父进程仍存活时清理其派生监督/Minecraft进程树。

停服CLI：`supervisor export --world <path> --archive <zip>`、`supervisor import --archive <zip> --world <new-path>`、`supervisor retry --world <path>`。

单人模式在新建极限世界页面提供开关。存档列表顶部提供检查点管理和导入入口；管理页只操作未加载世界。导出文件默认写入：

```text
<saves>/.hardcore_checkpoints/exports/
```

## 持久化布局

世界内仅保存非权威定位标记：

```text
<world>/hardcore_checkpoints.identity.json
```

权威控制数据位于世界父目录：

```text
<world-parent>/.hardcore_checkpoints/instances/<worldInstanceId>/
```

检查点位于该实例的 `checkpoints/`，有效指针为 `checkpoint-pointer.json`。普通文件系统复制不继承或绑定源侧车；完整复制必须使用停服导出/导入。

同一来源的两份世界同时启动不受支持，不承诺检测、隔离或数据安全。

## 构建验证

生产代码与制品构建命令：

```bash
./gradlew :control-core:compileJava :supervisor:compileJava :fabric:compileJava :fabric:compileClientJava :fabric:remapJar
```

项目当前不包含Gradle测试源码、JUnit依赖或显式`Test`任务配置。上述命令仅编译生产代码，并生成内嵌监督器的Fabric Mod。已有制品审计确认`common`无Fabric/NeoForge导入，`control-core`和`supervisor`无Minecraft/Loader/Balm导入，Fabric JAR无NeoForge元数据或重复条目。

尚未完成修复后的客户端等待页/自动重连复验、Configuration长时间等待、Play→Configuration重配置、监督程序60秒强制终止、大型世界恢复、磁盘耗尽和完整跨Windows/Linux部署验收。

## 许可证

MIT License，作者 GPT 5.6 Sol、Exmeow。
