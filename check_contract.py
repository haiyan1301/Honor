#!/usr/bin/env python3
"""Validate the module's native reflection/Hook signatures against the provided original APKs.

Requires androguard (analysis only). Does not execute APK code.
"""
import argparse
import json
import zipfile
from pathlib import Path
from loguru import logger
logger.remove()
from androguard.core.dex import DEX

p = argparse.ArgumentParser()
p.add_argument('--booster', type=Path, required=True)
p.add_argument('--system-base', type=Path, required=True)
p.add_argument('--settings', type=Path, required=True)
p.add_argument('--out', type=Path, required=True)
a = p.parse_args()
root = 'Lcom/hihonor/systemserver/booster/'
req = root+'framework/intf/intelligent/DBRequestInfo;'
scene = root+'framework/intf/intelligent/QosConst$Scene;'
cause = root+'framework/intf/intelligent/QosConst$Cause;'
qos = root+'ollie/intelligent/qos/QosManager;'
timer = root+'ollie/intelligent/qos/QosTimer;'
state = root+'framework/intf/OllieStateManager;'
utils = root+'framework/utils/BoosterUtils;'
link = root+'ollie/transportlayer/framework/intf/link/LinkLayerProxy;'
auto = root+'ollie/transportlayer/feature/transportlayerselfcure/dataacceleration/'
trans = root+'ollie/transportlayer/framework/intf/CommsCloud/DBRequestInfoForTrans;'
checks = {
 'booster': {
  qos: ['getInstance()'+qos, 'requestDedicatedBearer('+req+'Landroid/os/Message;)V',
        'checkIfQosReqLegal('+req+')I', 'doWhenQosSuccess('+root+'framework/cloudinter/common/CloudTaskResult;)V',
        'handleTelecomReturn(Landroid/os/AsyncResult;)V', 'isDBWorking(I)Z', 'isQosEnabled()Z'],
  req: ['<init>(Ljava/lang/String;II'+scene+cause+'Ljava/lang/String;Ljava/util/List;I)V',
        'setIpv6(Ljava/lang/String;)'+req, 'getApplyId()Ljava/lang/String;', 'checkParamIllegal()Z',
        'getScene()Ljava/lang/String;', 'getQosCause()Ljava/lang/String;', 'getPlmn()Ljava/lang/String;', 'getRat()I', 'getQciType()I', 'getDuration()I'],
  auto+'DataAccelerateManager;': ['getInstance()'+auto+'DataAccelerateManager;', 'isQosRequestNeeded()Z', 'isQosFeatureEnabled()Z',
      'isSceneEnabled(I)Z', 'triggerDataAccelerateSceneInstance(III)V', 'handleUserSceneEnter(II'+root+'ollie/transportlayer/framework/intf/featureproxy/userscene/NetInfo;)V',
      'handleBearerSceneChange(III)V','triggerQosSceneInstance(ZI)V', 'onOldProtocolHighSpeedRailChanged(Z)V',
      'onOldProtocolMetroChanged(Z)V', 'onOldProtocolLiveChanged(I)V', 'onOldProtocolCommuteChanged(Z)V'],
  auto+'DataAccelerateScene;': ['getQosReqScene()'+root+'ollie/transportlayer/framework/utils/TransportLayerConsts$Scene;',
      'startQosReq()V','connectQosCloudService()V','constructDBRequestInfo()'+trans, 'isSatisfyEnterCondition()Z',
      'isNonLagPenaltyDuration()Z','handleDbCloudDone(Landroid/os/Message;)V'],
  auto+'DataAccelerateScene$PrepareAccState;': ['processMessage(Landroid/os/Message;)Z'],
  auto+'DataAccelerateScene$AsyncExecState;': ['enter()V','processMessage(Landroid/os/Message;)Z'],
  trans: ['toDBRequestInfo()'+req],
  timer: ['getInstance()'+timer, 'hasExistIp('+req+')Z'],
  root+'ollie/common/OllieFeature;': ['isStart()Z'],
  root+'ollie/intelligent/qos/QosConfig;': ['getTokenAppid()Ljava/util/Map;'],
  root+'ollie/intelligent/qos/QosRequest;': ['getDBRequestInfo()'+req, 'generateHeaders()Ljava/util/HashMap;'],
  root+'framework/intf/CloudReqTaskExecutor;': ['getInstance()'+root+'framework/intf/CloudReqTaskExecutor;',
      'executeTask('+root+'framework/cloudinter/common/RequestBody;Landroid/os/Message;'+root+'framework/intf/ISaveCloudData;)V',
      'isExceedMaxInterTimes(I)Z'],
  root+'framework/cloudinter/common/config/CloudInterConfigManager;': ['getInstance()'+root+'framework/cloudinter/common/config/CloudInterConfigManager;',
      'getConfigInfo(I)'+root+'framework/cloudinter/common/config/CloudInterConfigInfo;'],
  root+'framework/cloudinter/common/config/CloudInterConfigInfo;': ['getDailyMaxInteractTimes()I'],
  root+'framework/utils/DateUtils;': ['getCurrentDay()Ljava/lang/String;'],
  root+'framework/cloudinter/common/HttpRequest;': ['request('+root+'framework/cloudinter/common/HttpResponse;)V',
      'handleResponse(Ljavax/net/ssl/HttpsURLConnection;'+root+'framework/cloudinter/common/HttpResponse;)V'],
  root+'framework/cloudinter/CloudReqTask;': ['@signature(ILjava/lang/String;[B)V'],
  root+'ollie/transportlayer/feature/transportlayerselfcure/dataacceleration/QosAppInfo;': ['getSwitchState()I'],
  state: ['getDefaultDataSlotId()I','getQosPeriodStatus()I','isDataOn()Z','isWifiConnected()Z','isVpnModeSwitchOn()Z',
          'isDataRoaming(I)Z','getCellularIpv4Addr(I)Ljava/net/Inet4Address;',
          'getCellularIpv6Addr(I)Ljava/net/Inet6Address;','isNetworkType4G(I)Z','isNetworkTypeSaNsaEnDcActive(I)Z'],
  utils: ['convertSlotIdToSubId(I)I','getTelephonyManager()Landroid/telephony/TelephonyManager;','isDomesticVersion()Z'],
  link: ['reportDedicatedBearRequest(II)V','getDedicatedBearInfoList(I)Ljava/util/List;'],
  root+'ollie/transportlayer/framework/channelmanage/communication/client/BoosterClient;': ['handleDedicatedBearerInfoInd(Landroid/os/Bundle;)V'],
  'Lcom/hihonor/booster/protobuf/DedicatedBearerInfoIndOuterClass$DedicatedBearerInfoInd;':
      ['parseFrom([B)Lcom/hihonor/booster/protobuf/DedicatedBearerInfoIndOuterClass$DedicatedBearerInfoInd;',
       'getDedicatedBearerInfoList()Ljava/util/List;'],
  'Lcom/hihonor/booster/protobuf/DedicatedBearerInfoIndOuterClass$BearerInfo;': ['getQci()I','getBearerType()I','getBearerState()I'],
 },
 'system_base': {'Lcom/hihonor/systemserver/HwServerApplication;':['onCreate()V']},
 'settings': {
  'Lcom/android/settings/WirelessSettings;':['onCreate(Landroid/os/Bundle;)V'],
  'Lcom/hihonor/settingslib/preference/ArrowPreference;':['<init>(Landroid/content/Context;)V'],
  'Landroidx/preference/Preference;':['setKey(Ljava/lang/String;)V','setTitle(Ljava/lang/CharSequence;)V',
      'setSummary(Ljava/lang/CharSequence;)V','setOrder(I)V','setIntent(Landroid/content/Intent;)V'],
  'Landroidx/preference/PreferenceGroup;':['addPreference(Landroidx/preference/Preference;)Z'],
 }
}
fields = {
 qos: ['dbRequestInfo','isTaskExecuting','qosMessage'],
 auto+'DataAccelerateManager;':['dataAccSceneConfigHashMap','dataAccSceneMap','handler'],
 auto+'DataAccelerateScene;':['dataAccSceneConfig','uid','userSceneId','enterCondition','ollieStateManager'],
 auto+'DataAccSceneConfig;':['sceneId','accAction','qciType','isSupportAsyncExec','dedicatedBearerReqDuration','sceneEnable','accEnter'],
 timer:['punishTimerCache','sceneTime','isPunishDuration'],
 root+'ollie/intelligent/qos/QosTimer$TimerCache;':['error','startTime','duration'],
 root+'ollie/intelligent/qos/QosResponse;':['code','duration','msg','remoteMessage'],
 root+'framework/intf/CloudReqTaskExecutor;':['INTER_TIMES_CACHE_MAP'],
 root+'framework/cloudinter/CloudReqTask;':['requestBody'],
 root+'framework/cloudinter/common/HttpRequest;':['url','body','heads','auth']
}
report=[]
for group, path in [('booster',a.booster),('system_base',a.system_base),('settings',a.settings)]:
 found={}; fs={}
 wanted=set(checks[group]) | (set(fields) if group=='booster' else set())
 with zipfile.ZipFile(path) as z:
  for name in z.namelist():
   if not name.endswith('.dex'):continue
   dex=DEX(z.read(name))
   for c in dex.get_classes():
    if c.get_name() not in wanted:continue
    found[c.get_name()]={m.get_name()+m.get_descriptor().replace(' ','') for m in c.get_methods()}
    fs[c.get_name()]={f.get_name() for f in c.get_fields()}
   del dex
 for cls,methods in checks[group].items():
  for method in methods:
   matches = any(m.endswith(method[len('@signature'):]) for m in found.get(cls,set())) if method.startswith('@signature') else method in found.get(cls,set())
   report.append({'class':cls,'method':method,'ok':matches})
 if group=='booster':
  for cls,names in fields.items():
   for field in names:report.append({'class':cls,'field':field,'ok':field in fs.get(cls,set())})
 print(group, 'checked', flush=True)
a.out.parent.mkdir(parents=True,exist_ok=True)
a.out.write_text(json.dumps(report,indent=2),encoding='utf-8')
failed=[r for r in report if not r['ok']]
if failed:
 print(json.dumps(failed,indent=2));raise SystemExit(1)
print('PASS:',len(report),'native method/field contract checks')
