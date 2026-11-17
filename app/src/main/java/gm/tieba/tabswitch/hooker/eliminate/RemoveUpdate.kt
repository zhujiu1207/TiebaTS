package gm.tieba.tabswitch.hooker.eliminate

import gm.tieba.tabswitch.XposedContext
import gm.tieba.tabswitch.hooker.IHooker
import gm.tieba.tabswitch.logBoth
import org.json.JSONObject

class RemoveUpdate : XposedContext(), IHooker {

    override fun key(): String {
        return "remove_update"
    }

    override fun hook() {
        // 注册点隔离: 类/签名漂移时只损失本功能并留痕
        //Lcom/baidu/tbadk/coreExtra/data/VersionData;->parserJson(Lorg/json/JSONObject;)V
        runCatching {
            hookReplaceMethod(
                "com.baidu.tbadk.coreExtra.data.VersionData",
                "parserJson", JSONObject::class.java
            ) { null }
        }.onFailure {
            logBoth("RemoveUpdate: 注册失败 -- $it")
        }
    }
}
