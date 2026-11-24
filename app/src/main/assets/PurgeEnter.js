            (function(){
              // 短路范围收窄(2026-11-24 真机排查): 标记只短路 observer/心跳注册,
              // 动作扫描每轮无条件执行。此前 IIFE 整体短路时, 真机上首注入回调
              // 即返回 true(=页面已带标记), 若标记所在 window 与真正渲染进吧内容
              // 的文档存在分离(宿主重载文档/双文档路径), 后续注入永远短路,
              // 内容文档永无净化——模拟器正常而真机失效的差异正落在此处。
              // scan()/run() 自身幂等(改写覆盖式赋值+display:none), 重复执行无害
              if(!window.__tsPurgeEnter){
                window.__tsPurgeEnter=true;
                __tsPurgeRegisterObserver();
              }
              __tsPurgeRun();
              return true;
              function dtext(el){
                var cn=el.childNodes;var own='';
                for(var j=0;j<cn.length;j++){if(cn[j].nodeType===3){own+=cn[j].textContent;}}
                return own;
              }
              // 展开键存在(展开/收起)说明该区块可折叠, 必须走 unfold 门控;
              // 关注吧不足一屏时两者都不存在, forum-list 恒为高度0的收起态
              function gridReady(){
                try{
                  var fl=document.querySelectorAll('[class*="forum-list"]');
                  for(var i=0;i<fl.length;i++){
                    var cn=' '+String(fl[i].className)+' ';
                    if(cn.indexOf(' unfold ')>=0){
                      var r=fl[i].getBoundingClientRect();
                      if(r.height>500){return true;}
                    }
                  }
                }catch(e){}
                return false;
              }
              // 恢复关注吧列表显示: 不足一屏时贴吧水合后给列表打内联
              // display:none(原生布局为flex两列格子, flex-flow在作者样式里),
              // 用!important样式恢复显示——内联样式压不过它, H5反复重设也无效
              function forceExpandList(){
                try{
                  if(!document.getElementById('__tsFlStyle')){
                    var st=document.createElement('style');
                    st.id='__tsFlStyle';
                    st.textContent='.forum-list{display:flex !important;}';
                    (document.head||document.documentElement).appendChild(st);
                  }
                }catch(e){}
              }
              function hideAfterForum(fl,T){
                try{
                  if(!fl||!T)return;
                  var sec=T;
                  while(sec.parentElement&&sec.parentElement!==document.body){
                    if((sec.nextElementSibling||sec.previousElementSibling)&&sec.contains(fl)){break;}
                    sec=sec.parentElement;
                  }
                  if(sec===document.body||!sec.parentElement||sec.parentElement===document.body)return;
                  if(!sec.contains(fl))return;
                  var p=sec.parentElement;
                  var kids=p.children;
                  var started=false;
                  for(var k=0;k<kids.length;k++){
                    if(kids[k]===sec){started=true;continue;}
                    if(started){
                      var kt=(kids[k].textContent||'');
                      if(kt.indexOf('足迹')>=0||kt.indexOf('我关注的吧')>=0){continue;}
                      kids[k].style.display='none';
                    }
                  }
                }catch(e){}
              }
              // 逐候选消费: 某候选祖先尺寸不符时继续试下一个(与旧版循环内
              // continue 语义一致), 全部不符则保持原样
              function hideCreate(els){
                try{
                  for(var i=0;i<els.length;i++){
                    var cell=els[i];
                    while(cell&&cell!==document.body){
                      var r=cell.getBoundingClientRect();
                      if(r.height>=40){break;}
                      cell=cell.parentElement;
                    }
                    if(cell&&cell!==document.body){
                      var rr=cell.getBoundingClientRect();
                      if(rr.height>=40&&rr.height<=400){
                        cell.style.display='none';
                        return;
                      }
                    }
                  }
                }catch(e){}
              }
              // 单轮全树扫描取代旧版 walk/expandBtnExists/hideAfterForum/hideCreate
              // 各自 querySelectorAll('*') 的 4 轮遍历: 进吧页节点数上千,
              // observer 高频触发下 4 轮是最大的 JS 开销来源。
              // 逐元素判定与旧版逐一对应: 含'一键签到'的元素改写(与旧版 walk 的
              // if/else 互斥一致), 其余元素做展开点击 + 展开/收起/锚点/创建吧收集。
              // 旧版的 expandBtnExists/hideAfterForum/hideCreate 在 walk 改写之后
              // 执行, 看到的是"已改成'签'"的文本——单轮化后这些节点走改写分支
              // 不再进入收集, 结果一致
              function scan(){
                // fl 取首个 forum-list 匹配(与旧版 hideAfterForum 的
                // querySelector 行为一致, 不加 unfold 过滤——unfold 只用于
                // gridReady 门控判定, 两者在旧版就是不同的查询)
                var fls=document.querySelectorAll('[class*="forum-list"]');
                var fl=fls.length?fls[0]:null;
                var T=null,creates=[],expandBtn=false;
                var all=document.querySelectorAll('*');
                for(var i=0;i<all.length;i++){
                  var el=all[i];
                  if(el.shadowRoot){scanShadow(el.shadowRoot);}
                  var own=dtext(el);
                  if(own.indexOf('一键签到')>=0){
                    el.textContent='签';
                    el.style.width='34px';el.style.height='34px';el.style.minWidth='34px';
                    el.style.borderRadius='17px';el.style.padding='0';el.style.overflow='hidden';
                    el.style.lineHeight='31px';el.style.fontSize='14px';el.style.flex='0 0 34px';
                    el.style.boxSizing='border-box';el.style.textAlign='center';
                    el.style.background='#ffffff';
                    el.style.border='1px solid #b8b8b8';
                    el.style.color='#333333';
                    el.style.fontWeight='normal';
                  }else{
                    var t=own.replace(/\s+/g,'');
                    if(t==='展开'){
                      var vr=el.getBoundingClientRect();
                      if(vr.height>0&&window.__tsLastExpand!==el){
                        window.__tsLastExpand=el;
                        try{el.click();}catch(e){}
                      }
                    }
                    // 展开键存在(展开/收起)说明该区块可折叠, 必须走 unfold 门控;
                    // 关注吧不足一屏时两者都不存在, forum-list 恒为高度0的收起态
                    if(!expandBtn&&(t==='展开'||t==='收起')){expandBtn=true;}
                    if(!T&&own.length<12&&own.indexOf('我关注的吧')>=0){T=el;}
                    // 候选收全: 旧版 hideCreate 首个候选尺寸不符时继续试后续,
                    // 单轮化后由 hideCreate 逐个消费, 语义不变
                    if(own.length<10&&own.indexOf('创建吧')>=0){creates.push(el);}
                  }
                }
                return {fl:fl,T:T,creates:creates,expandBtn:expandBtn};
              }
              // shadowRoot 子树处理旧版 walk 递归的等价动作: 签到改写 + 展开点击
              // (门控判定类收集只在 document 层——旧版 expandBtnExists/
              // hideAfterForum/hideCreate 的 querySelectorAll 不穿透 shadow)
              function scanShadow(root){
                try{
                  var all=root.querySelectorAll('*');
                  for(var i=0;i<all.length;i++){
                    var el=all[i];
                    if(el.shadowRoot){scanShadow(el.shadowRoot);}
                    var own=dtext(el);
                    if(own.indexOf('一键签到')>=0){
                      el.textContent='签';
                      el.style.width='34px';el.style.height='34px';el.style.minWidth='34px';
                      el.style.borderRadius='17px';el.style.padding='0';el.style.overflow='hidden';
                      el.style.lineHeight='31px';el.style.fontSize='14px';el.style.flex='0 0 34px';
                      el.style.boxSizing='border-box';el.style.textAlign='center';
                      el.style.background='#ffffff';
                      el.style.border='1px solid #b8b8b8';
                      el.style.color='#333333';
                      el.style.fontWeight='normal';
                    }else if(own.replace(/\s+/g,'')==='展开'){
                      var vr=el.getBoundingClientRect();
                      if(vr.height>0&&window.__tsLastExpand!==el){
                        window.__tsLastExpand=el;
                        try{el.click();}catch(e){}
                      }
                    }
                  }
                }catch(e){}
              }
              function run(){
                // 单轮扫描无条件先执行: 与旧版 walk() 每轮先跑一致, 签到改写与
                // 展开点击不受门控约束; 门控只约束 hideAfterForum/hideCreate
                var r=scan();
                // 仅当列表展开完成(unfold+高度正常)后才屏蔽, 展开判定出现200ms后
                // 执行以避开高度计算; 收起态屏蔽会导致整页塌陷白屏。
                // 旁路: 页面无展开/收起键 = 关注吧不足一屏, 列表高度恒为0且
                // 不存在展开交互, 稳定200ms后直接屏蔽
                if(gridReady()){
                  if(!window.__tsG0){window.__tsG0=Date.now();}
                  if(Date.now()-window.__tsG0>=200){
                    hideAfterForum(r.fl,r.T);
                    hideCreate(r.creates);
                  }
                }else if(!r.expandBtn){
                  if(!window.__tsN0){window.__tsN0=Date.now();}
                  if(Date.now()-window.__tsN0>=200){
                    forceExpandList();
                    hideAfterForum(r.fl,r.T);
                    hideCreate(r.creates);
                  }
                }else{
                  window.__tsG0=null;
                  window.__tsN0=null;
                }
              }
              // 挂到 window 上供后续每次注入直接驱动一轮扫描(见文件头短路收窄注释)
              window.__tsPurgeRun=run;
              function __tsPurgeRun(){
                try{run();}catch(e){}
              }
              // 防抖: observer 回调只记时间戳, 150ms 尾沿后统一跑一轮 run。
              // 此前每次 DOM 变动立即 run()(最多4轮全树扫描), 而本脚本自己的
              // 改写(签名按钮 textContent)又会触发 childList observer 回环,
              // 变动风暴下全树扫描被放大数倍。尾部防抖收敛为每 150ms 至多一轮;
              // 心跳与防抖走同一调度入口, 不再并行两套触发
              function __tsPurgeRegisterObserver(){
                var pending=false;
                function scheduleRun(){
                  if(pending)return;
                  pending=true;
                  setTimeout(function(){
                    pending=false;
                    run();
                  },150);
                }
                scheduleRun();
                var lastMutation=Date.now();
                var mo=new MutationObserver(function(){lastMutation=Date.now();scheduleRun();});
                mo.observe(document.documentElement,{childList:true,subtree:true});
                // 心跳兜底: 页面稳定后无DOM变动, 靠定时器驱动屏蔽判定。
                // 仅在近期无DOM变动(变动已由observer即时处理)时以1s节奏兜底,
                // 避免固定200ms心跳对稳定页面做每秒5轮的全树扫描
                setInterval(function(){
                  if(Date.now()-lastMutation>=1500){scheduleRun();}
                },1000);
              }
            })();
