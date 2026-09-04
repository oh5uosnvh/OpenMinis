# Fork 维护手册（oh5uosnvh/OpenMinis）

本 fork 的目标：**在上游 OpenMinis/OpenMinis 持续更新的前提下，用最小改动快速产出带私有魔改的 APK。**

为此，改动不以「一堆散落的编辑」形式存在，而是一串**可以重放到任意上游版本之上的补丁**（patch series）。

---

## 1. 分支模型

| 分支 | 角色 | 谁来写 |
|---|---|---|
| `main` | **纯上游镜像**。永不手改，只由 sync workflow fast-forward 到 `upstream/main` | 自动 |
| `mods` | **我们的全部改动**，始终 rebase 在 `upstream/main` 之上。CI 从这里构建 APK。**这是本 fork 的默认分支** | 人 + CI |
| `patches/` | 每次成功同步后自动导出的 `.patch` 快照（rebase 失败时的救生艇） | 自动 |

`mods` 被设为**默认分支**，原因是 GitHub 的硬性限制：`workflow_dispatch`（手动点 Run
workflow）只认默认分支上的 workflow 文件。如果默认分支是纯净的 `main`，那我们的
CI 就必须把 workflow 也塞进 `main`，`main` 就不再是纯镜像了。把默认分支换成
`mods` 同时满足两件事：手动触发可用，`main` 保持逐字节等于上游。

`mods` 是一条**会被改写历史的分支**（rebase → force-push）。这是设计使然，不是事故：
它代表「相对于当前上游的改动集」，而不是一条线性开发史。

```
upstream/main ──●──●──●──●  (1.13)          ──●──●──●  (1.14)
                          ╲                          ╲
mods                       ●─●─●  (我们的 4 个补丁)    ●─●─●  (rebase 后同样 4 个补丁)
```

---

## 2. 上游发新版时怎么做

### 方式 A：GitHub Actions（推荐，零本地环境）

1. Actions → **Sync Upstream** → Run workflow
2. 成功：`main` 前进、`mods` 自动 rebase 并 force-push、`patches/` 更新
3. 失败：workflow 会创建一个 Issue，列出冲突文件，并把冲突现场推到
   `rebase-failed/<上游sha>` 分支供手工解决
4. 然后 Actions → **Build Android APK** → Run workflow 出包

### 方式 B：本地

```sh
# 一条命令完成 fetch + main 前进 + mods rebase + 导出 patches
./scripts/fork/sync-upstream.sh

# 只导出补丁快照
./scripts/fork/export-patches.sh
```

---

## 3. 降冲突的三条硬规则

写任何新功能前先读这一节。违反它 = 每次上游更新都要手工合并。

### 规则 1：新功能写进**新文件**

新增 UI/逻辑一律建新文件（本 fork 的魔改文件统一带 `Mods` 后缀或放在 `mods/` 包下）。
新文件永远不会和上游冲突 —— 上游不认识它。

### 规则 2：上游文件里只留「加参数 + 默认值」式挂钩

允许：

```kotlin
fun ProviderDetailScreen(
    instanceId: String,
    // ...上游原有参数...
    showModelsSection: Boolean = true,   // ← fork 挂钩：加参数 + 默认值
) {
```

禁止：重排上游代码块、改上游函数签名的既有参数顺序、大段搬移。
Git 三方合并对「在参数列表末尾加一行」几乎不会冲突；对「搬移代码块」几乎必然冲突。

### 规则 3：挂钩点必须登记在案

**当前 fork 触碰的上游文件（冲突面全集）：**

| 上游文件 | 改动 | 说明 |
|---|---|---|
| `ui/settings/ProviderDetailScreen.kt` | 末尾加 4 个带默认值的参数（`showModelsSection` / `bottomBar` / `showBackArrow` / `title`）；模型区块包一层 `if`；`ApiKeyCredentialBlock` 改为全量明文 + 复制按钮 | 模型区块交给「模型」Tab 渲染 |
| `ui/settings/AddProviderScreen.kt` | Endpoint 段内新增自定义 UA 输入；Key 输入改明文换行；保存时写入 UA + 手动标记，去掉自动全量拉取 | 修上游「添加时没有 UA、添加完才冒出来」的反人类逻辑 |
| `ui/settings/SettingsComponents.kt` | `SettingsScaffold` 加 `bottomBar` 参数（带默认值） | 底部 Tab 的唯一挂钩点 |
| `ui/navigation/AppNavigation.kt` | `PROVIDER_DETAIL` 路由改指向 `ProviderDetailTabbedScreen` | 1 处 |
| `data/repository/ProviderRepository.kt` | `autoRefreshModels` 开头加手动策略跳过 | 防止后台刷新回灌全部模型 |

**fork 独有的新文件（零冲突）：**

- `ui/settings/mods/ProviderDetailTabs.kt` —— 底部「配置 / 模型」固定 Tab + 能力标签
- `ui/settings/mods/ProviderDetailTabbedScreen.kt` —— Tab 宿主，配置页直接复用上游实现
- `ui/settings/mods/ProviderModelsTab.kt` —— kelive 风格模型管理页
- `ui/settings/mods/ProviderModelsFetchSheet.kt` —— 获取模型 + 勾选面板
- `ui/settings/mods/ProviderCatalogFetcher.kt` —— 只读目录抓取（fetch 与写入分离）
- `ui/settings/mods/ForkModelPolicy.kt` —— 「此服务商模型由用户手动管理」标记
- `res/values*/strings_fork.xml` —— fork 专属文案，**不碰上游 strings.xml**
- `.github/workflows/*.yml`、`scripts/fork/*`、`docs/FORK.md`

上游改了上表四个文件的**同一区域**时才会冲突。真冲突了，按 §4 处理。

---

## 4. rebase 冲突了怎么办

```sh
git checkout mods
git rebase upstream/main
# 冲突：
git status                     # 看哪些文件
# 解决后
git add <files> && git rebase --continue
```

判断原则：**上游的实现优先**。我们的补丁只应保留「挂钩 + 新文件引用」，
如果上游把某段逻辑重写了，就在新的上游代码上重新打一次同样的挂钩，
而不是把上游代码回退成旧版。

救生艇：`patches/` 下有上一次成功状态的 `.patch`，可以 `git am -3` 逐个重放，
比在 rebase 冲突现场硬啃更容易看清「我们到底改了什么」。

---

## 5. 构建产物

- Workflow：**Build Android APK**（`.github/workflows/build-apk.yml`）
- 原生依赖在 CI 里现建 + 缓存：
  - `deps/build_proot.sh` → `libproot.so` + 两个 loader（NDK 交叉编译）
  - `scripts/prepare_android_sandbox.sh` → Alpine rootfs 资产
  - `deps/build_rclone_android.sh` → `rclone.aar`（gomobile；失败时自动降级为
    stub，只影响远程备份，其余功能不受影响，构建摘要里会写明）
- `provider-customization.properties` 用 example 空值填充：**能编译能跑**，
  只有 Claude OAuth 登录这一条路径需要真值（详见 `BUILDING.md`）。
- 不修改 `build.gradle.kts` 里的 `versionCode`/`versionName` —— 那是高频冲突点。
  APK 文件名由 CI 用 `git describe` 生成，版本号跟随上游。
