# 运行时插件契约

宿主 APK 不打包任何重运行时（Python / Node.js / QuickJS / 迅雷 / mpv / BD-J），这些能力全部以**独立签名、独立打包、可在线安装**的插件 APK 形式存在。宿主只负责调度、校验与加载。本文记录两侧必须共同遵守的约束——改这里任何一条，都要同时改宿主与发布链路。

## 1. 构建插件

```bash
cd runtime
./gradlew assembleRelease -PtvRuntimeModule=python -PtvPythonVersionCode=114 -PtvPythonVersionName=1.1.4
```

`-PtvRuntimeModule` 只接受 `python|nodejs|quickjs|thunder|bdj|mpv`；每个模块的版本来自 `tv<Module>VersionCode` / `tv<Module>VersionName`，缺省直接构建失败（`runtime/build.gradle:22`）。产物为 `runtime/build/<module>/outputs/apk/{arm64,arm32}/release/runtime-<module>-<abi>.apk`，每个模块必须同时产出两个 ABI。

## 2. 信任链

宿主接受一个插件 APK 需要同时满足五条，缺一即拒装：

| 条件 | 位置 |
|---|---|
| 签名证书与宿主**逐项相同**（不是“包含”，是数量与内容都一致） | `RuntimeModule.verifyPackage` |
| 包名为 `com.fongmi.runtime.<id>`，且无 split | 同上 |
| `assets/runtime.json` 的 module / packageName / versionCode / versionName / abi 与 APK 自身一致 | `verifyMetadata` |
| `assets/runtime.json` 的 compatibilityId 与宿主期望一致 | `verifyMetadata` |
| 每个 payload 的路径、大小、SHA-256、ELF 架构、`.so` 是否为 STORED 全部匹配 | `verifyPayloads` |

因此 **签名密钥是整条链的单点**：`local.properties` 里的 `storeFile` 同时用于 debug、release、全部插件（`runtime/build.gradle:99`）。它必须留在仓库外。密钥一旦泄露，攻击者可以签发任何宿主愿意安装的插件。

插件**必须开启 V1+V2、关闭 V3+V4**（`runtime/build.gradle:104`）。这不是偏好：宿主用 `getPackageArchiveInfo(..., GET_SIGNING_CERTIFICATES)` 读取归档签名，关闭 V3/V4 才能保证读到的 signer 集合与安装后一致。

## 3. compatibilityId

| 模块 | 来源 |
|---|---|
| python | `catvod-python-plugin-v4` |
| nodejs | `catvod-nodejs-plugin-v3` |
| quickjs | `fongmi-quickjs-ng-0.17-v13` |
| thunder | `catvod-thunder-plugin-v1` |
| bdj | `libbluray-1.4.1-openjdk8-zero-v1` |
| mpv | `mpv-<hash>`，**构建期动态生成** |

前五个是 `AbiApkPackaging.RUNTIME_PROFILES` 里的常量，改它们等于让所有旧插件失效，必须同步升级版本号并重发。

mpv 是唯一例外：`PrepareRuntimeTask` 用 `MpvNativeLibraries.compatibilityId(mpvAar, ffmpegAar)` 从两个 AAR 的原生库哈希算出 `mpv-xxxxxxxxxxxxxxxx` 写进插件元数据；宿主侧由 `GenerateMpvCompatibilityTask` 从同样的 AAR 生成 `MpvCompatibility.ID`，`App.onCreate` 调用 `RuntimeModule.MPV.setCompatibilityId(...)` 覆盖默认值。**两侧必须使用同一对 AAR**，否则宿主算出的 ID 与插件不一致，`RuntimeInstaller.selectAsset` 会把该模块当成“未发布”静默跳过（界面表现为永远装不上，而不是报签名错误）。

mpv 插件还在元数据里声明宿主 `libc++_shared.so` 的 SHA-256（`hostLibraries`），加载前逐字节比对（`verifyHostLibrary`）。宿主换 NDK 或换 `libc++_shared.so` 会直接让旧 mpv 插件失效——这是有意的，避免运行期 `dlopen` 崩溃。

## 4. payload 布局

`assets/runtime.json` 的 `files` 必须与 APK 内容严格对应，多余或缺失都会被拒：

| 模块 | 必需 payload |
|---|---|
| python | `lib/<abi>/` 下 9 个 `.so` + `assets/chaquopy/{app,bootstrap,stdlib-common,stdlib-<abi>,requirements-common,requirements-<abi>}.imy`、`build.json`、`cacert.pem` |
| nodejs | `lib/<abi>/{libnode,libnodebridge}.so` + `assets/nodejs/bootstrap.js` |
| quickjs | `lib/<abi>/libquickjs-wrapper.so` + `assets/js/lib/{net,spider}.js` |
| thunder | `lib/<abi>/{libxl_stat,libxl_thunder_sdk}.so` |
| mpv | `lib/<abi>/{libmpv,libplayer}.so`，且**不允许多余的 `.so` 或 `.dex`** |
| bdj | 仅 `assets/bdj/runtime.zip`（STORED），内含 `j2re-image/lib/{aarch64,arm}/` 的 JVM 库与 `rt.jar`，且不得含 `lib/`、`.dex` |

路径规则：`lib/` 下必须精确等于 `<abi>/<清单内文件名>`；资产必须落在模块专属前缀下，且禁止 `..`、`\`、`//`、结尾 `/`。所有 `.so` 必须 STORED 未压缩，否则无法直接 mmap，构建期与安装期都会拒绝。

## 5. catalog 与镜像

`catalog.json`（schema=1）是唯一的发布清单，宿主按 ABI、`minSdk`、`versionCode` 选最大可用版本。约束：

- 每个模块必须有两个 ABI 的条目，`url` 与每条 `mirrors` 的文件名必须都是 `runtime-<id>-<versionName>-<abi>.apk`。
- `url` 是主源，`mirrors` 是顺序回退；宿主按 `url` → `mirrors` 依次尝试，失败才换下一个。
- 版本不允许回退；同一 versionCode 不允许换 versionName 或换 APK 字节。

当前发布三源：GitHub Release（主）、`ghfast.top`、`gh-proxy.com`（后两者为第三方 GitHub 代理，需保持可探测）。宿主读取的 catalog 地址本身也带镜像：`app/build.gradle` 的 `tvRuntimeCatalog` / `tvRuntimeCatalogMirror` 以 `|` 拼接写入 manifest，`RuntimeInstaller.getCatalogUrls` 依次重试。所以**只改 catalog 内容不改构建属性**，国内网络仍可能卡在第一步。

`catalog/catalog.json` 是入库的只读快照，供 CI 校验与审计，不参与分发；发布时用 `--record` 更新。

## 6. 发布与校验

```bash
# 1. 收产物 + 生成清单（可多次 --url-base 追加镜像）
py -3 scripts/publish_runtime.py --collect \
  --aapt2 <sdk>/build-tools/37.0.0/aapt2.exe \
  --url-base https://github.com/Diaojiangjun/media/releases/download/module \
  --url-base https://ghfast.top/https://github.com/Diaojiangjun/media/releases/download/module \
  --url-base https://gh-proxy.com/https://github.com/Diaojiangjun/media/releases/download/module \
  --github-repo Diaojiangjun/media --netdisk Release/netdisk --record catalog/catalog.json

# 2. 发布前校验：结构 + 已发布 APK 逐字节复核（需要本地 APK 目录与 aapt2）
py -3 scripts/runtime_catalog.py --verify catalog/catalog.json --artifacts Release/module --aapt2 <aapt2>

# 3. 发布后健康检查：线上清单 + 每条 url/mirror 的真实尺寸
py -3 scripts/runtime_catalog.py --verify-url <catalog-url> --check-downloads
```

`--artifacts` 会用 `read_apk` 重新解析每个 APK 的元数据、payload 哈希、ELF 架构与 BD-J 归档 CRC，并与清单逐字段比对——这是唯一能在发布前发现“清单写错字节”的手段。

## 7. CI

`.github/workflows/build.yml` 三个 job：

- `catalog`：校验 `catalog/catalog.json`，任何 URL、镜像、版本、兼容性字段写错都会失败。
- `unit-test`：`./gradlew :catvod:testDebugUnitTest`，覆盖 `RuntimeModule` 的校验分支。
- `assemble`：`./gradlew :app:assembleRelease`。**默认会被跳过**——`app/libs/lib-*.aar`（私有 media3、mpv、ffmpeg）被 `.gitignore` 排除，干净检出无法编译 app。把它们打成 `player-libs.zip` 上传到 Release，再设置仓库变量 `PLAYER_LIBS_URL` 即可启用；CI 会自己生成一次性签名密钥，产物仅用于验证可构建性。

`.github/workflows/catalog-health.yml` 每周探测线上一份清单在三个源上的结构与下载尺寸，代理挂掉会变成红色。

## 8. 排查

| 现象 | 原因 |
|---|---|
| 模块显示“未发布”，但 Release 里明明有 APK | compatibilityId 不匹配（尤其 mpv 的两个 AAR 不是同一对），`selectAsset` 静默丢弃 |
| 装到一半失败、重试后仍失败 | 签名不一致；宿主只接受与自身完全相同的证书 |
| 下载完成但校验失败 | 镜像返回了旧字节，或 catalog 的 sha256/size 与 APK 不符 |
| mpv 装上但加载崩溃 | 宿主 `libc++_shared.so` 已变，`hostLibraries` 校验本应拦住；若被绕过说明元数据被改 |
| 国内无法拉取清单 | 只配了 GitHub 主源；需同时设置 `tvRuntimeCatalogMirror` |

## 9. 未完成的三件事

按投入产出排序，细节见下：

1. **ffmpeg 下沉为插件**（预计省 30 MB）：宿主仍打包 `libffmpeg.so` + Dovi + `libffmpegJNI.so`。已验证 fork 暴露了 `FfmpegLibrary.setLibraries(String...)`，但它的 `LibraryLoader` 实现仍是 `System.loadLibrary(name)`——只认库名、不认绝对路径，而插件的 `.so` 只存在于插件 APK 的 `lib/<abi>/` 里，不在宿主的 native 搜索路径上。所以下沉的前提是 media3 fork 增加一个加载器注入点（例如 `FfmpegLibrary.setLoader(LibraryLoader)`），再在 `App.onCreate` 里用 `System.loadAbsolute` 指向 `RuntimeModule` 解析出的插件路径。这也是为什么它被排在 media3 可复现构建之后。
2. **拆分 `PlayerManager`（1302 行）**：建议按“选源（engine 选择 + 回落）”“生命周期（prepare/start/stop/release）”“状态同步（进度、字幕、轨道的 UI 回调）”切成三个协作者，`PlayerManager` 只保留门面。前提是先给 `PlayerEngineFactory` 的选路规则补表驱动测试，否则无法验证重构未改变行为。
3. **私有 media3 可复现**：`app/libs/lib-*.aar` 共 9 个来自同一 fork，仓库内无源码，历史构建无法重放、安全补丁无从审计。要么把 fork 源码以子模块入库并自建 `media3-custom` 构建，要么建立版本化二进制仓库并附 `SHA256SUMS`，由 `verifyMpvJni` 一类任务在构建期校验。在此之前，`compatibilityId` 机制只能保证“装错插件被拒”，保证不了“插件本身可信”。
