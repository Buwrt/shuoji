# 紫罗兰工具箱（手机版）VioletToolBox Mobile

一款**手机对手机**刷机工具：主手机装上本应用，用 OTG 线直连副手机，即可完成
线刷、单刷 boot / init_boot、ADB 与 Fastboot 命令操作，**不需要电脑、不需要 Root**。

功能设计参照 PC 端 [Smart-Paocai/VioletToolBox](https://github.com/Smart-Paocai/VioletToolBox)（GPL-3.0）。

---

## 一、支持范围

| 品牌 | 支持内容 |
|---|---|
| 小米 / Redmi | 官方 fastboot 线刷包（`flash_all.bat` / `flash_all.sh` 直接解析执行）、boot / init_boot 单刷、解锁提示 |
| OPPO | fastboot 散包一键刷、init_boot 修补刷入、fastbootd 自动切换 |
| 一加 OnePlus | 同上，额外支持 DDR / 基带信息读取 |
| 真我 realme | 同上 |
| 其它机型 | 通用 fastboot 分区刷写、ADB 管理、命令终端 |

> 说明：OFP / OPS / OZIP 等 OPPO 专有封装格式无法直接刷写，请先用电脑端工具
> 转换为 fastboot 散包（images 目录）后再使用本应用。

## 二、功能清单

1. **首页与设备连接**：自动识别副手机处于 ADB（开机）还是 Fastboot / FastbootD，USB 权限自动申请，插拔自动重连。
2. **ADB 工具箱**：设备信息一键采集（品牌 / 型号 / 代号 / 版本 / 槽位 / 基带 / DDR）、重启到系统 / Recovery / Bootloader / FastbootD / EDL、安装 APK、推送文件、自定义 shell 命令、小米强开 USB 调试。
3. **Fastboot 工具箱**：`getvar all`、单分区刷写、boot / init_boot / vendor_boot / vbmeta / recovery 快捷刷写、临时启动镜像（不写入）、擦除分区、解锁 / 回锁、A/B 槽位切换、自定义命令。
4. **单刷 boot / init_boot**：按文件名自动识别目标分区，可附带刷入 vbmeta 并关闭校验（`--disable-verity --disable-verification`）。
5. **品牌线刷中心**：选择线刷包目录 → 自动解析 `flash_all` 脚本或按镜像名生成刷写计划 → 一键线刷；刷 super 等动态分区时自动切到 FastbootD 并等待设备重连后继续。
6. **命令终端**：支持 `adb shell xxx`、`fastboot xxx` 及裸命令。
7. **无线 ADB**：同一 WiFi 下通过无线调试控制副手机（Android 11+ 需先配对）。
8. **大镜像分段**：超过设备 `max-download-size` 的镜像（如 4~8GB 的 super.img）会自动切成 sparse 分段逐段刷写。
9. **ROM 解包（手机端直解，不用电脑）**：
   - 官方 OTA `zip` → 自动取出 `payload.bin` 并解出 boot / init_boot / vbmeta / system 等分区镜像；
   - 单独 `payload.bin` 直接解；支持 REPLACE、REPLACE_XZ、REPLACE_BZ、ZERO（XZ 用 `org.tukaani:xz`，BZ2 用 commons-compress）；
   - sparse 镜像还原为 raw（内置 simg2img 等价实现），scatter / super 多分片可按序合并成 `super.img`；
   - OPPO / 一加 / 真我的 `ofp` / `ops` / `ozip`：先尝试按 ZIP 直解，再对明文部分做签名扫描提取；加密部分支持**导入密钥表**后解密（密钥由用户从 oppo_decrypt 等项目自行获取导入，本工程不内置任何厂商密钥）。
10. **ADB Sideload**：副手机进 Recovery 选「Apply update from ADB」后，主手机直接推送官方 OTA 包升级/救砖。
11. **连接诊断与协议自检**：一键检查 OTG 能力、USB 权限、设备枚举；内置协议层自检（ADB 报文 CRC/magic、RSA 密钥签名验签、Fastboot 响应解析、protobuf 解析、sparse 往返、payload 解包往返），并附带真机连通性验证（`getvar` / 读设备信息）。

## 三之二、ROM 解包怎么用

1. 首页点 **ROM 解包（手机端）**。
2. 选择官方 OTA 的 `zip`（一加 / 小米 / Pixel 等）或 `payload.bin`、`ofp`、`ops`、`ozip`。
3. 「只解这些分区」留空=全部；只想刷 Root 时填 `boot,init_boot` 可省大量时间和空间。
4. 点「开始解包」，产物在 `Android/data/com.violet.toolbox/files/unpacked/`。
5. 解出的 `boot.img` / `init_boot.img` 可直接进「单刷 boot / init_boot」刷入；解出完整镜像后可进「品牌线刷中心」一键线刷。
6. 若是 `super.0.xxx.img` 这类分片，用「合并 super 分片」合成 `super.img`（不同运营商 / 地区的分片不要混着合并）。

## 三之三、副手机开不了 USB 调试怎么办

有两条路不依赖 USB 调试：

| 路径 | 条件 | 能做什么 |
|---|---|---|
| **Fastboot 直连** | 副手机能进 fastboot（关机后按「音量下 + 电源」，一加 / OPPO 多为「音量上 + 电源」） | Bootloader 走 fastboot 协议，不需要系统授权，可直接刷分区、解锁、切槽 |
| **Recovery + Sideload** | 副手机能进 Recovery（「音量上 + 电源」） | 选「Apply update from ADB」后由 Recovery 提供 sideload 服务，可推官方 OTA 整包升级 / 救砖 |

另外「连接诊断与自检」页会逐项判定：主手机是否支持 USB Host、OTG 开关是否打开、USB 权限是否已授予、副手机处于 ADB 还是 Fastboot，并给出对应的中文修复建议。

## 三之四、关于「协议层没跑过真机联调」

协议栈的正确性由两层保障：

1. **离线验证**：`tools/verify_protocols.py` 是与 App 内自检同构的 Python 参考实现，可直接运行：
   ```bash
   python3 tools/verify_protocols.py
   ```
   当前结果：6 项全部通过（ADB 报文往返与损坏识别、sparse 切段与还原、payload.bin 解包 REPLACE / REPLACE_XZ、Fastboot 四类响应解析）。
2. **真机一键自检**：装好 APK 后先点「诊断 / 自检 → 运行协议层自检」，在你的手机上把同样的算法跑一遍（含 RSA-2048 密钥签名验签），确认通过再用「真机连通性验证」读一次 `getvar` 或设备信息，通过后再动刷写。这样可以把「工具问题」和「刷机问题」彻底分开。

## 三、使用方法

1. 主手机安装 APK 并开启 OTG（部分机型在「设置 - 系统 - OTG 连接」）。
2. 副手机打开「开发者选项 - USB 调试」。
3. 用 OTG 转接头 + 数据线（或双头 Type-C 线）连接两台手机。
4. 主手机弹出 USB 授权要点「允许」；首次连接时副手机会弹出 RSA 指纹确认，勾选「一律允许」后点「允许」。
5. 首页显示「已连接」后即可进入对应功能页。

## 四、本地构建 APK

### 方式一：Android Studio（推荐）

用 Android Studio（Koala 及以上）打开本目录，等待 Gradle 同步完成，
点击 **Build → Build Bundle(s)/APK(s) → Build APK(s)**，
产物在 `app/build/outputs/apk/debug/app-debug.apk`。

### 方式二：命令行

需要 JDK 17 与 Android SDK（命令行已配置 Gradle 8.7）。

```bash
# 1. 指定 SDK 路径（写入 local.properties，或用 ANDROID_HOME 环境变量）
echo "sdk.dir=$ANDROID_HOME" > local.properties

# 2. 生成签名密钥（可选，仅 release 需要）
keytool -genkey -v -keystore violet.jks -keyalg RSA -keysize 2048 -validity 10000 -alias violet
cat > keystore.properties <<'EOF'
storeFile=../violet.jks
storePassword=你的密码
keyAlias=violet
keyPassword=你的密码
EOF

# 3. 构建
./build.sh debug     # 调试版
./build.sh release   # 发布版（有 keystore.properties 时自动签名）
```

产物：
- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/release/app-release.apk`

### 方式三：GitHub Actions 自动构建（最快拿到 APK）

仓库内已附带 `.github/workflows/build-apk.yml`。
把本工程推到 GitHub 后，Actions 会自动编译并在任务详情页输出
`VioletToolBoxMobile-debug` 与 `VioletToolBoxMobile-release` 两个 APK 制品，直接下载即可安装。

## 五、技术与开源参考

本项目协议层为纯 Java 实现，未引入任何第三方 ADB / Fastboot 库，
实现过程参考了以下开源项目（遵循其许可证）：

| 项目 | 许可证 | 参考点 |
|---|---|---|
| [Smart-Paocai/VioletToolBox](https://github.com/Smart-Paocai/VioletToolBox) | GPL-3.0 | 功能划分、品牌流程、交互文案 |
| [google/fastboot-mobile](https://github.com/google/fastboot-mobile) | Apache-2.0 | fastboot over USB OTG 协议 |
| [RohitVerma882/fastboot-java](https://github.com/RohitVerma882/fastboot-java) | Apache-2.0 | fastboot-java 接口设计 |
| [cgutman/AdbLib](https://github.com/cgutman/AdbLib) | BSD | ADB 报文格式与 RSA-2048 认证（AdbCrypto 等价实现） |
| [flyfishxu/Kadb](https://github.com/flyfishxu/Kadb) | Apache-2.0 | ADB 客户端分层结构 |
| [sky22333/skyadb](https://github.com/sky22333/skyadb) | GPL-3.0 | 手机对手机 ADB / OTG 交互模式 |
| [rhythmcache/Dioxamine](https://github.com/rhythmcache/Dioxamine) | Apache-2.0 | 手机端 Fastboot 刷写流程 |
| [aShell You](https://github.com/DP-Hridayan/aShellYou) | GPL-3.0 | 手机端 Shell / Fastboot 终端交互 |

**本项目采用 GNU General Public License v3.0（GPL-3.0）授权**，详见 LICENSE。

## 六、已知限制（及本工程给出的应对）

| 限制 | 当前状态 | 应对方式 |
|---|---|---|
| 主手机必须支持 OTG | 硬要求，无法绕过 | 「连接诊断」自动判定是否支持、是否需开 OTG 开关；不支持时提示换机 |
| 副手机要开 USB 调试 | 仅 ADB 模式需要 | Fastboot 直连与 Recovery Sideload 都不需要 USB 调试，详见「三之三」 |
| OFP / OPS 加密封装 | 明文与 ZIP 结构可直接解；加密部分需要密钥 | 支持导入密钥表（从 oppo_decrypt 等项目自行获取）；无法解密时建议电脑端 oppo_decrypt 转 fastboot 散包 |
| payload.bin 解包 | 已内置 | 支持 REPLACE / REPLACE_XZ / REPLACE_BZ / ZERO；**增量包**（含 SOURCE_COPY / SOURCE_BSDIFF）离线无法还原，需官方全量包或走 Sideload |
| ZSTD 压缩的 payload | 暂不支持（Android 17 新增 REPLACE_ZSTD） | 会给出明确中文提示，建议改用全量包或 Sideload |
| 高通 EDL（9008） | 仅提供重启入口 | 未实现 Sahara / Firehose，深砖请用电脑端工具 |
| 协议栈真机联调 | 未做过多机型联调 | 提供离线验证脚本 + App 内一键自检 + 真机连通性验证三步，见「三之四」 |

刷机有风险，操作前请务必备份数据；因使用本工具导致的设备损坏或数据丢失，作者不承担责任。

## 七、新增依赖说明

| 依赖 | 用途 | 许可证 |
|---|---|---|
| `org.tukaani:xz:1.9` | 解压 payload 中的 XZ（LZMA2）数据 | Public Domain |
| `org.apache.commons:commons-compress:1.26.1` | 解压老版本 payload 的 BZip2 数据 | Apache-2.0 |

两者都是纯 Java 实现，不含 native 库，在 Android 8.0（minSdk 26）及以上可直接运行。
