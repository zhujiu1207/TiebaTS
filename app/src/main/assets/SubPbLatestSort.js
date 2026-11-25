(function () {
    if (window.__TS_LATEST_SORT__) { return; }
    window.__TS_LATEST_SORT__ = true;

    // ===== 22.14+ Vue3 桥接模式 =====
    // 22.14 起楼中楼 H5 升级 Vue3: 生产构建不往 DOM 挂 __vue__(Vue2 时代的
    // 组件实例探测全部失效), 且实测服务端排序接口不支持降序(sort=1/3/4/6
    // 均正序、2/8=热门, 原生楼层的"倒序"走另一条原生协议管道不可复用)。
    // 桥接模式改在数据源头工作, 不再触碰组件实例:
    // 1. 经 webpack 假 chunk 拿 __webpack_require__, 从模块表按形状定位页面
    //    事件总线 TbInfra(含纯转发 shim 工厂的安全捕获, 见 findTbInfra);
    // 2. 卸载应用的 "notify" 监听器并包一层重注册: floor.getFloorData 响应
    //    的 subpost_list 在应用消费之前按 selectedTab 语义整体重排
    //    (最早=升序/最晚=降序), 应用照常克隆渲染;
    // 3. 两个 tab 全部放行原生点击, 桥接模式自身不发任何请求——自派
    //    floor.naRequest 的响应条目缺回复上下文字段, 宿主模板会把根回复
    //    渲染成"空名+冒号"(同帖 A/B 实证, 见 bridgeSupplementalRefresh
    //    上方注释), 应用自构请求的响应始终干净; 升/降序全靠包装层重排
    //    (服务端 sort 值不参与语义)。首屏补发=程序化点击一次原生"最早"
    //    按钮。限制与 22.13 一致: 点过 tab 后分页追加的页只保证页内有序,
    //    整体顺序不再保证。
    // Vue2 宿主(12.x~22.13)上 __vue__ 探测可用, 走原有本地排序路径,
    // 桥接不会初始化。
    var bridgeMode = false;
    var bridgeStopped = false;
    // 两处重试共用一个计数会让配额互相侵占: 共享 chunk 晚加载(上限 8)耗掉几轮后,
    // "应用未挂载完"(上限 5)的余量变少, 大图 H5 首帧慢时桥接提前永久放弃
    var bridgeChunkAttempts = 0;   // findTbInfra 失败(共享 chunk 含 TbInfra 模块, 晚于注入加载)
    var bridgeListenerAttempts = 0; // 应用尚未注册 notify 监听器(挂载晚于注入)
    // 桥接包装层引用与世代漂移检测(见 bridgeListenerDrift)
    var bridgeWrapRef = null;
    var bridgeTbi = null;
    // 首屏补发只发一次: 拆桥重建不重发——重建循环里每次补发=一次程序化点击=
    // 一次 reload(既覆盖用户刚选的 tab 语义, 又令 H5 短暂无响应)。
    // 2026-11-25 真机回归(字体变大+点不动几秒后恢复)的机制之一
    var bridgeRefreshDone = false;
    // 漂移连续计数: 应用挂载期会瞬时增删 notify 监听(组件逐个注册), 单次漂移
    // 即拆桥会进入"拆桥(class 写入解禁)→重建(补发点击)→应用再重挂"的 2s 循环,
    // 连续两轮漂移才认定世代真的更替了
    var driftStrikes = 0;

    var userInteracted = false;
    // 当前应高亮的 tab: 'early' | 'latest'(默认"最早", 与原生默认激活态一致)
    var selectedTab = 'early';
    // 上轮见过的 lzlList 引用: H5 重载(下拉刷新/失败重试)会整体重建数组,
    // 引用变化即复位 userInteracted, 恢复自动排序(否则高亮与内容长期不符)
    var lastListRef = null;
    // 原生 tab 激活/未激活时的完整 class 快照, 首次重命名时记录
    var classSnap = null;
    var healScheduled = false;
    // 缓存的 tab 按钮: 每 2s 轮询对全文档 querySelectorAll 是本脚本的主要
    // 开销(楼中楼长帖数百节点 × 每轮 2~4 次), 命中一次后仅在"缓存节点脱离
    // 文档(H5 重建了 tab 行)或文本被还原"时才全量重扫
    var cachedTabs = null;
    // resolveTabs 连续未命中次数: 多数注入页不渲染排序 tab, 全量文本扫描在
    // 那些页面上是纯浪费, 用于轮询退避(命中即清零)
    var scanMisses = 0;

    // 廉价哨兵: tab 行/楼中楼容器存在性探测（纯 class 选择器, 无逐元素
    // textContent 比较）。为假时跳过 findBtn 的全文档扫描, 交由退避节奏兜底
    function lzlUiExists() {
        return document.querySelector('.lzl-sort-tab, .sort-tab, .tabs, .lzl-comment-item, .lzl-float-item') != null;
    }

    function resolveTabs() {
        if (cachedTabs
            && document.contains(cachedTabs.early) && document.contains(cachedTabs.latest)
            && (cachedTabs.early.textContent || '').trim() === '最早'
            && (cachedTabs.latest.textContent || '').trim() === '最晚') {
            scanMisses = 0;
            return cachedTabs;
        }
        var early = null;
        var latest = null;
        if (lzlUiExists()) {
            early = findBtn('最早');
            latest = findBtn('最晚');
        }
        if (early && latest) {
            cachedTabs = { early: early, latest: latest };
            scanMisses = 0;
            return cachedTabs;
        }
        scanMisses++;
        return null;
    }

    // 找持有 lzlList 的 PbLzl 组件实例: 从排序 tab 容器沿 DOM 祖先链向上找。
    // 注意 tab 容器自身不一定挂 __vue__(实测有的弹窗挂在 TbmStatus/PbLzl 上)。
    // 命中结果同样缓存, 仅在锚点元素脱离文档或 vm 失效时重新查找
    var cachedLzl = null;
    function findLzlVm() {
        // 锚点元素仍在文档且其 __vue__ 仍是缓存实例才算命中:
        // 元素被宿主复用重挂时 __vue__ 可能换新, 不能返回过期 vm
        if (cachedLzl && cachedLzl.vm.lzlList
            && cachedLzl.el.__vue__ === cachedLzl.vm
            && document.contains(cachedLzl.el)) {
            return cachedLzl.vm;
        }
        try {
            var cands = document.querySelectorAll('.lzl-sort-tab, .sort-tab, .tabs');
            for (var i = 0; i < cands.length; i++) {
                var p = cands[i];
                while (p && p !== document.body) {
                    if (p.__vue__ && p.__vue__.lzlList) {
                        cachedLzl = { el: p, vm: p.__vue__ };
                        return p.__vue__;
                    }
                    p = p.parentElement;
                }
            }
            // 兜底: 从楼中楼列表项向上找
            var items = document.querySelectorAll('.lzl-comment-item, .lzl-float-item');
            for (var j = 0; j < items.length; j++) {
                var q = items[j];
                while (q && q !== document.body) {
                    if (q.__vue__ && q.__vue__.lzlList) {
                        cachedLzl = { el: q, vm: q.__vue__ };
                        return q.__vue__;
                    }
                    q = q.parentElement;
                }
            }
        } catch (e) { }
        return null;
    }

    // 应用时间序: 保留 slot0(最早根回复, H5 渲染在 tabs 上方的 lzl-first-item
    // 特殊槽位, 不参与排序), 其余按 time 升序(desc=false)或降序(desc=true)。
    // 两个 tab 均为纯本地排序, 不触发 H5 网络重载, 从任意当前顺序一步到位;
    // 用 splice/push/sort 变异方法保证 Vue2 响应式生效。
    // 变异必然触发 Vue 全列表重渲染 diff, 排序前先做 O(n) 有序性检查——
    // 轮询兜底路径(未点 tab 时每 2s 一轮)对早已有序的列表不再白付 diff;
    // 返回值只表达"已确保有序", 供点击路径置 userInteracted 用
    function sortByTime(vm, desc) {
        try {
            var list = vm.lzlList;
            if (!list) { return false; }
            // 空表/单条天然有序: 返回 true 让点击路径拦截原生行为
            // (改名后的按钮原生语义与标签相反, 放行会"点最晚得到升序")
            if (list.length < 2) { return true; }
            var sorted = true;
            var lastValid = NaN;
            for (var i = 1; i < list.length; i++) {
                var v = Number(list[i].time);
                // NaN(尾部占位/折叠项缺 time)不参与判序: 有序性只由有效项子序列
                // 判定, NaN 项的位置不作为判据。若把 NaN 对判为无序, 而排序对
                // "NaN 与谁都相等"的不可传递关系可能修不动乱序, 轮询路径会每 2s
                // 一次永不收敛的全表重排。
                // 比较跨 NaN 占位的相邻有效项而非物理相邻对: 占位夹在中间时乱序
                // 只在对侧有效项之间可见, 仅查物理相邻对会漏判导致"点了没反应"
                if (isNaN(v)) { continue; }
                if (!isNaN(lastValid)) {
                    var d = lastValid - v;
                    if (desc ? d < 0 : d > 0) { sorted = false; break; }
                }
                lastValid = v;
            }
            if (!sorted) {
                var tail = list.splice(1, list.length - 1);
                tail.sort(function (a, b) {
                    // 比较器必须是一致全序, 否则排序可能修不动乱序(如 [4,NaN,2]:
                    // "4~NaN~2 但 4>2"的不可传递关系让 V8 可能原样返回), 轮询路径
                    // 每轮检出乱序却修不好, 同样不收敛。缺 time 项恒排最后(占位/
                    // 折叠项本就在尾部, 不参与时间序), 有效项之间按 time 全序
                    var ta = Number(a.time), tb = Number(b.time);
                    var na = isNaN(ta), nb = isNaN(tb);
                    if (na && nb) { return 0; }
                    if (na) { return 1; }
                    if (nb) { return -1; }
                    return desc ? tb - ta : ta - tb;
                });
                // 分批 push: apply 一次性展开数千条会触发 RangeError(参数个数上限)
                for (var k = 0; k < tail.length; k += 2000) {
                    list.push.apply(list, tail.slice(k, k + 2000));
                }
            }
            return true;
        } catch (e) {
            return false;
        }
    }

    function findBtnIn(root, txt) {
        var els = root.querySelectorAll('span,div,a,button,li,p,em,i,label,b,strong,td');
        for (var i = 0; i < els.length; i++) {
            var e = els[i];
            if (e.childElementCount === 0 && (e.textContent || '').trim() === txt) { return e; }
        }
        return null;
    }

    // 只在排序 tab 容器/lzl 组件子树内查找, 不做全文档文本匹配:
    // 无锚定匹配会把无关的"热门"文本叶子(推荐角标/其他 tab)误认成排序 tab,
    // 级联污染 resolveTabs 缓存/classSnap/点击拦截
    function findBtn(txt) {
        var roots = document.querySelectorAll('.lzl-sort-tab, .sort-tab, .tabs');
        for (var i = 0; i < roots.length; i++) {
            var hit = findBtnIn(roots[i], txt);
            if (hit) { return hit; }
        }
        // 兜底: 已定位 lzl 组件锚点(经 __vue__.lzlList 验证)时在其子树内查找
        try {
            if (cachedLzl && cachedLzl.el && document.contains(cachedLzl.el)) {
                return findBtnIn(cachedLzl.el, txt);
            }
        } catch (e) { }
        return null;
    }

    function setBtnText(node, from, to) {
        for (var i = 0; i < node.childNodes.length; i++) {
            var c = node.childNodes[i];
            if (c.nodeType === 3 && c.textContent.trim() === from) {
                c.textContent = c.textContent.replace(from, to);
            } else if (c.nodeType === 1) {
                setBtnText(c, from, to);
            }
        }
    }

    // 判断节点是否位于指定祖先元素内
    function nodeIn(node, ancestor) {
        while (node) {
            if (node === ancestor) { return true; }
            node = node.parentElement;
        }
        return false;
    }

    function getTabs() {
        return resolveTabs();
    }

    // 重命名两个原生 tab: "热门"→"最早", "最早"→"最晚"。
    // 只在页面上存在原生"热门"文本时执行(说明 H5 刚渲染/还原了 tab 组合),
    // 必须先把"最早"改成"最晚"、再把"热门"改成"最早", 否则文本查找会串。
    // 改名前快照两个按钮的完整 class: 原生默认"热门"是激活态、"最早"是
    // 未激活态, 以此作为后续恢复高亮的样式来源(不猜测具体激活类名)。
    // 该快照仅服务 Vue2 本地排序路径的 applyTabStyle; 桥接模式(22.14+)
    // 高亮完全由原生管理, 不消费快照、不写任何 class
    function renameTabs() {
        try {
            var hot = findBtn('热门');
            if (!hot) { return; }
            var early = findBtn('最早');
            if (!early) { return; }
            if (!classSnap && /\bactive\b/.test(hot.className)
                && !/\binactive\b/.test(hot.className)
                && hot.className !== early.className) {
                classSnap = { active: hot.className, inactive: early.className };
            }
            setBtnText(early, '最早', '最晚');
            setBtnText(hot, '热门', '最早');
        } catch (e) { }
    }

    // ===== 桥接模式实现 =====

    // 自派请求响应形状漂移的实证记录(修复冒号问题的依据, 勿再走 naRequest):
    // - 脚本自派 floor.naRequest(无论参数带不带 load_type)触发的
    //   floor.getFloorData 响应, 条目缺回复上下文数据, 宿主模板把根回复
    //   渲染成"空名+冒号"前缀; 同帖同数据下应用自构请求的响应渲染正常;
    // - 实测驱动过三组参数: {pn,sort,load_type:3}/{pn,sort}/{pn,sort}·
    //   sort=1/3 交替, 全部带冒号; 而原生点击与应用 sortChange 事件
    //   (参数由应用构造)的响应在所有轮次中从未出现冒号;
    // - 推断: 响应形状跟随请求来源而非参数值——应用发请求时会附带某个
    //   上下文字段/加工步骤, 裸 naRequest 不具备(具体字段未探明, 探针
    //   只证实了响应差异存在)。
    // 因此本脚本只做两类触发: ①包装层对响应重排(不发请求); ②程序化
    // .click() 原生按钮——请求仍由应用自己的代码路径构造, 参数必然兼容

    // 首屏补发: 桥接就绪可能晚于应用的首屏请求(响应已被消费、来不及重排,
    // 首屏停留在服务端默认序), 程序化点击一次"最早"按钮补一次升序刷新,
    // 请求走应用自己的代码路径, 保证打开浮层即为"最早"序; 若首屏请求尚未
    // 发出, 本次点击与首屏的两次响应都会被包装层按当前语义(默认最早=升序)
    // 重排, 结果一致。找不到按钮(极早期/结构漂移)时静默跳过——首屏保持
    // 服务端默认序, 仅失去自动升序, 用户点 tab 仍可用。
    // 150ms 固定延时的真实语义(2026-11-25 真机"先改名后归位"感知归因):
    // 点早于 tab 行渲染会空转浪费唯一一次补发, 点晚则用户先看到胶囊压在
    // 改名后的"最晚"位再跳走——这里改为"桥接就绪后持续探测 tab 出现,
    // 出现即点", 把补发点击从固定 150ms 收敛到"tab 可点的最早时刻"
    function bridgeSupplementalRefresh() {
        var n = 0;
        var timer = setInterval(function () {
            n++;
            var clicked = false;
            try {
                var tabs = getTabs();
                if (tabs && tabs.early) { tabs.early.click(); clicked = true; }
            } catch (e) { clicked = true; }
            if (clicked || n >= 20) { clearInterval(timer); }
        }, 100);
    }

    // 拿页面事件总线: webpack 5 运行时把模块注册表挂在 webpackChunk* 数组上,
    // 塞一个假 chunk 即可拿到 __webpack_require__; TbInfra 从已加载模块缓存
    // (require.c, 部分构建不暴露)或全模块表(require.m)按形状识别。
    // 导出形态两种都要认: 事件总线直接挂在模块导出上, 或包在 .TbInfra 属性里
    // (22.14 实测为后者, CDP 实证 mod.TbInfra 才是总线实例)。
    // 22.14 的 TbInfra 模块(8462)是纯转发 shim(e.exports=commonBase, 真身在
    // chunk 运行时闭包里), 工厂源码不含 "TbInfra" 字面量, 源码预筛会漏——
    // 所以还要对 shim 形态的工厂做第二遍扫描: 用假 module 对象手动执行工厂
    // (shim 只做一次赋值, 安全; 含内部 require 调用的复杂工厂立即抛错被跳过),
    // 从捕获到的 exports 里做同样的形状校验
    function asTbInfra(ex) {
        if (!ex) { return null; }
        try {
            var cand = (typeof ex.dispatch === 'function' && typeof ex.on === 'function') ? ex
                : (ex.TbInfra && typeof ex.TbInfra.dispatch === 'function') ? ex.TbInfra : null;
            if (cand && typeof cand.off === 'function' && cand.lifecycle
                && cand.lifecycle._feListenerMap) {
                return cand;
            }
        } catch (e) { }
        return null;
    }
    function findTbInfra(req) {
        // req.c/req.m 是 webpack 运行时的非公开字段, 部分构建形态可能不
        // 挂载(getter 抛错/只读代理), 统一按"不存在"处理走下一级探测
        var cached = null;
        try { cached = req.c; } catch (e) { }
        if (cached) {
            for (var id in cached) {
                var hit = asTbInfra(cached[id] && cached[id].exports);
                if (hit) { return hit; }
            }
        }
        var modules = null;
        try { modules = req.m; } catch (e2) { }
        if (!modules) { return null; }
        // 纯转发 shim 的工厂形态: function(e){ [可选 "use strict";] e.exports=标识符 }
        // (22.14 的 TbInfra 模块即此形态, 真身在 chunk 运行时闭包变量里)。
        // 只对命中该形态的工厂做假 module 手动执行——单语句赋值无副作用;
        // 含内部 require 调用/初始化逻辑的复杂工厂绝不执行, 避免以残缺参数
        // 跑工厂体污染页面模块状态
        var shimRe = /^function\s*\(\s*\w+\s*\)\s*\{\s*(?:"use strict";\s*)?\w+\.exports\s*=\s*[A-Za-z_$][\w$]*\s*;?\s*\}$/;
        for (var mid in modules) {
            var src;
            try { src = Function.prototype.toString.call(modules[mid]); } catch (e) { continue; }
            if (src.indexOf('TbInfra') !== -1) {
                try {
                    var hit2 = asTbInfra(req(mid));
                    if (hit2) { return hit2; }
                } catch (e2) { }
            }
            if (shimRe.test(src)) {
                try {
                    var fake = { exports: {} };
                    modules[mid](fake);
                    var hit3 = asTbInfra(fake.exports);
                    if (hit3) { return hit3; }
                } catch (e3) { }
            }
        }
        return null;
    }

    // 桥接初始化: 只在 Vue2 探测失败(页面上有排序 tab 但找不到 __vue__ 链)
    // 时调用。应用尚未注册 notify 监听器(挂载晚于注入)时允许有限次重试;
    // 结构性失败(缓存/注册表漂移)即永久放弃, 按钮退化为原生语义
    function tryBridge() {
        if (bridgeMode || bridgeStopped) { return; }
        try {
            var chunkKey = null;
            for (var k in window) {
                if (/^webpackChunk/.test(k) && Array.isArray(window[k])) { chunkKey = k; break; }
            }
            if (!chunkKey) { bridgeStopped = true; return; }
            var req = null;
            window[chunkKey].push([['ts_' + Math.random()], {}, function (r) { req = r; }]);
            if (!req) { bridgeStopped = true; return; }
            var tbi = findTbInfra(req);
            if (!tbi) {
                // 共享 chunk(含 TbInfra 模块)可能晚于注入才加载完, 与"监听器
                // 未注册"一样按有限次重试处理, 超限才视为结构性漂移
                if (++bridgeChunkAttempts >= 8) { bridgeStopped = true; }
                return;
            }
            var map = null;
            try { map = tbi.lifecycle && tbi.lifecycle._feListenerMap; } catch (e0) { }
            var listeners = map && map['notify'];
            if (!Array.isArray(listeners) || !listeners.length) {
                // 应用还没挂载完: 稍后由轮询重试, 超过 5 次放弃
                if (++bridgeListenerAttempts >= 5) { bridgeStopped = true; }
                return;
            }
            var originals = listeners.slice();
            var removed = [];
            for (var i = 0; i < originals.length; i++) {
                try {
                    tbi.off('notify', originals[i]);
                    removed.push(originals[i]);
                } catch (e) {
                    // 卸载中断不能一走了之: 已卸载的监听器必须重挂回去, 否则
                    // 应用的 notify 处理链从此残缺(楼层响应无人处理, 浮层坏死),
                    // 恢复失败(理论上不发生)才放弃
                    for (var r = 0; r < removed.length; r++) {
                        try { tbi.on('notify', removed[r]); } catch (e2) { }
                    }
                    bridgeStopped = true;
                    return;
                }
            }
            var wrap = function (e) {
                // 世代校验: 应用重挂/重建 notify 监听会 off 掉 wrap, 此时对快照
                // originals 的继续分发已成僵尸调用(操作已销毁组件的状态)。
                // 包装被移除即退出, 由 poll 的漂移检测拆桥重建
                var curListeners = null;
                try {
                    curListeners = tbi.lifecycle._feListenerMap
                        && tbi.lifecycle._feListenerMap['notify'];
                } catch (e0) { }
                if (!Array.isArray(curListeners) || curListeners.indexOf(wrap) === -1) { return; }
                try {
                    var detail = e && e.detail || {};
                    // 只动楼中楼楼层响应, 且页面确实挂着排序 UI;
                    // 非法 time 项混入时跳过整页重排(保持服务端顺序, 与旧版
                    // sortByTime 对 NaN 的处理一致)
                    if (detail.key === 'floor.getFloorData' && lzlUiExists()) {
                        var result = detail.data && detail.data.result;
                        var sub = result && result.subpost_list;
                        if (sub && sub.length > 1) {
                            var desc = selectedTab === 'latest';
                            var valid = true;
                            for (var m = 0; m < sub.length; m++) {
                                if (!sub[m] || isNaN(Number(sub[m].time))) { valid = false; break; }
                            }
                            if (valid) {
                                sub.sort(function (a, b) {
                                    return desc ? Number(b.time) - Number(a.time)
                                        : Number(a.time) - Number(b.time);
                                });
                            }
                        }
                    }
                } catch (e2) { }
                for (var n = 0; n < originals.length; n++) {
                    try { originals[n](e); } catch (e3) { }
                }
                // 高亮不接管: 重排后的回显重渲染会动画重画胶囊, 这是
                // 宿主自带行为, 脚本不写 class(见 applyTabStyle 注释)
            };
            tbi.on('notify', wrap);
            bridgeTbi = tbi;
            bridgeWrapRef = wrap;
            bridgeMode = true;
            // 首屏补发(程序化点击, 见 bridgeSupplementalRefresh): 请求由应用
            // 自构, 响应经包装层按默认"最早"序重排。只发一次: 漂移拆桥重建
            // 不重发——重建循环里每次补发=一次 reload(H5 短暂无响应, 真机
            // 表现"点不动")+一次幽灵点击覆盖用户刚选的 tab(2026-11-25 真机
            // 回归实证), 首发已保证过升序, 重建后由包装层按 selectedTab 重排
            if (!bridgeRefreshDone) {
                bridgeRefreshDone = true;
                bridgeSupplementalRefresh();
            }
        } catch (e) {
            // 结构性失败(找不到 chunk 数组/require 异常等): 放弃桥接,
            // 按钮退化为原生语义, 不影响宿主
            bridgeStopped = true;
        }
    }

    // 世代漂移检测: 桥接就绪后监听表里应只剩 wrap 一个条目(originals 已被卸载)。
    // 应用重挂/新增 notify 监听(off 旧的、on 新的)后长度或身份不符——此时
    // 旧快照不可信、新监听器绕过重排, 必须拆桥重建而非继续沿用旧包装
    function bridgeListenerDrift() {
        try {
            var cur = bridgeTbi && bridgeTbi.lifecycle
                && bridgeTbi.lifecycle._feListenerMap
                && bridgeTbi.lifecycle._feListenerMap['notify'];
            return !Array.isArray(cur) || cur.length !== 1 || cur[0] !== bridgeWrapRef;
        } catch (e) {
            return false;
        }
    }

    // 把两个 tab 的 class 对齐到 selectedTab 对应的原生激活/未激活组合。
    // 仅服务 Vue2 本地排序路径; 桥接模式高亮完全交还原生——胶囊的
    // "消失-回弹"动画是宿主 H5 自带行为(模块关/开表现一致, 实机对照),
    // 原生点击后自会回到被点按钮上, 脚本写 class 只会与 Vue 重渲染
    // 竞争引入额外闪烁(2026-11-22 实机验证后拆除整套 pill 保持机制)。
    // Vue3 页面(桥接宿主)必须直退: Vue2 探测失败≠可写 class——桥接未建立
    // 的窗口期(初始化失败/漂移拆桥后)落到这里时, 本函数是唯一仍在写
    // class 的路径, 与 Vue 重渲染竞争产生胶囊抖动/字号异常(2026-11-25
    // 真机"字体变大"回归的机制之一, 与 2026-11-22 拆 pill 机制同一教训)
    function applyTabStyle() {
        if (bridgeMode || !findLzlVm()) { return; }
        try {
            var tabs = getTabs();
            if (!tabs) { return; }
            var act = selectedTab === 'early' ? tabs.early : tabs.latest;
            var ina = selectedTab === 'early' ? tabs.latest : tabs.early;
            if (classSnap) {
                if (act.className !== classSnap.active) { act.className = classSnap.active; }
                if (ina.className !== classSnap.inactive) { ina.className = classSnap.inactive; }
            } else {
                // 快照失败(两按钮 class 相同等)时的兜底; add/remove 对已处于
                // 目标态的元素是无操作, 不会触发 observer 自循环
                act.classList.add('active-tab');
                ina.classList.remove('active-tab');
            }
        } catch (e) { }
    }

    // H5 重建 tab 行后文本与激活态都会被还原成原生状态, 需要立即修复;
    // 200ms 防抖把重建引起的变更风暴合并成一次修复, 2s 轮询兜底。
    // 修复动作自身触发的 observer 回调因"无差异不写 DOM"而自然终止。
    // 哨兵为假(该页无 lzl 界面)时跳过 renameTabs 的全文档扫描——变更风暴期
    // 每 200ms 一次的全量扫描是这类页面的主要持续开销, 交由轮询退避兜底
    function scheduleHeal() {
        if (healScheduled) { return; }
        healScheduled = true;
        setTimeout(function () {
            healScheduled = false;
            // tab 已就位(缓存命中)时跳过 renameTabs 的全文档扫描, 只修激活态
            if (!resolveTabs() && lzlUiExists()) {
                renameTabs();
            }
            applyTabStyle();
        }, 200);
    }

    // 浮层首开的"原生 tab 闪现"收敛(2026-11-25 真机反馈: 打开楼中楼先看到
    // "热门|最早", 改名后胶囊还在原"最早"位, 补发点击后才跳到左侧"最早"):
    // 注入点(onPageFinished)只跑一次, 此刻 H5 往往还没渲染出 tab 行——首屏
    // 的改名此前完全依赖 observer(200ms 防抖)/轮询(2s)兜出, 原生组合裸奔
    // 可见近一帧。这里用短周期 MutationObserver 等待: tab 一出现立即改名,
    // 把"原生组合可见窗口"从一帧压到改名单次执行的耗时; 30 次上限是防御
    // (极慢页面 6s 后交还给常规 observer/轮询节奏)。立即改名不等于立即归位:
    // 胶囊归位仍等首屏补发点击(链路见 bridgeSupplementalRefresh), 这是把
    // 裸奔窗口从"文本+胶囊都错"压到"只胶囊暂位"
    function watchFirstTabs() {
        var n = 0;
        var timer = setInterval(function () {
            n++;
            var done = false;
            try {
                if (lzlUiExists()) {
                    renameTabs();
                    done = resolveTabs() != null;
                }
            } catch (e) { done = true; }
            if (done || n >= 30) { clearInterval(timer); }
        }, 200);
    }
    try { watchFirstTabs(); } catch (e) { }

    try {
        if (window.MutationObserver && document.body) {
            new MutationObserver(scheduleHeal).observe(document.body, {
                childList: true,
                subtree: true,
                attributes: true,
                attributeFilter: ['class']
            });
        }
    } catch (e) { }

    // 点击命中哪个 tab: 先按 DOM 包含双向判定(target 在叶子内, 或叶子在
    // target 内——原生监听常绑在文字叶子的祖先上, 单向判定会漏掉 pill 区域
    // 的点击), 再按点击坐标落在两叶子包围盒的哪一侧兜底(两判定都覆盖不到
    // "5条楼中楼"计数文本, 它在包围盒外)。返回 'early' | 'latest' | null
    function tabHit(ev, tabs) {
        var t = ev.target;
        if (nodeIn(t, tabs.early)) { return 'early'; }
        if (nodeIn(t, tabs.latest)) { return 'latest'; }
        if (nodeIn(tabs.early, t) && !nodeIn(tabs.latest, t)) { return 'early'; }
        if (nodeIn(tabs.latest, t) && !nodeIn(tabs.early, t)) { return 'latest'; }
        try {
            var r1 = tabs.early.getBoundingClientRect();
            var r2 = tabs.latest.getBoundingClientRect();
            // 容差随 tab 实际高度自适应(不同宿主/UI 密度下 pill 尺寸不同),
            // 12/8px 仅作下限保留原行为; 包围盒为 0(display:none 等)时退化回下限
            var h = Math.max(r1.height, r2.height);
            var padX = Math.max(12, h * 0.5);
            var padY = Math.max(8, h * 0.25);
            var x = ev.clientX, y = ev.clientY;
            var minX = Math.min(r1.left, r2.left) - padX;
            var maxX = Math.max(r1.right, r2.right) + padX;
            var minY = Math.min(r1.top, r2.top) - padY;
            var maxY = Math.max(r1.bottom, r2.bottom) + padY;
            if (x < minX || x > maxX || y < minY || y > maxY) { return null; }
            // 分界线取两盒之间间隙的中点(默认"最早"在左); 盒重叠或顺序
            // 颠倒时退化取两个中心点的中点, 保证判定仍落在两者之间
            var mid;
            if (r1.right <= r2.left) { mid = (r1.right + r2.left) / 2; }
            else if (r2.right <= r1.left) { mid = (r2.right + r1.left) / 2; }
            else {
                mid = ((r1.left + r1.right) / 2 + (r2.left + r2.right) / 2) / 2;
            }
            return x < mid ? 'early' : 'latest';
        } catch (e) {
            return null;
        }
    }

    // 拦截两个 tab 的点击, 全部改为本地时间序排序, 不走 H5 状态机:
    // 原生"最早"按钮现显示"最晚", 其原生行为(升序重载)与标签语义相反, 必须拦截;
    // 拦截后 H5 的 activeSortId 不变, 不会发请求重载数据。
    // preventDefault/stopPropagation 只在本地排序成功时执行: 探测瞬时失败
    // (H5 重建、__vue__ 挂载点漂移等)时放行原生行为, 按钮退化为原生语义而非"死掉"
    document.addEventListener('click', function (ev) {
        try {
            var tabs = getTabs();
            if (!tabs) { return; }
            // 桥接模式: 两个 tab 全部放行原生点击(见头部注释第 3 条——
            // 自派请求的响应必缺回复上下文数据, 冒号根因), 请求参数由应用
            // 自己构造、形状必然兼容; 升/降序语义与请求的 sort 值无关,
            // 全部由 notify 包装层按 selectedTab 对响应整体重排实现。
            // 高亮完全交还原生: 原生点击处理会把激活态打在被点按钮上, 与
            // 改名后标签天然一致, 脚本不写任何 class(见 applyTabStyle 注释)。
            // 限制与 22.13 一致: 点过 tab 后分页请求按原 tab 语义翻页,
            // 追加页由 wrapper 按页内重排——整体顺序不再保证。
            if (bridgeMode) {
                var hit = tabHit(ev, tabs);
                if (hit === 'latest') {
                    userInteracted = true;
                    selectedTab = 'latest';
                } else if (hit === 'early') {
                    userInteracted = true;
                    selectedTab = 'early';
                }
                return;
            }
            var hit2 = tabHit(ev, tabs);
            if (hit2 === 'early') {
                var vm = findLzlVm();
                if (vm && sortByTime(vm, false)) {
                    ev.preventDefault();
                    ev.stopPropagation();
                    userInteracted = true;
                    selectedTab = 'early';
                    applyTabStyle();
                }
            } else if (hit2 === 'latest') {
                var vm2 = findLzlVm();
                if (vm2 && sortByTime(vm2, true)) {
                    ev.preventDefault();
                    ev.stopPropagation();
                    userInteracted = true;
                    selectedTab = 'latest';
                    applyTabStyle();
                }
            }
        } catch (e) { }
    }, true);

    // 轮询间隔: tab 从未出现(连续未命中)时指数退避到最长 10s——那些页面上
    // 每轮只剩哨兵探测这一笔廉价开销; 任意命中即回到 2s 正常节奏
    function pollDelay() {
        return scanMisses > 0 ? Math.min(2000 * scanMisses, 10000) : 2000;
    }

    // H5 重载(下拉刷新/失败重试)会整体重建 lzlList 并还原原生热门序:
    // 引用变化即复位交互态, 恢复自动"最早"排序与默认高亮,
    // 否则用户点过一次 tab 后, 重载页面的高亮与实际排序长期不符
    function detectListReload(vm) {
        try {
            if (vm && vm.lzlList && vm.lzlList !== lastListRef) {
                lastListRef = vm.lzlList;
                if (userInteracted) {
                    userInteracted = false;
                    selectedTab = 'early';
                }
            }
        } catch (e) { }
    }

    function poll() {
        // 页面不可见时跳过本轮全文档扫描, 降低后台 H5 页功耗(定时器仍保持节律)
        if (document.hidden) {
            setTimeout(poll, pollDelay());
            return;
        }
        // 缓存命中时本轮零全文档扫描; tab 组合被 H5 还原/尚未改名时才走 renameTabs
        if (!resolveTabs() && lzlUiExists()) {
            renameTabs();
        }
        if (bridgeMode && bridgeListenerDrift()) {
            // 监听表被应用重挂/新增监听: 旧快照 originals 已成僵尸、新监听器
            // 不经 wrapper(其响应不再重排)。但应用挂载期会瞬时增删监听
            // (组件逐个注册/重挂), 单次漂移就拆桥会进入"拆桥→重建→再重挂"
            // 的 2s 循环——每轮循环都伴随一次补发点击(=reload)与一次 class
            // 写入窗口, 即真机"点不动几秒+字体变大"(2026-11-25 实证)。
            // 连续两轮漂移才认定世代真的更替, 拆桥重建; 首轮漂移只记录,
            // 旧包装的世代校验(见 wrap)已保证僵尸监听器不会误分发
            if (++driftStrikes >= 2) {
                driftStrikes = 0;
                bridgeMode = false;
                bridgeStopped = false;
                bridgeChunkAttempts = 0;
                bridgeListenerAttempts = 0;
            }
        } else {
            driftStrikes = 0;
        }
        if (bridgeMode) {
            // 桥接模式: 数据在响应层重排, 高亮完全交还原生(见 applyTabStyle
            // 注释); 改名/文本复原已在本轮开头处理
            setTimeout(poll, pollDelay());
            return;
        }
        // 用户点击过 tab 之前保持"最早"序(与默认高亮的"最早"标签一致),
        // H5 分页追加的新回复由下一轮轮询排好; 用户点击后不再自动干预
        // (H5 整体重载时 detectListReload 已复位交互态, 自动排序恢复)。
        // vm 探测用廉价哨兵先行过滤: 非楼中楼页每轮付两次 querySelectorAll 是纯浪费
        if (lzlUiExists()) {
            var vm = findLzlVm();
            if (vm) {
                detectListReload(vm);
                if (!userInteracted) {
                    sortByTime(vm, false);
                }
            } else {
                // 有排序 UI 却找不到 Vue2 组件实例: Vue3 页面(22.14+,
                // 生产构建不挂 __vue__), 转桥接模式(应用未挂载完时有限重试)
                tryBridge();
            }
        }
        // applyTabStyle 仅供 Vue2 本地排序路径维护激活态(桥接模式内部直退)
        applyTabStyle();
        setTimeout(poll, pollDelay());
    }

    // 桥接只能在"Vue2 探测失败"之后初始化(见 poll), 不能注入时抢跑:
    // 否则 22.13 等仍带 __vue__ 的宿主会被误切到桥接路径
    poll();
})();
