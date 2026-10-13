# 内存搜索优化方案

> 针对提交 `53e127b6`（新增支持内存搜索）的"很多游戏搜不到"问题。
>
> **实施状态**：
> - P0-1（值候选解耦）、P0-2（`ANY` 类型）、P0-4（上限只截收集不截遍历）已完成，
>   并一并加上了 P0-1 的安全前提——`java.util.*` 内部结构命中只读（见第九节 9.1）。
> - P3-1 已按**方案 A** 实现：面板加"打开时暂停游戏"开关（默认关），`show()` 时 `MidletThread.pauseApp()`，
>   `minimize()`/`destroy()` 时恢复。走的是游戏的 J2ME 生命周期回调，风险见第八节补充。
> - 其余条目待确认。

---

## 一、现状梳理

当前实现是**基于反射的对象图遍历**（不是原生内存扫描），入口 `MemorySearchPanel.onFirstScan()`：

- 根集合：`MidletThread.getMidlet()`（MIDlet 实例）+ `activity.getCurrent()`（当前 Displayable）
- 静态字段：`AppClassLoader.getDexPath()` → `DexFile.entries()` 拿类名 → `findLoaded()` → 读 static 字段
- 遍历规则（`MemoryScanner`）：
  - `isTraversable()`：数组 + `isAppClass()`（classloader == AppClassLoader 且非 `javax.microedition.` / `ru.playsoftware.` / `android.` / `androidx.`）+ `java.util.` 前缀
  - `processField()`：只有 `ft == type.getFieldClass()` 且 `isAppClass(声明类)` 才当命中
  - `MAX_HITS = 100_000`，命中满后置 `truncated`
  - 基本类型数组走 `deferredArrays`，在 `drain()` **之后**才扫
- 复搜：`rescan()` 只对上一轮命中重读（标准 CE 工作流）

---

## 二、"搜不到"的根因（按影响排序）

### RC1 ⭐ 值候选与"可遍历"判定耦合 —— **最大根因**

`push()` 用 `isTraversable()` 做闸门，而 `isTraversable(String)` / `isTraversable(Integer)` 都是 `false`
→ 这类对象**既不检测、也不遍历，直接被丢弃**。

后果：
- 值只要是**容器成员**（`HashMap` / `HashSet` / `TreeMap` / `LinkedList$Entry` 的 `val` / `next`）就搜不到；
- 只有在 **app 类里直接声明的字段**（`processField` 的 `ft == String.class` 分支）才检测得到。

典型踩坑：金币/库存/任务进度存在 `Vector<HashMap<PlayerId, Integer>>`、`HashSet<Enemy>`、`TreeMap` 里 → **全部搜不到**。

### RC2 ⭐ 命中上限会把整个扫描掐断

```java
private void drain() {
    while (!stack.isEmpty() && !cancel.get() && !truncated) { ... }
}
```

命中满 10 万 → `truncated = true` → **遍历整体中止**，对象图后半部分根本不扫。
搜 `0` / `1` / `100` 这类常见值时，几千个字段就撑爆上限，目标在后面就永远找不到。UI 只会提示"truncated"，用户会以为是精度问题。

### RC3 ⭐ 基本类型数组被排到最后一批

`visit()` 遇到 primitive array → 加入 `deferredArrays`，`run()` 里 `drain()` 结束后才 `scanPrimitiveArray()`。
一旦 RC2 触发 truncation，**所有数组一个都不扫**。而 J2ME 游戏的数值大量在 `int[]` / `float[]` / `byte[]` 里。

### RC4 ⭐ 类型必须猜对，没有"任意类型"

- `processField()`：`ft != type.getFieldClass()` 直接 return
- `isTraversable()`：primitive array 要求 `comp == type.getFieldClass()`

用户选了 `INT` 而值是 `float` / `double` / `short` / `long`（例如 HP 存成 `3.0f`、金币存成 `long`）→ 搜不到。
`ValueType` 有 7 种数值类型，命中率上限约 1/7。

### RC5 装箱类型完全不可见

`Integer` / `Long` / `Float` / `Double` / `Short` / `Byte` / `Boolean` / `Character` 字段
→ `ft` 是引用类型 → 走 `push()` → `isTraversable` false → 丢弃（同 RC1）。

### RC6 根集合太小 + `javax.microedition.*` 走不通

- 只有 2 个实例根。挂在**非当前界面**（`Form` / `List` / 备用 `Canvas`）上的数据，只能靠 MIDlet 直接引出来；
- `isTraversable()` 明确排除 `javax.microedition.` → 从 LCUUI 类（非当前 Displayable、`Form`/`List`/`Table` 的内容）出发的那条链整条断掉。
- 注：这些类编译在**本 APK 内**，不受 Android 9+ 隐藏 API 限制，放开是安全的。

### RC7 父类链字段被截断

```java
for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
    if (!isAppClass(k) && !k.getName().startsWith("java.util.")) break;
    ...
}
```

app 类继承 `javax.microedition.lcdui.*` 时，父类链上的非 static 字段（以及再往上的 app 类字段）不会被收集。

### RC8 字符串只有精确相等

血量显示 `"100/100"`、道具名等场景搜不出，没有 contains / 前缀模式。

### RC9 结果不可区分、不可筛选

`MemoryHit.label()` 只用 `ownerClass + member`（`push(e, node.owner, member)` 不累积路径），
`A.b[0].c` 和 `A.b[1].c` 显示成同一个标签；无类名过滤，1 万条结果只能盲点。

### RC10 扫描与游戏线程并发

扫描时游戏线程照常跑，读到撕裂状态；`CHANGED` / `UNCHANGED` 的语义不稳定（一轮之内值可能变多次）。

### RC11 其它小问题（顺带修）

- 每次首搜都 `new DexFile(dexPath)` 重新打开；`listClassNames` 不支持 `:` 分隔的多 dex 路径（当前 `MicroLoader` 只传单路径，属防御性修复）
- 静态 `final` 字段整体跳过（`isStatic && isFinal`）——语义正确，但被改写的 static final 会漏
- `onReset()` / `destroy()` 与后台扫描线程之间没有统一的 token 收尾

---

## 三、优化方案

### P0 — 搜索引擎正确性（直接决定"搜不搜得到"）

**P0-1 值候选判定与可遍历解耦**（修 RC1 / RC5）
- `MemoryScanner` 新增 `isValueCandidate(Class)`：包装类型（`Integer`/`Long`/`Float`/`Double`/`Short`/`Byte`/`Boolean`/`Character`）+ `String` + `Enum` + `Character`
- `processField()` 对引用类型字段：**先按值候选检测一次命中**，再按 `isTraversable()` 决定是否继续下钻
- 命中即建 `MemoryHit`，`target` = 持有者（`HashMap$Node` 等）、`field` = 该字段 → 读改写天然正确
- 收益：容器里的数值全部可搜，是本次改动收益最大的一项

**P0-2 新增 `ANY`（自动/任意数值）类型**（修 RC4）
- `ValueType` 增加 `ANY`（`MemorySearchPanel.typeSpinner` 靠 `ValueType.values()` 生成，自动出现）
- `ANY` 下：所有数值字段类型都参与判定；`isTraversable()` 对所有 primitive array 放行
- `MemoryHit` 新增 `fieldType`，按**实际字段类型**记录，写回仍走 `ValueType.toFieldValue`
- 收益：用户不用猜 int/float/long，单次数值搜索覆盖率 ×7

**P0-3 基本类型数组内联扫描**（修 RC3）
- 删除 `deferredArrays` 机制，在 `visit()` 里直接扫
- 收益：数组不再受 truncation 影响

**P0-4 命中上限只截断"收集"，不截断"遍历"**（修 RC2）
- `addHit()` 到达上限只跳过入列，`druncated` 不再参与 `drain()` 的终止条件
- 新增独立的**遍历安全上限**（建议 200 万对象，可配置），到顶再停止遍历并标记
- UI 文案改为「结果 ≥ N（已忽略更多命中，请缩小范围）」
- 收益：这是"搜不到"里最容易误解、也最好修的一项

**P0-5 数值/字符串判定补齐**（修 RC8）
- `SearchMode` 增加 `CONTAINS`（字符串包含）、`EQUAL_WITHIN`（浮点容差，`v2` 作为容差）
- 注意：`SearchMode` 的声明顺序必须与 `R.array.memory_search_modes` 严格一致

### P1 — 扩大可达性 + 收敛结果

**P1-1 放开 `javax.microedition.lcdui.*` 遍历**（修 RC6）
- 这些类在本 APK 内编译，不受隐藏 API 限制
- 建议做成默认开启的开关（避免噪音），值字段判定仍保持 `isAppClass` 以免 UI 对象噪音刷屏

**P1-2 根集合扩容**（修 RC6）
- 追加：遍历图时收集到的所有 `Displayable` 实例（二次 push）、`MidletThread` 持有的所有 MIDlet
- **明确不做**：走 `android.view.*` 视图树当根 —— Android 9+ 上 `setAccessible` 会被隐藏 API 策略拦截，不可靠

**P1-3 父类链收集改为完整链**（修 RC7）
- `instanceFieldsOf()` 去掉 `break`，沿完整父类链收集 `app` / `java.util.*` / `javax.microedition.*` 的非 static 字段

**P1-4 结果可筛选、可区分**（修 RC9）
- `MemoryHit` 增加 `path`（构造时按引用累积 `owner.member[3]`）
- 面板加类名 / 字段名过滤框，支持按 owner 排序
- 状态行显示：命中数、**已访问对象数**、是否截断、耗时（`ScanResult` 已有 `visited`，补 `elapsedMs`、`totalHits`）

### P2 — 对象级能力（收益最高，工作量最大）

**P2-1 按类找对象（实例查找器）**
- 输入/补全类名（数据源：`DexFile.entries()`，已有现成能力）
- 返回图中所有存活实例，进入后可浏览并**直接编辑该实例的全部数值字段**
- 价值：解决"我还不知道数值是多少，但我知道它存在 `World`/`Player` 类里"这类搜不到的场景
- 落地形式：复用 `MemorySearchPanel` 的第二个 Tab 或一个独立子面板

**P2-2 批量操作**
- 结果列表多选 → 批量写 / 批量锁（`LockManager.lock` 目前逐个）

**P2-3 锁定写入改到游戏线程执行**
- 现在 `LockManager` 用独立 `HandlerThread` 每 30ms 反射写，与游戏线程存在竞争
- 改为 post 到 `MidletThread` 执行写入（需控制频率，避免拖帧）

### P3 — 稳健性

**P3-1 扫描时暂停游戏**（修 RC10）
- 面板加开关；首搜/复搜前暂停游戏线程，结束后恢复（`MidletThread` 的 pause/resume 基础设施已有）
- 让 `CHANGED` / `UNCHANGED` 语义稳定、读数不撕裂
- 代价：扫描期间游戏画面停住

**P3-2 取消与生命周期收敛**
- `onReset()` / `destroy()` 与扫描线程统一用同一个 token 收尾，扫描线程结束后再改 UI 状态

**P3-3 `listClassNames` 健壮性**
- 支持 `File.pathSeparator` 拆分的多 dex 路径；结果按 dexPath 缓存

---

## 四、明确不做

- **ART 原生堆扫描 / `/proc/self/mem` / ptrace**：Android 上不可行，且无法解释 ART 对象布局
- **通过 `android.view.*` 视图树扩根**：Android 9+ 隐藏 API 限制会直接失败
- **生成测试用例、运行验证**：按约定由你来做

---

## 五、涉及文件

| 文件 | 改动 |
|---|---|
| `memsearch/ValueType.java` | 新增 `ANY`；装箱/枚举解析与写回；`normalize` 兼容装箱 |
| `memsearch/SearchMode.java` | 新增 `CONTAINS`、`EQUAL_WITHIN`（顺序需同步 `R.array.memory_search_modes`） |
| `memsearch/MemoryHit.java` | 新增 `fieldType`、`path`（lombok 注解，不写 get/set） |
| `memsearch/MemoryScanner.java` | 主要改动：值候选判定、ANY、数组内联、上限解耦、父类链、可遍历集合 |
| `memsearch/ScanResult.java` | 新增 `totalHits`、`elapsedMs` |
| `memsearch/MemorySearchPanel.java` | 过滤框、统计、暂停开关、批量操作、实例查找器入口 |
| `memsearch/LockManager.java` | 写入改到游戏线程（P2-3） |
| `res/layout/memory_search_panel.xml` | 过滤框、统计/暂停控件 |
| `res/values/strings.xml`、`res/values-zh-rCN/strings.xml` | 新增文案与 modes 数组项 |
| `MicroActivity.java` / `MidletThread.java` | 暂停钩子（P3-1） |

新增类均使用 lombok 注解。

---

## 六、风险

- P0-1 会显著增加命中数（所有装箱字段都成为候选）→ 靠 P0-4 的上限与类型过滤控制
- P1-1 会把 LCUUI 对象带进遍历，噪音增加 → 做成开关，默认状态需确认
- P0-4 全图扫描可能变慢 / 占内存 → 需要遍历对象数上限（建议 200 万）与命中收集上限（建议 50 万，UI 展示 1 万）
- P3-1 暂停游戏会改变游戏行为（部分游戏暂停逻辑敏感）→ 默认关闭

---

## 七、需要你确认的点

1. **P2-1「按类找对象」要不要做？** 工作量最大，但对"搜不到"收益最高（不需要知道数值）。
2. **P1-1 遍历 `javax.microedition.lcdui.*` 默认开还是做成开关？**
3. **上限数值**：命中收集 50 万 / UI 展示 1 万 / 遍历对象 200 万 —— 可以吗？
4. **P3-1 扫描时暂停游戏** 要不要？（默认关闭）
5. **P0-5 的 `EQUAL_WITHIN` 浮点容差** 要不要一起做，还是先只做 `CONTAINS`？
6. 实施顺序建议 **P0 → P1 → P2 → P3**，是否可以先只做 P0 验证效果？

---

## 八、收益排序（回答"哪几个最值"）

| 排序 | 项 | 覆盖率收益 | 改动量 | 结论 |
|---|---|---|---|---|
| 1 | P0-1 值候选解耦 | 极高：容器里的数值从"完全不搜"变"可搜" | 小（`processField` 一个分支） | **必做** |
| 2 | P0-4 上限只截收集不截遍历 | 极高：修掉最容易被误判的静默中断 | 极小（几行） | **必做** |
| 3 | P0-2 `ANY` 类型 | 高：数值类型覆盖 ×7，不用猜 | 中 | **必做** |
| 4 | P0-3 数组内联 | 低：数组被 defer 排在最后，只在命中多、扫描慢时更明显；正确性上不丢 | 极小（删机制） | 顺手做 |
| 5 | P2-1 按类找对象 | 最高（解决"不知道数值"） | 大（新交互） | P0 见效后再做 |
| 6 | P1-1 / P1-3 遍历与父类链 | 中 | 小 | 顺带做 |
| 7 | P1-4 筛选与完整路径标签 | 中（不修覆盖率，修可用性） | 中 | 可后做 |
| 8 | P0-5 / P3-1 / P3-3 / P2-2 | 低-中 | 小 | 按需 |

**建议：P0 的 1~4 打包成一次改动**，全部集中在 `MemoryScanner.java`（约 80 行改动）+ `ValueType.java`（约 40 行）+ `MemoryHit`/`ScanResult` 各加 2 个字段 + 面板统计文案，合计约 150 行。这一批做完，"搜不到"应该从常见变成偶发。

---

## 九、内存搜索本身的问题（新增评估，含一个必须修的坑）

### 先说好消息

J2ME 游戏没有 native 堆，全部游戏逻辑跑在 ART 上的 Java 对象里，反射在原理上**能看到根集合可达的一切状态**。也就是说当前的搜不到是**算法缺口，不是天花板**，修算法是有效的投入。

### 9.1 ⚠️ 必须修：写穿 `java.util.*` 内部结构会让游戏数据结构自毁

P0-1 落地后，`HashMap$Node` / `TreeMap$Entry` 的字段会成为命中来源。这些节点**同时有 `key` 和 `val`**：
- 写 `val` 是安全的（不动键，map 的查找不变）；
- **写 `key` 会破坏该 map 的不变量** → 后续 `get` 永远查不到、`put` 产生重复键、游戏逻辑直接错乱。

对策：给 `MemoryHit` 加只读标记 —— **声明类在 `java.util.*` 的命中一律只读（可搜索/查看，禁用写入与锁定）**，`MemorySearchPanel` 对只读命中隐藏「写入/锁定」按钮。这一条应并入 P0。

同类风险一并处理：
- `final` 实例字段当前允许反射写入，建议只读（游戏用 final 的字段往往参与构造期不变量）；
- **枚举命中只读**：换一个 enum 常量会让游戏自己的 `switch` / 状态机走进未预期分支。

### 9.2 写入"自伤"游戏逻辑（不是崩溃，是更难查的逻辑错乱）

- 写死值绕过游戏自身的边界校验：HP 拉到超过 `maxHp` 后，游戏可能自己 `clamp` 回原值（用户以为没生效），或在 `score / level`、`index / count` 这类表达式上触发除零、越界、`NaN`，表现是游戏自己卡死或闪退。
- 反射写发生在**非游戏线程**，游戏不会重新做一致性检查，所以这类问题不会立刻报错，而是几个回合后才爆。
- 缓解：给「锁定」加**原值备份 + 一键还原**（`MemoryHit` 存 `originalValue`），让误操作可逆；这是比"防止误操作"更现实的做法。

### 9.3 内存：扫描会 pin 住整个游戏堆（当前已有问题）

`MemoryHit.target` 是强引用，`hits` 列表在面板存活期间一直持有 → **首搜之后，整个可达游戏对象图都无法被回收**，直到 Reset 或面板销毁。10 万条命中时这可能接近堆上限。
对策：P0-4 的命中收集上限（建议 50 万，UI 展示 1 万）是硬需求；另外 `destroy()` 已调 `unlockAll()`，但要确认 `hits` 列表也一并清空。

### 9.4 平台限制（决定方案边界，别再花时间试）

- **`android.*` 不可遍历**：Android 9+ 隐藏 API 策略会抛 `InaccessibleObjectException`，所以"从视图树扩根"这条路是死的（已列入不做）。
- **看不到**：`RecordStore` 存档数据、JNI/native 内存、其他进程 —— 不过存档已有独立的备份恢复功能覆盖。
- **`DexFile` 已 deprecated** 但可用；`Field.setAccessible(true)` 对 app 类无限制（app 代码不受隐藏 API 约束），这是当前方案能跑通的前提，短期内不会变。

### 9.5 并发

- 扫描线程读与游戏线程写并发：结构性异常已在 `visit()`/`rescan()` 按节点 catch，可控；但 32 位设备读写 `long` 字段存在撕裂可能（概率低）。
- `LockManager` 用独立 `HandlerThread` 每 30ms 写，与游戏线程竞争。改到游戏线程执行（P2-3）能消掉竞争，但需控频率避免拖帧。

### 9.6 产品侧（供决策，不是技术问题）

- 这是**内置在加载器里的作弊工具**，会影响带存档完整性校验的游戏。
- 崩溃归因：锁定导致的闪退，日志看起来是加载器的锅，用户会往加载器上归因。建议失败/异常时明确提示"由内存锁定导致"。
