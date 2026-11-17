if (!XMLHttpRequest.prototype.__tsPurgePatched) {
  XMLHttpRequest.prototype.__tsPurgePatched = true;
  (function (send) {
    XMLHttpRequest.prototype.send = function () {
      // XHR 复用(open 后再次 send)时属性上可能仍是先前的包装器:
      // 解包到底层页面回调, 避免包装器级联(按旧闭包净化新响应+重复净化)
      var captured = this.onreadystatechange;
      while (captured && captured.__tsWrapper) {
        captured = captured.__tsCaptured;
      }
      var wrapper = function () {
        if (this.readyState == 4) {
          // 先无条件删掉上一轮净化建立在实例上的自有属性: 它们会遮蔽原型上的原生
          // response/responseText 访问器, XHR 复用(open 后再次 send)时原生响应写入
          // 内部槽位, 不删掉自有属性则页面永远读到旧数据——即使本次 URL 不命中
          // 规则也要恢复原生存取, 否则"命中→复用为不命中"的第二次响应仍被遮蔽
          delete this.response;
          delete this.responseText;
          let propertiesToDelete = [];
          if (
            // 吧页面更多板块
            this.responseURL.match(
              /https?:\/\/tieba\.baidu\.com\/c\/f\/frs\/frsBottom.*/g
            )
          ) {
            propertiesToDelete = [
              "frs_bottom",
              "activityhead",
              "live_fuse_forum",
              "card_activity",
              "ai_chatroom_guide",
              "friend_forum",
              "game_card_guide",
              "area_data",
            ];
          } else if (
            // 一键签到页面
            this.responseURL.match(
              /https?:\/\/tieba\.baidu\.com\/c\/f\/forum\/getforumlist.*/g
            )
          ) {
            propertiesToDelete = ["advert"];
          }
          if (propertiesToDelete.length > 0) {
            // responseType 非 text 时 response 不是字符串(JSON 已解析/二进制):
            // JSON.parse 会抛异常导致命中 URL 的净化静默失效, 改走对象分支
            // (blob/arraybuffer 主体无法净化, 仅删除自有属性遮蔽后原样放行)
            if (this.responseType && this.responseType !== "text") {
              try {
                if (this.response && typeof this.response === "object") {
                  propertiesToDelete.forEach((property) => {
                    delete this.response[property];
                  });
                }
              } catch (e) {
                // ignore
              }
            } else {
              // 解析失败（空响应/非 JSON 等）时保留原始响应，交给页面原回调处理
              try {
                let res = JSON.parse(this.response);
                propertiesToDelete.forEach((property) => {
                  delete res[property];
                });
                // 净化结果暂存为自有属性, 页面回调经此读到净化后的 JSON;
                // configurable: true 保证下一次响应时上方 delete 可重新删除
                Object.defineProperty(this, "response", {
                  writable: true,
                  configurable: true,
                });
                Object.defineProperty(this, "responseText", {
                  writable: true,
                  configurable: true,
                });
                this.response = this.responseText = JSON.stringify(res);
              } catch (e) {
                // ignore
              }
            }
          }
        }
        if (captured) {
          captured.apply(this, arguments);
        }
      };
      wrapper.__tsWrapper = true;
      wrapper.__tsCaptured = captured;
      this.onreadystatechange = wrapper;
      send.apply(this, arguments);
    };
  })(XMLHttpRequest.prototype.send);
}
