# 请求参数、计数与 QCI 的反编译核对

> 本文记录 1.1.0 的手动直调审计基线。1.2.0 已改为真实系统自动场景、原生构造参数与局部条件 Hook，见 [native-auto.md](native-auto.md)。下文中的“模块使用 Manual/30 分钟”和“保留公共次数”均指历史版本；当前值及放行范围以 1.2.0 文档和实机诊断为准。

针对用户提供的系统服务 70.1.0.1 / Booster split DEX 核对。文中类名省略共同前缀 `com.hihonor.systemserver.booster`。这是客户端源码可证明的行为；未取得荣耀或运营商服务端实现。

## 申请链路

`QosManager.requestDedicatedBearer` → `checkIfQosReqLegal` → 填入 applyId、记录本地 IP → 电信先取得 token（移动走原生另一分支）→ `doCloudTask` → `CloudReqTaskExecutor.executeTask`（业务号 1004）→ `CloudReqTask.doInBackground` → `RequestBody.toJson` → `SignatureHelper.getSignature` → `HttpRequest.request` → HTTPS POST `/dedicatedbearer/v1/apply`。

模块的直接调用跳过了前面的场景触发器，但仍走原生参数、令牌、公共执行器、签名和发送层。不能把“进入 QosManager”当作“已经发包”。

## 请求体

`QosRequest` 的实例字段是 `requestInfo`。`RequestBody.toJson()` 通过 `JsonUtil.toJson(this)` 调用普通 Gson；静态字段不参与。`DBRequestInfo` 的实例字段如下：

| 字段 | 来源/意义 |
| --- | --- |
| plmn | 申请时上网卡的运营商 PLMN |
| rat | 原生制式编码，模块按原生 4G/5G 条件填写 |
| qciType | 请求目标 QCI；优速通手动申请为 6，不代表实际已经分配 |
| scene | 模块调用使用 Manual，云端能够看到该场景 |
| qosCause | 模块调用使用 Scene |
| ip、ipv6 | 本机蜂窝地址；在诊断中脱敏 |
| destinationIpList | 目的地址串；QCI 6 允许为空 |
| duration | 建议时长；模块使用 30 分钟，云端可以拒绝或返回不同值 |
| token | 电信原生获取并填入；移动按原生另一分支处理 |
| applyId | 原生生成的本次申请 UUID |

结构是 `{"requestInfo":{...}}`。这个对象没有 Base、Manual 剩余次数、Fail 剩余次数、通用接口调用计数、punish_error 或冷却起止字段。Manual 作为 scene 的字符串值，与本地同名次数不是一回事。

## 请求头与签名

`QosRequest.generateHeaders()` 提供 Content-Type、Charset、Accept-Encoding、X-Req-Src、X-App-Version、X-CP-AppId、X-Request-ID、Batch-ID、Product-Name、VendorCountry、Is-Beta、OsVersion、Is-Test、Device-Id。

- Device-Id 是 `CloudUtils.getDeviceId()` 从本地配置读取/首次生成并持久化的 UUID。它能让云端关联设备。模块沿用原值。
- Batch-ID 在 `CloudUtils.getBatchId()` 中首次随机取 0–99 并持久化，不是“已申请次数”。
- `SignatureHelper` 使用 HTTP 方法、URL 路径、规范化 query、完整 JSON、cp appId、当前时间戳生成 HMAC 认证头；没有读取 QosTimer 或通用计数缓存。模块沿用原生签名，不修改待签名内容。
- `HttpRequest` 把上述 headers 加到连接，将签名放入 authorization，并写入同一个 JSON 字节串；未发现追加本地次数的操作。

因此，可确认这些本地次数没有作为该 `/apply` 的直接业务字段上传。设备身份、IP、场景、令牌等仍会参与云端判断，服务端也可自行计数。客户端不能由“没带次数”推导出“云端不限制次数”。

## 两层本地计数与单独的统计

| 层 | 核对的方法 | 行为 |
| --- | --- | --- |
| QoS 场景与冷却 | `QosManager.checkIfQosReqLegal`、`QosTimer.getQosReqSceneTime` | 冷却时返回保存的错误码；Base 或相应场景剩余大于 0 才继续；模块只对自己的这次调用绕过该层 |
| 通用每日接口次数 | `CloudReqTaskExecutor.isExceedMaxInterTimes` | 从 cloud_interaction_config.xml 读取业务 1004 的 dailyMaxInteractTimes，读取 INTER_TIMES_CACHE_MAP 中的“业务号_日期”计数；通过时先加一，超限时仅返回本地错误，不发 HTTP；本版保留并记录该层 |
| 原生完成记账 | `QosTimer.addQosReqSceneTime` | 失败且 Fail>0 时先减 Fail；否则可能减 Base，再减场景次数。绕过入口不阻止完成记账和新的冷却记录 |
| 通用调用统计 | `CloudReqTaskExecutor.processExpiredData/reportProtocolFlow` | 清理旧日期计数时，把业务号、日期、次数交给 CHR 流水记录 |
| QoS 流水 | `QosChr.QosProtocolFlow.reportToDb` | 记录请求、结果、部分本地拦截累计等，交给 CHR；与申请 JSON 是不同路径 |

统计经 `OllieChrProxy` → `OllieChrManager` → `HnChrReportConnImpl` 处理。本次核对确认其独立落库/统计路径；没有服务端证据说明这些统计是否参与后续权益或频控判定。因此不能笼统承诺“本地计数绝不影响云端状态”，也不能把当前 202 直接归因于本地次数。

## 202 的证据边界

`QosConst.QOS_OUT_OF_EXPIRY_DATE = 202`，本地次数不足是 105；公共接口次数不足则由 CloudReqTaskExecutor 改为本地执行器错误，产生 CloudErrorCode，进入不了正常的 HTTP 业务响应解析。

用户提供的 1.0.0 诊断中，三次独立申请都满足本地校验 -1、电信 HTTP 200 并返回令牌、CloudTaskResult 为 NO_ERROR、QosResponse.code 为 202。这证明本次到了实际云端业务响应阶段；原生常量属于服务有效期类拒绝。msg/remoteMessage 为空，无法确定是设备、号码、产品有效期还是其它服务端策略。删除本地冷却或改成功显示不能改变它。

## QCI 与手机本地读取

荣耀专用链路：电话框架注册 unsolicited 105 → RadioProxy → Booster 回调 2001 → 专用承载 protobuf，真实字段是 bearerType、qci、bearerState。它不是申请参数回显，也不保证提供所有普通上网默认承载。

1.1.0 增加独立电话进程观察器，读取 RIL 数据连接列表的默认 QoS 及 QoS bearer sessions。Android 16 AOSP 接口来源：

- [CommandsInterface.getDataCallList / registerForDataCallListChanged](https://android.googlesource.com/platform/frameworks/opt/telephony/+/refs/heads/android16-release/src/java/com/android/internal/telephony/CommandsInterface.java)
- [DataCallResponse.getDefaultQos / getQosBearerSessions](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/telephony/java/android/telephony/data/DataCallResponse.java)
- [EpsQos.getQci](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/telephony/java/android/telephony/data/EpsQos.java)
- [NrQos.get5Qi](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/telephony/java/android/telephony/data/NrQos.java)

QCI/5QI 由网络配置，手机本地可以读取供应商上报的值；SIM 不是只有一个固定 QCI。供应商若没有通过这些接口提供 QoS，模块显示未知。新电话路径尚未在这台荣耀手机验证。

## 新诊断的证据范围

记录实际 HTTP 对象，不用预先构造参数冒充已发送参数；用 applyId 绑定当前请求，按请求分别归档前后计数、公共本地检查、默认网络/卡槽、签名时间、HTTP 状态和原生反序列化前的返回 JSON。对令牌、签名、设备标识、地址和未知字符串脱敏。

进入 HTTP 对象只证明到达发送层；收到响应状态/业务 JSON 才是进一步证据。HTTP 200 与业务码 200 分别记录。服务端返回 duration=0 如实保留。最多保留最近 6 次请求及有限事件；未获取的字段、未观察到的 Hook 和供应商未提供的 QoS 都不伪造。
