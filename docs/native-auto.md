# 1.2.0：系统原生自动优速通与条件 Hook

本版按用户要求，把“模块构造 Manual 并直接提交”改为“系统自动场景产生原生申请，模块仅观察与放行本地条件”。原始服务版本基线仍为 70.1.0.1。

## 原生请求链路

```text
系统真实用户/地理/业务场景事件
→ DataAccelerateManager 创建原生 DataAccelerateScene
→ 原生状态机评估进入条件
→ 原生 connectQosCloudService / startQosReq
→ 原生 constructDBRequestInfo
→ 原生 DBRequestInfoForTrans.toDBRequestInfo
→ 原生 QosManager / 电信或移动分支
→ 原生公共执行器 / JSON / 请求头 / 签名 / HTTP
→ 原生回调 / 状态机 / 重试 / 承载生命周期
```

模块使用附加实例元数据与线程作用域关联上述来源，保留原始对象和原始 Message。没有添加、替换或重写申请参数，没有自己的申请入口，也不自行启动/停止专用承载监测。

“自动”体现在原生场景、QCI、原因、目的地址等真实参数，而不是给旧 Manual 请求新增一个 isAuto 字段或改名。

## 支持的原生自动场景

| sceneId | 原生 scene | 已上传配置中的 QCI |
| --- | --- | --- |
| 7 | Gaming | 3 |
| 8 | Railway | 6 |
| 9 | Metro | 6 |
| 10 | Live | 6 |
| 14 | Commute | 6 |
| 15 | SpeedTest | 6 |
| 16 | FlashSale | 6 |
| 19 | Grab | 6 |

实际值由手机运行中的原生配置决定。模块仅放行已存在、完整、动作 8、异步方式且 QCI 3/6 的这些映射场景。场景 18 没有在已分析的原生映射中定义，因此不编造 Campus；场景 21/Manual 不纳入自动放行。

游戏名单、前台 UID/用户场景、地理场景等仍由系统识别。没有真实事件时等待系统；开启模式只通知已有场景在原生线程重新评估，不创建场景、不直接发申请。

## Hook 范围

| 位置 | 放行范围 | 保留范围 |
| --- | --- | --- |
| DataAccelerateManager.isQosRequestNeeded | 真实自动事件调用中的本地国内/服务期/总开关策略 | 产品支持、管理器已启动；手动事件不放行 |
| DataAccelerateManager.isSceneEnabled | 已存在完整自动 QoS 配置的启用条件 | 配置缺失、非 QoS、未知映射、Manual |
| DataAccelerateScene.isSatisfyEnterCondition | 只在计算结果中补足 512、2048、2097152、4194304 四类本地位 | 真实场景/优先级、网络、非漫游、制式、VPN、运营商、双卡和已有 VIP 等其它位 |
| isNonLagPenaltyDuration | 自动场景的本地惩罚谓词 | 不改写原生计时器 |
| QosManager.checkIfQosReqLegal | 自动来源同次调用的冷却和 Base/对应场景次数 | 服务启动、参数、并发、重复 IP/QCI |
| CloudReqTaskExecutor.isExceedMaxInterTimes | 业务 1004、自动来源、配置已加载时的每日上限结果 | 缺少配置、协议同意、公共并发、鉴权和签名 |

位含义：512=QoS 总开关，2048=链路拥塞，2097152=无本地惩罚等待，4194304=RTT 拥塞。只对 `isSatisfyEnterCondition` 的局部计算应用掩码，不写入原生 enterCondition/配置字段；`remainingBlockedBits` 记录尚未满足的真实条件。

所有放行都受模式开关、版本匹配和自动来源确认限制。关闭后未来判断恢复原生；系统已经发出的请求和已有承载仍按原生生命周期处理。

## 实际携带参数

- `scene` 取原生场景枚举，例如 Gaming、SpeedTest，目标 `qciType` 取原生配置。
- `qosCause` 沿用原生 Scene / IpChanged / DestinationIpChanged 等原因。
- 目的地址由原生 UserSceneProxy 按 UID/用户场景取得；模块不再传固定空列表。
- duration、PLMN、RAT、蜂窝地址均由原生构造逻辑决定。
- 电信 AppID/令牌、非电信原生分支、设备 UUID、GRS、请求头与 HMAC 均沿用系统。
- 原生 JSON 中没有新增模块字段。Xposed 附加元数据保存在对象外的关联表，不参与 Gson 序列化。

手动来源或无法确认自动来源的原生请求会记录为 unmanaged，保持系统原样，不被本模块改写成自动请求。

## 计数与结果

QoS 入口放行不阻止原生完成记账或新冷却。公共计数在未超限时仍走原生递增；在原生超限路径中，本来就不会继续递增，模块只改布尔阻止结果，不重置、补写或伪造该计数。诊断分别保存 rawExceeded、effectiveExceeded、bypassed 和 counterNote。

改变本地条件不代表云端允许服务。HTTP 状态、原始业务码及消息保持不变；202 等拒绝仍会显示。

成功证据按该次真实请求的 QCI 匹配，Gaming 的 QCI 3 不会用 QCI 6 替代。卡槽在构造到 QosManager 之间发生变化时标记归因不确定。未知前态、旧回报、其它 SIM 和原生本地成功映射不单独构成确认成功。

## 新实现文件与观测

- `NativeAutoHooks.java`：真实自动事件作用域、局部谓词、原生来源标记和已有场景重评估。
- `AutoPolicy.java`：受支持映射及允许放行的局部位。
- `AutoRequestIndex.java`：按原生请求对象身份关联旧/新请求，最多 24 次。
- `NativeBackend.java`：从原生对象读取参数并观察原生回调；较早请求在新尝试出现后仍能归档，避免混入最新请求状态。
- `check_native_auto.py`：检查没有模块造参、直接提交、原生参数/回调替换、响应改写、独立 HTTP 客户端或承载监测启停。

诊断重点字段：requestMode=SYSTEM_NATIVE_AUTO、autoEnabled、nativeAutoManager、autoGates、nativeOrigin、nativeScene、nativeCause、requestedQci、requestedMinutes、commonCloudChecks、requestAudit、httpResponse、requestHistory。

## 验证边界

本地构建、策略/索引测试、原生 DEX 接口核对和架构约束检查不等于新自动链路已经在荣耀手机实测。首轮实机应确认：1.2.0 注入、出现真实自动场景事件、原生参数实际进入发送层、原生回调和对应卡槽承载证据。

如果只看到场景事件而没有申请，查看 remainingBlockedBits、原生管理器状态和来源 Hook 命中。不要用手工构造参数来补成“自动申请成功”。

RIL 的普通默认 QoS 读取沿用 1.1.0；供应商未返回的字段仍显示未知。历史交接见 [HANDOFF.zh-CN.md](HANDOFF.zh-CN.md)，历史请求分析见 [request-audit.md](request-audit.md)。
