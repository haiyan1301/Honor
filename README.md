# 荣耀优速通观察台 1.2.0

## 下载

- [下载 APK 1.2.0（原生自动模式）](https://github.com/haiyan1301/Honor/raw/refs/heads/main/downloads/HonorFastPassObserver-1.2.0.apk)
- [下载源码包 1.2.0](https://github.com/haiyan1301/Honor/raw/refs/heads/main/downloads/HonorFastPassObserver-1.2.0-source.zip)
- [自动流程与 Hook 说明](docs/native-auto.md)
- [SHA-256](downloads/SHA256SUMS.txt) / [验证记录](downloads/verification-1.2.0.json)

本地验证：41 项判定、37 项原生自动策略/索引、31 项请求/无线证据测试通过，共 109 项；另有 121 项原生 DEX 接口核对及 12 项原生自动架构约束检查通过。与旧版签名一致。新自动链路尚未实机验证。

适配此次提供的系统服务 `com.hihonor.systemserver 70.1.0.1`、设置 `14.0.0.210`，用于 Magic7 Pro / Android 16 / MagicOS 10 的 LSPosed 模块。

1.2.0 改为系统原生自动流程：系统识别场景、生成请求参数、持有回调、鉴权、申请、重试和管理承载。模块只放行已确认自动来源中的本地阻止条件，并观察真实结果。

## 安装与使用

1. 覆盖安装 APK，打开一次观察台。
2. LSPosed 作用域勾选 `com.hihonor.systemserver`、`com.android.settings`、`com.android.phone`。
3. 重启手机，确认面板显示模块后端 1.2.0 和原生版本 70.1.0.1。
4. 打开“启用原生自动优速通条件放行”。该设置首次默认关闭；启用后持久保存，后端重启时读取。
5. 使用符合原生要求的蜂窝网络，进入系统真实识别的游戏、直播、通勤、测速等场景，等待系统自动申请。没有真实场景时等待原生事件。
6. 可独立点击“读取本机 QCI / 5QI”，不发送加速申请。
7. 导出诊断，查看原生场景、QCI、请求参数、放行条件、云端结果和真实承载。

开启/关闭只通知已有自动场景重新评估，不创建虚假场景。关闭放行后，后续判断恢复原生；已经发出的原生请求仍由系统处理。

## 参数与原生流程

模块不再构造 `DBRequestInfo`、指定 Manual、手工设置 QCI/时长/目的地址或直接调用申请接口。原生 `DataAccelerateScene.constructDBRequestInfo()` 构造参数，经原生 `toDBRequestInfo()` 转换后交给 QosManager。

Gaming 可按原生配置申请 QCI 3；其它自动场景按原生配置选择 QCI、时长和目的地址。电信取令牌、移动的另一原生分支、GRS 域名、请求头、签名、云端响应、原生回调及原生重试保持原流程。

模块使用 Xposed 附加元数据关联来源，不向 JSON 添加自动标志，不把 Manual 改名成其它场景。原生手动场景 21 不接管、不放行；模块界面已移除手动发送和原生手动页面按钮。

## 放行哪些条件

仅在模块已启用、目标版本匹配、自动场景来源确认时生效：

- 自动事件中的本地区域/服务期/总开关策略；保留真实产品支持和服务已启动要求。
- 已存在完整原生配置的自动 QoS 场景启用判断。
- 场景进入条件中的总开关、拥塞及本地惩罚等待位。
- QosManager 的本地冷却和 Base/对应原生场景次数。
- 公共业务 1004 的每日次数结果；仅配置已加载时放行，不改写原生计数。

网络连接、真实场景/前台优先级、漫游、VPN、制式、运营商、双卡切换保护、已有 VIP/重复承载、参数有效性、并发、协议同意、令牌和签名保持原生。尚未满足的进入条件记录在 `remainingBlockedBits`。

[自动流程与 Hook 详细说明](docs/native-auto.md)

## 观察与真实 QCI

- 继续独立读取电话进程的 RIL 数据连接默认 QoS 和 QoS 会话，分别显示 LTE QCI 与 NR 5QI。
- 继续观察荣耀电话框架消息 105 / Booster 回调 2001 的专用承载。
- 按该次原生请求的实际 QCI、卡槽、时间与可靠基线判断，不固定使用 QCI 6。
- 云端业务 200 与正确卡槽的新活跃匹配承载共同作为证据；原生结果 1 不单独算成功。
- 无 QoS 字段/无回报时显示未知；云端拒绝保持原样。

新增自动来源读取和 Hook 需要实机验证。RIL 可能不提供默认 QoS；未知不会被填成 6 或 9。此模块不会保证云端允许服务或固定幅度提速。

## 日志及原生记账

记录原生 uid/sceneId/userSceneId、真实 scene/cause/QCI/时长、参数来源、实际脱敏 HTTP body/headers、两层计数原始与有效检查结果、请求前后记账、HTTP/业务返回和承载。

每次 native 请求按对象身份索引，较早请求的回调可在较新尝试出现后继续归档；不会替换原生 Message 或回调。最多索引 24 次请求，导出最多 6 次请求快照和 300 条事件，并保留体积限制。

原生失败仍可能更新计数及冷却。公共次数在超限路径原本不再增加，放行该结果不会自动补写或伪造原生计数；日志明确记录。CHR 与云端自己的权益/频控仍是独立路径。

## 构建与验证

使用 Android Studio/Gradle，或 `build.py` 配合 SDK platform 36、build-tools 36.0.0、Xposed API 82、签名材料构建。传入 `--json-test-jar` 可执行 JVM JSON/无线证据测试；测试 JAR 不打入 APK。

```sh
python3 build.py --android-jar /SDK/platforms/android-36/android.jar \
  --build-tools /SDK/build-tools/36.0.0 --xposed-api /deps/api-82.jar \
  --json-test-jar /deps/json-20240303.jar \
  --key /private/key.pk8 --cert /private/cert.pem
python3 check_native_auto.py --out native-auto-architecture.json
```

`check_contract.py` 核对上传原始 DEX 的方法和字段。静态检查和本地测试不能代替实机运营商验证。

## 历史资料

- [1.2.0 原生自动流程说明](docs/native-auto.md)
- [1.1.0 详细历史交接](docs/HANDOFF.zh-CN.md)
- [申请参数、计数与 QCI 反编译说明](docs/request-audit.md)

旧版与 1.2.0 使用同一签名，可覆盖升级；每次升级应重启目标进程。已发布旧 APK/源码归档保留原文件和校验值。
