# -*- coding: utf-8 -*-
"""
书架页改动自检（每次改完 HomeScreen / shelf 包后必跑）。

背景：书架先后返工四次，翻车点高度重复——括号被误删、手势被组件抢走、
板块顺序错位、样式走样。这个脚本把每一条都变成可自动断言的检查项。

用法：
    python tools/audit-shelf.py

退出码 0 = 全部通过；非 0 = 有 FAIL（会打印具体行号）。
"""
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
UI = os.path.join(ROOT, "app", "src", "main", "java", "com", "example", "ui")
HOME = os.path.join(UI, "HomeScreen.kt")
MULTI = os.path.join(UI, "shelf", "ShelfMultiSelect.kt")
HOST = os.path.join(UI, "shelf", "ShelfSelectionHost.kt")

results = []


def check(name, ok, detail=""):
    results.append((bool(ok), name, detail))


def read(path):
    return io.open(path, encoding="utf-8").read().split("\n")


def strip_code(line):
    """去掉字符串与注释，避免把文字里的括号算进来。"""
    s = re.sub(r'"(\\.|[^"\\])*"', '""', line)
    return re.sub(r'//.*', '', s)


def brace_balance(path):
    lines = read(path)
    depth = 0
    in_block = False
    for i, raw in enumerate(lines):
        s = strip_code(raw)
        if in_block:
            if '*/' in s:
                in_block = False
                s = s.split('*/', 1)[1]
            else:
                continue
        if '/*' in s and '*/' not in s:
            in_block = True
            s = s.split('/*', 1)[0]
        depth += s.count('{') - s.count('}')
        if depth < 0:
            return False, "第 %d 行多出一个 }" % (i + 1)
    return (True, "") if depth == 0 else (False, "净差 %d 个 {" % depth)


def line_of(lines, needle, start=0):
    for i in range(start, len(lines)):
        if needle in lines[i]:
            return i
    return -1


# ── 1. 括号平衡（删代码块最容易带走别人的闭合括号） ──────────────────
for label, path in [("HomeScreen", HOME), ("ShelfMultiSelect", MULTI), ("ShelfSelectionHost", HOST)]:
    ok, detail = brace_balance(path)
    check("括号平衡 · %s" % label, ok, detail)

L = read(HOME)

# ── 2. 手势唯一主人：书卡自己不能再挂 pointerInput / clickable ───────
# 锚点不带右括号：items 调用后来追加了 contentType = { "book" }（R2 批次），
# 旧锚点 "…key = { it.id })" 因此永远失配。
i_books = line_of(L, "items(items = sortedBooks, key = { it.id }")
i_fav = line_of(L, 'key = "fav_section_header"')
if i_books < 0 or i_fav < 0:
    check("书籍卡区间可定位", False, "找不到 books items 或收藏板块锚点")
else:
    offenders = [
        (i + 1, L[i].strip()[:70])
        for i in range(i_books, i_fav)
        if re.search(r'\.pointerInput|\.clickable\(|combinedClickable|detectTapGestures', L[i])
    ]
    check(
        "书籍卡不抢手势（长按才有机会）",
        not offenders,
        "发现 %d 处：%s" % (len(offenders), offenders[:3]),
    )
    check(
        "书籍卡已登记几何 itemBounds",
        any("itemBounds(book.id.toString())" in L[i] for i in range(i_books, i_fav)),
    )

# ── 3. 板块顺序：书网格 → 我喜欢的 → 阅读统计 ──────────────────────
i_stats = line_of(L, 'key = "bottom_stats"')
check("阅读统计卡存在", i_stats > 0)
if i_fav > 0 and i_stats > 0:
    check("「我喜欢的」排在阅读统计之前", i_fav < i_stats, "fav@%d stats@%d" % (i_fav + 1, i_stats + 1))
if i_books > 0 and i_fav > 0:
    check("「我喜欢的」排在书籍网格之后", i_books < i_fav)

# ── 4. 样式：不能有与分类栏重复的排序胶囊 ──────────────────────────
check(
    "「我喜欢的」没有排序胶囊（与分类栏重复）",
    not any("FavoriteSort.entries" in l for l in L),
)
check(
    "「我喜欢的」分类栏不单独列出「默认」",
    not any("FAV_DEFAULT_CATEGORY" in L[i] and "CategoryPill" in L[i + 6] for i in range(len(L) - 6)),
)

# ── 5. 隐私：收藏锁必须挂在隐私模式总开关之下 ──────────────────────
lock_line = [l for l in L if "val favoritesLocked" in l]
check(
    "「我喜欢的」锁跟随隐私模式总开关",
    bool(lock_line) and "privacyModeEnabled" in lock_line[0],
    lock_line[0].strip() if lock_line else "未找到 favoritesLocked",
)

# ── 6. 宿主：长按不能因为子级消费就放弃 ────────────────────────────
M = read(MULTI)
# 只看真正的代码（注释里提到 isConsumed 不算）
consumed_code = [
    l.strip() for l in M
    if re.search(r'^\s*if\s*\(\s*change\.isConsumed', l)
]
# 1.4.13: book cards remain passive (the checks above enforce this), but
# interactive overlays are allowed to consume a pointer. The grid must yield
# both its down and its tracking phase, never convert that cancellation to a tap.
_owner_code = "\n".join(M)
check(
    "长按宿主尊重交互子控件，取消不误触点击",
    "if (down.isConsumed) return@awaitEachGesture" in _owner_code and
    re.search(r"if \(change\.isConsumed\)\s*\{\s*aborted = true\s*moved = true\s*break", _owner_code) is not None,
    "交互子控件接管后，宿主仍可能触发书卡点击或长按",
)
check("宿主同时接管短按（onTap）", any("onTap(key)" in l for l in M))

# ── 6b. 长按计时的取消必须宽判 ──────────────────────────────────────
# 翻车史：withTimeout 到时抛出的并不总是 kotlinx 的 TimeoutCancellationException，
# release + R8 下是 Compose 自己的 CancellationException 子类。只 catch 具体类型
# ⇒ 异常被 awaitEachGesture 吞掉、手势循环静默重启 ⇒「长按毫无反应，但点击正常」。
_src = "\n".join(M)
# 注意要找「调用」而不是 import：用 "withTimeout(" 定位
_i_timeout = _src.find("withTimeout(")
_window = _src[_i_timeout:_i_timeout + 2600] if _i_timeout >= 0 else ""
check(
    "长按等待捕获的是宽判的 CancellationException（而非具体 Timeout 子类）",
    "catch (e: kotlinx.coroutines.CancellationException)" in _window,
    "在 withTimeout 附近没找到宽判 catch —— 长按会在 release 包里静默失效",
)
check(
    "宽判取消时区分「真·宿主销毁」与「长按到时」",
    # Job 的取号在 withTimeout 之前（AwaitPointerEventScope 里拿不到协程上下文），
    # 所以全文找取号、窗口里找判活。
    "Job]" in _src and re.search(r"\w*[Jj]ob\.isActive", _window) is not None,
    "缺少 Job.isActive 判断，宿主销毁时的取消会被误判成长按",
)

# ── 6c. 交付前不能残留临时调试日志 ──────────────────────────────────
_dbg = [
    (i + 1, l.strip()[:70])
    for i, l in enumerate(M)
    if re.search(r'android\.util\.Log\.[dvwe]', l)
]
check("ShelfMultiSelect 无残留调试日志", not _dbg, _dbg[:3])

# ── 7. 死代码：HomeScreen 不应再 import detectTapGestures ──────────
check(
    "无残留 detectTapGestures 导入",
    not any(l.startswith("import") and "detectTapGestures" in l for l in L),
)

# ══════════════════════════════════════════════════════════════════
# 第八轮：动效打磨（模拟器实测后固化）
# 这些条目全部来自真机/模拟器量出来的问题，改回去了就会在肉眼可见的地方翻车。
# ══════════════════════════════════════════════════════════════════
H = read(HOST)

# ① 多选顶栏必须是**浮层**（不占位）。
#    翻车史：它曾经是 Column 的子节点（expandVertically 展开），一出现就把整屏内容
#    顶下去 ~56dp —— 长按瞬间整页跳一下（用户说的"闪烁"），更要命的是书卡的
#    itemRect 瞬间全部失效，手指还按着却命中到别的卡 → 选中又被取消。
_home = "\n".join(L)
_i_tb = _home.find("MultiSelectTopBar(")
_win_tb = _home[max(0, _i_tb - 1600):_i_tb] if _i_tb >= 0 else ""
check(
    "多选顶栏是浮层（align(TopCenter)），不占布局高度",
    "align(Alignment.TopCenter)" in _win_tb and any(host in _win_tb for host in ("AnimatedVisibility", "PullOutVisibility")),
    "顶栏一旦参与布局就会把整屏内容顶下去，长按瞬间闪烁 + itemRect 失效",
)
check(
    "多选顶栏不再用展开式入场（会导致内容位移）",
    not any("expandVertically" in strip_code(l) for l in L),
    "expandVertically 会改变高度 → 网格重排 → 落点矩形全废",
)

# ② 底栏被隐藏时，底部元素只能用 NoTabBar 的 inset。
#    注：底部放置坞 DropDock 已整块移除（落点改用页面上的分类胶囊），
#    所以现在只剩「多选操作栏」一处用到，断言从 ≥2 放宽为 ≥1。
check(
    "底部让位用的是 NoTabBar inset（不含被隐藏的 Tab 栏）",
    sum(1 for l in H if "LocalAppBottomInsetNoTabBar.current" in l) >= 1,
    "改用含 Tab 栏的 inset 会让底部凭空空出 92dp（操作栏悬在半空）",
)
check(
    "操作栏没有误用含 Tab 栏的 inset",
    not any("LocalAppBottomInset.current" in l for l in H),
    "ShelfSelectionHost 里不允许出现 LocalAppBottomInset.current",
)

# ③ 呼吸动画必须在「滚动 / 多选 / 拖拽」时冻结
_breathe = "\n".join(L)
_i_br = _breathe.find("breathingProgress.floatValue =")
_win_br = _breathe[max(0, _i_br - 1600):_i_br] if _i_br >= 0 else ""
_no_breathe = "breathingProgress" not in _breathe and "rememberInfiniteTransition(" not in _breathe
check(
    "呼吸动画已停用，或在滚动时冻结",
    _no_breathe or "isScrollInProgress" in _win_br,
    "滚动中重绘会直接吃掉帧预算",
)
check(
    "呼吸动画已停用，或在多选/拖拽时冻结",
    _no_breathe or "ShelfPhase.BROWSE" in _win_br,
    "拖拽中卡片还在 ±3dp 浮动 → itemRect 抖动 → 明明对准了却放不进去",
)

# ④ 归位占位框必须用像素级 layout 定位，不能走 offset+size 的 dp 换算
_i_ph = "\n".join(H).find("private fun PlaceholderBox")
_win_ph = "\n".join(H)[_i_ph:_i_ph + 1200] if _i_ph >= 0 else ""
check(
    "原位占位框用 Modifier.layout 做像素级定位",
    "Modifier.layout" in _win_ph or ".layout {" in _win_ph,
    "offset{}+size(dp 换算) 曾画出「比书卡大 3 倍、往上偏 290px」的鬼影框",
)

# ⑤ 放置目标必须是页面里的分类胶囊，不能另起一个底部 Dock
check(
    "已移除底部放置坞 DropDock",
    not any(l.startswith("private fun DropDock(") for l in H),
    "用户明确要求：拖到分类就是拖到「我的书架 / 我喜欢的」下面那一排分类按钮，不要另起一栏",
)
check(
    "分类胶囊已注册为拖拽落点（书架 + 我喜欢的）",
    any("dropTargetOfCategory(" in l for l in L) and
    any("dropTargetOfFavoriteCategory(" in l for l in L),
    "分类栏没挂 dropTargetModifier，拖过去不会有任何落点",
)
# 高亮只能改颜色/缩放，不能改文案（改文案会让兄弟 chip 位移甚至换行，手底下的目标就跑了）
check(
    "落点高亮不改文案（用 dropHighlight 只动配色与缩放）",
    "dropHighlight" in "\n".join(H) or "dropHighlight" in "\n".join(L),
    "缺少 dropHighlight → 拖到分类上没有视觉反馈",
)

# ⑥ 归位落点必须登记**封面**几何，不能只用整卡矩形
check(
    "归位用封面几何（coverRect），不是整卡矩形",
    "coverRect(" in "\n".join(H) and "coverBoundsModifier" in "\n".join(L),
    "用整卡矩形当落点会让书飞到比原位低半张封面的地方，动画结束瞬间从下面瞬移到上面",
)

# ⑥ 书卡的缩放语义必须统一为「内缩」：不能同时叠加放大与缩小
check(
    "书卡不再同时叠加 1.05 与 0.94 两个 scale",
    not any("1.05f" in l for l in L),
    "两个 scale 相乘 ≈ 0.987 等于互相抵消，还会让所有选中项额外上浮 8dp",
)

# ── ⑦ 拖拽期页面必须「只在你想让它滚的时候、以可控速度滚」 ──────────
# 用户反馈："拖动到分类触发的时候手指不是刚好在分类之上，有时候拖动会触发页面上下滑动"
_m = "\n".join(M)
_h = "\n".join(H)
check(
    "边缘滚动用 root 坐标判定（不能拿局部 at.y 直接比 hostTop）",
    "val root = at + hostOffset" in _m and "rootY < hostTop + edge" in _m and
    # ⚠️ 必须用 strip_code：注释里为了说明这个坑，本身就写了 `at.y < hostTop`
    not any(
        "at.y < hostTop" in strip_code(l) or "at.y > hostBottom" in strip_code(l)
        for l in M
    ),
    "at.y 是局部坐标、hostTop 是 root 坐标，混用会让上滚感应带膨胀到 368px、下滚彻底失效",
)
check(
    "边缘滚动有驻留延迟，且计时在宿主的滚动循环里（不是 moveDrag）",
    "EDGE_ARM_NS" in _h and "inEdgeSince" in _h and "edgeEnterAt" not in _m,
    "计时放进 moveDrag 就永远推不动：手指停在边缘不动就不再产生 MOVE 事件，页面纹丝不动",
)
check(
    "落点带迟滞 + 要求完整可见（防半露胶囊误命中与高亮闪烁）",
    "pickTarget(" in _m and "HYSTERESIS" in _m and
    "it.top >= hostTop && it.bottom <= hostBottom" in _m,
    "只校验中心会让滚到一半只露出半截的胶囊被命中：手指明明在空白处却触发了分类",
)
_h = "\n".join(H)
check(
    "边缘滚动节奏固定，且不再重复乘 density",
    "LaunchedEffect(state.phase) {" in _h and "scrollBy(v)" in _h and
    "scrollBy(v * density.density)" not in _h and "EDGE_TICK_MS" in _h,
    "key 里带 edgeScroll 会让协程随手指事件反复重启，实际速度 = 事件频率 × 速度；"
    "edgeScroll 已是 px，再乘 density 又是 3 倍",
)
check(
    "拖拽期禁掉分类栏自己的横向滚动",
    _h.count("horizontalScroll(") == 0 and
    "\n".join(L).count(".horizontalScroll(rememberScrollState())") == 0 and
    "enabled = shelfSelection.phase !=" in "\n".join(L),
    "手指在胶囊上左右挪动会把这排胶囊滑走，落点矩形跟着漂，看着像「没对准却触发了」",
)

# ══════════════════════════════════════════════════════════════════
# 第九轮：归位飞行交班 + 心形美工统一
# ══════════════════════════════════════════════════════════════════
FAV = os.path.join(UI, "favorite", "FavoriteHeart.kt")
FLY = os.path.join(UI, "favorite", "ShelfFly.kt")
ART = os.path.join(UI, "favorite", "HeartArt.kt")
F = read(FAV)
Y = read(FLY) if os.path.exists(FLY) else []
A = read(ART) if os.path.exists(ART) else []

# ① 归位期间真实卡片必须继续藏着。
#    翻车史（用户原话）：「书架拖动后松手，原位置立马浮现书本，然后拖动书本才做曲线
#    运动回到原位置，而且原位置和书本远处完全不对齐」。
#    根因：松手瞬间 dragging 就清空，而幽灵卡还要飞 ~560ms；只按 dragging 判定可见性，
#    真实卡片会在松手那一刻立刻浮现 → 屏幕上同时有「已经站好的书」和「还在半空飞的书」。
check(
    "HomeScreen 的 dimTarget 把 flyingHome 也算作「隐藏」",
    "isFlyingHome" in "\n".join(L) and
    re.search(r"if \(isDragged \|\| isFlyingHome\) 0f", "\n".join(L)) is not None,
    "少了 isFlyingHome ⇒ 松手那一刻真实卡片立刻显形，和还在飞的幽灵卡重叠成「两个副本」",
)
check(
    "宿主在松手时调用 beginReturnFlight（进入归位态）",
    "state.beginReturnFlight(items)" in _h,
    "没有 beginReturnFlight，flyingHome 永远是空集",
)
check(
    "幽灵卡落位时才 endReturnFlight + 清 pendingFlight（同一帧交班）",
    "state.endReturnFlight()" in _h and "pendingFlight = null" in _h,
    "先清幽灵再显形（或反过来）都会在落位瞬间闪一下",
)
check(
    "归位落点每帧重读**每个条目自己的**真实封面矩形（不能用起飞快照）",
    "liveOrigin" in _h and "liveOrigin(key)" in _h and
    "localRect(state.coverRect(key))" in _h,
    "用快照 ⇒ 松手后卡片进选中态改尺寸/页面滚动，落点就偏了（用户说的「完全不对齐」）",
)
check(
    "归位幽灵卡收敛到选中态缩放（SELECTED_SCALE），不是 1.0",
    "settleTo" in _h and "AppMotion.SELECTED_SCALE" in _h,
    "收到 1.0 ⇒ 落位瞬间幽灵卡比真实卡片大一圈，看着就是没对齐",
)
check(
    "幽灵卡在归位全程 alpha 保持 1（落位前不淡出）",
    "if (returning) 1f else" in _h,
    "提前淡出会与已经显形的真实卡片叠成半透明双影",
)

# ② 心形只有一套美工。
#    翻车史（用户原话）：「爱心飞出来做曲线运动，效果做的太差了太简陋了…太不苹果了」。
#    旧版飞行体是 Material 的纯色扁平 Icon 装在一个白底描边圆角框里，像贴纸。
check("HeartArt 美工文件存在（按钮 / 角标 / 书架 HeartBurst 共用）", os.path.exists(ART))
check(
    "「喜欢」按钮的实心态用 HeartArt（不是 Material 扁平剪影）",
    "HeartArt(" in "\n".join(F) and "Icons.Filled.Favorite" not in "\n".join(F),
    "扁平剪影与渐变心摆在一起，点亮瞬间像换了材质",
)
check(
    "心形颜色只有一套（HeartMid = #FF4D6D）",
    "0xFFFF4D6D" in "\n".join(A) and "HeartMid" in "\n".join(F),
    "别处再调一个红，同一屏里会出现两种心色",
)

# ②b 「飞向书架」的爱心/迷你封面动效必须**整体不存在**。
#    翻车史（用户原话）：「这个爱心效果本身加的就很多余 不要爱心飞来飞去了」。
#    上一轮把它做精致（渐变心 + 三次贝塞尔 + 切线姿态 + 拖尾 + 落点弹跳）并没有解决
#    根本问题：**「喜欢」和「底部书架图标」之间没有真实的因果关系**（喜欢 ≠ 进书架），
#    一个横穿屏幕的飞行体纯属视觉噪音。于是 ShelfFly 控制器 + 全屏覆盖层 +
#    落点弹跳全部删除，反馈收回到按钮自身（弹簧过冲 + 粒子）。
#    ⚠️ 不要再往回加任何"飞向某处"的心形/迷你封面。
_SRC = os.path.join(ROOT, "app", "src", "main", "java")
_ALL = []
for _d, _sub, _files in os.walk(_SRC):
    for _f in _files:
        if _f.endswith(".kt"):
            _ALL.append(os.path.join(_d, _f))


def strip_comments(path):
    """去掉块注释与行注释后再扫标识符：注释里点名 ShelfFly 是「别再加回来」的说明，
    不该被当成"代码里还在引用"而误报。"""
    out = []
    in_block = False
    for raw in read(path):
        s = raw
        if in_block:
            if "*/" in s:
                in_block = False
                s = s.split("*/", 1)[1]
            else:
                continue
        while "/*" in s:
            pre, rest = s.split("/*", 1)
            if "*/" in rest:
                s = pre + rest.split("*/", 1)[1]
            else:
                in_block = True
                s = pre
                break
        out.append(re.sub(r"//.*", "", s))
    return out


_TXT = "\n".join("\n".join(strip_comments(p)) for p in _ALL)

check(
    "ShelfFly.kt 已删除（飞行控制器/覆盖层/落点弹跳）",
    not os.path.exists(FLY),
    "文件还在 ⇒ 有人又把它捡回来了",
)
check(
    "全工程不再引用 ShelfFly / ShelfFlyOverlay / MiniHeartBadge",
    "ShelfFly" not in _TXT and "MiniHeartBadge" not in _TXT,
    "MainActivity 里挂回 ShelfFlyOverlay() 就会重新开始飞",
)
check(
    "Motion 里的飞行令牌已清干净（FLY_MS / FLY_TRAILS / LAND_RING_MS / easeThrow）",
    "FLY_MS" not in _TXT and "FLY_TRAILS" not in _TXT and
    "LAND_RING_MS" not in _TXT and "easeThrow" not in _TXT,
    "留着没人用的令牌，下次改动会被误导着又写一遍飞行",
)
check(
    "「喜欢」按钮不再上报飞行锚点（positionInWindow / onMeasured 簿记已删）",
    "positionInWindow" not in "\n".join(F) and "onMeasured" not in "\n".join(F),
    "锚点簿记是飞行的前提，留着说明只删了一半",
)
check(
    "「喜欢」按钮不再接收 coverUrl（飞行缩略图的唯一用途）",
    "coverUrl" not in "\n".join(F),
    "coverUrl 只在飞缩略图时用得到，重新传进来就是飞行要回来的信号",
)

# ④ 「我喜欢的」长按必须能进多选
#    翻车史：收藏卡片自己挂了 pressScale(clickable)，down 事件被它吃掉，
#    宿主的长按等待循环一等不到超时就被判成「点击」，长按完全没反应。
_i_fav_card = line_of(L, "items(items = favSorted")
# 收藏卡片 item 体量在 100 行上下；用固定窗口，别去找下一个板块锚点
# （fav_section_header 在它**上面**，向后找会一路扫到文件末尾，把无关代码算进来）
_span = L[_i_fav_card:_i_fav_card + 120] if _i_fav_card > 0 else []
check(
    "收藏卡片不抢手势（hostOwnsGesture），长按才有机会",
    bool(_span) and not any(
        re.search(r"\.pointerInput|\.clickable\(|combinedClickable|pressScale\(", strip_code(l))
        for l in _span
    ) and any("hostOwnsGesture" in l for l in _span),
    "收藏卡自己挂点击 ⇒ down 被消费 ⇒ 「我喜欢的」长按毫无反应",
)
check(
    "收藏卡片已登记几何（itemBounds + coverBoundsModifier）",
    bool(_span) and any("itemBounds(" in l for l in _span) and
    any("coverBoundsModifier(" in l for l in _span),
    "没登记几何 ⇒ 宿主命中不到它，长按/拖拽都无效",
)
check(
    "收藏卡用 fav:: 前缀与书籍卡区分",
    'SHELF_KEY_FAV_PREFIX' in "\n".join(H) and "favShelfKey(" in "\n".join(L),
    "两套 key 混在一起 ⇒ 点击分发/命中判定会认错对象",
)

# ══════════════════════════════════════════════════════════════════
# 第十二轮：拖进分类的提示语义 / 收藏能不能真的归类 / 多本各自归位
# ══════════════════════════════════════════════════════════════════
MA = os.path.join(ROOT, "app", "src", "main", "java", "com", "example", "MainActivity.kt")
FAVP = os.path.join(UI, "favorite", "FavoritesPanel.kt")
_m = "\n".join(read(MA))
_fp = "\n".join(read(FAVP))
_hc = "\n".join(strip_comments(HOST))

# ① 「操作出错」的假错误提示
#    翻车史（用户原话）：「拖进别的分类总是显示操作出错，问题是操作根本没有出错」。
#    根因：SnackbarHost 一律把消息渲染成 AppErrorSnackbar（红色错误卡 + 固定标题
#    「操作出错」），而 showUndo() 弹的是「已移动 2 本到『X』」这种**成功 + 撤销**提示。
check(
    "SnackbarHost 按语义分流（不能一律渲染成红色错误卡）",
    "AppSnackbarVisuals" in _m and "AppSnackKind.TOAST" in _m and
    "AppToastSnackbar(" in _m and "AppErrorSnackbar(" in _m,
    "所有提示都套「操作出错」⇒ 成功的移动也报红，用户被反复误导",
)
check(
    "「撤销」提示走 TOAST 语义，不是 ERROR",
    "showAppSnackbar(" in _m and "AppSnackKind.TOAST" in _m and
    "AppSnackKind.ERROR" in _m,
    "成功提示挂 ERROR 语义 = 假错误弹窗",
)

# ② 「我喜欢的」拖进分类必须真的生效
#    翻车史：onDropToCategory / onDropToFavoriteCategory 只写
#    `sortedBooks.filter { it.id.toString() in keys }`，而收藏卡片的 key 带 fav:: 前缀，
#    一条都匹配不到 ⇒ list 为空 ⇒ 什么都不做，提示却照弹。
check(
    "拖到分类时必须按 fav:: 前缀分流（书架书 ↔ 我喜欢的）",
    "isFavShelfKey(it)" in "\n".join(L) and "SHELF_KEY_FAV_PREFIX" in "\n".join(L),
    "只认 book.id ⇒ 收藏拖进分类永远匹配不到，静默失败",
)
check(
    "收藏落到分类要真的调用 onMoveFavoritesToCategory",
    "onMoveFavoritesToCategory(favKeys, name)" in "\n".join(L),
    "没有这条调用 ⇒ 「我喜欢的」拖进分类依旧无效",
)
check(
    "收藏分类的撤销要还原到原分类（移动前先记下来）",
    "favoriteItems.find { it.key == k }" in "\n".join(L),
    "撤销若不知道原分类，撤销后收藏会卡在新分类里",
)

# ③ 多本一起拖，每一本都要回到**自己**的原位
#    翻车史（用户原话）：「选中多个一起移动但是松开只会移动回一本书的原处
#    而不是多本书各自回到原处，这导致和拖动按住时的动画有很大割裂感，从多本变成一本」。
#    根因：DragFlight 只有单个 origin，DragFlightLayer 只画 items.first() 一张。
check(
    "DragFlight 携带每个条目各自的原位（origins map）",
    "val origins: Map<String, androidx.compose.ui.geometry.Rect>" in _hc,
    "只有一个 origin ⇒ 多本只能共用第一本的原位",
)
check(
    "飞行层按条目逐个绘制（不是只画 first()）",
    "flight.items.take(4)" in _hc and "coverOf(key)" in _hc and
    "flight.items.firstOrNull().orEmpty()" not in _hc,
    "只画一张 ⇒ 松手瞬间从「一摞」塌成「一本」，与拖拽态严重割裂",
)
check(
    "每本用各自的落点（origins[key]），不是统一的 flight.to",
    "flight.origins[key]" in _hc,
    "共用 flight.to ⇒ 所有书叠着飞到同一点",
)

# ④ 「我喜欢的」拖动时原处必须和书架一样消失
#    翻车史（用户原话）：「我喜欢的拖动的时候原处书本没有跟我的书架一样原处消失」。
check(
    "收藏卡片有 hidden（被拖起/归位中 → alpha 0）",
    "hidden: Boolean = false" in _fp and "if (hidden) 0f" in _fp,
    "只有 dimmed(0.85) ⇒ 原位留着一张卡，和手上的幽灵卡同时出现",
)
check(
    "HomeScreen 把 dragging / flyingHome 传给收藏卡片的 hidden",
    "favKey in shelfSelection.dragging" in "\n".join(L) and
    "favKey in shelfSelection.flyingHome" in "\n".join(L),
    "不传 ⇒ 两个板块的拖动手感不一样",
)

# ══════════════════════════════════════════════════════════════════
# 第十三轮：移动之后的"幽灵热区"（点不动 / 选不中 / 书消失）
# ══════════════════════════════════════════════════════════════════
#    翻车史（用户原话）：「移动会出现很多bug，比如移动后无法点进去，
#    而且无法长按其他书后点击它选中，移动后发生一些书莫名其妙找不到的问题」。
#    根因只有一个：三张几何表（itemRects / coverRects / targetRects）**只写不删** ——
#    onGloballyPositioned 没有 dispose 回调，条目消失后矩形永久残留。
check(
    "状态机能按「真实存在的名单」裁剪几何表",
    "fun pruneGeometry(" in "\n".join(M) and
    "itemRects.keys.retainAll(aliveItems)" in "\n".join(M) and
    "coverRects.keys.retainAll(aliveItems)" in "\n".join(M) and
    "targetRects.keys.retainAll(aliveTargets)" in "\n".join(M),
    "只写不删 ⇒ 书移走后那块区域变成幽灵热区：点不动、选不中",
)
check(
    "宿主在 aliveKeys / aliveTargets 变化时触发裁剪",
    "LaunchedEffect(aliveKeys, aliveTargets)" in _h and
    "state.pruneGeometry(aliveKeys, aliveTargets)" in _h,
    "不裁剪 ⇒ 幽灵矩形一直生效",
)
check(
    "HomeScreen 把真实条目集合与收藏分类交给宿主",
    "aliveKeys = shelfAliveKeys" in "\n".join(L) and
    "favoriteCategories = favCustomCategories" in "\n".join(L) and
    "val shelfAliveKeys = remember(" in "\n".join(L),
    "不传 ⇒ 宿主无从判断哪些条目是真的还活着",
)
check(
    "落点分类不存在时拒绝移动（书被写进不存在的分类 = 凭空消失）",
    "categories.none { it.name == name }" in "\n".join(L) and
    "不是可用的分类，没有移动" in "\n".join(L),
    "把书归到一个已删除的分类名 ⇒ 它在任何分类下都不显示",
)
check(
    "新飞行起飞前先把上一批 flyingHome 交班（旧的 onDone 永远不会再被调用）",
    "if (pendingFlight != null) {" in _h and "state.endReturnFlight()" in _h,
    "上一次飞行被顶掉 ⇒ 那批书永远停在 flyingHome = alpha 恒 0",
)

# ⑤ 交付前不能残留调试脚手架
_all = "\n".join(L) + "\n" + _h + "\n" + "\n".join(Y) + "\n" + "\n".join(M)
check(
    "无残留调试脚手架（SLOWMO / DEBUG 浮层 / 品红占位块）",
    "SLOWMO-DEBUG" not in _all and "DEBUG-SLOWMO" not in _all and
    "D500F9" not in _all and "FF00FF" not in _all,
    "临时把动画放慢 / 贴状态浮层，交付前必须清干净",
)

# ⑥ 环境前提：模拟器/真机的动画时长必须开着，否则所有验证都是假的
#    翻车史：模拟器 animator_duration_scale = 0 ⇒ MainActivity 判定为「减少动态效果」⇒
#    所有飞行动画走 tween(120) 降级分支，560ms 的归位根本拍不到，
#    连续几次抓帧全是「已经落位」的同一张图，差点把好的实现误判成坏的。
try:
    import subprocess
    _adb = r"C:\Users\GuanXingRen\AppData\Local\Android\Sdk\platform-tools\adb.exe"
    _r = subprocess.run([_adb, "shell", "settings", "get", "global", "animator_duration_scale"],
                        capture_output=True, text=True, timeout=8)
    _scale = _r.stdout.strip()
    if _scale in ("0", "0.0"):
        check("模拟器动画时长已开启（否则拍摄到的动画全是降级分支）", False,
              "adb shell settings put global animator_duration_scale 1")
    else:
        check("模拟器动画时长已开启（否则拍摄到的动画全是降级分支）", True)
except Exception as e:
    check("模拟器动画时长检查（无设备时跳过）", True, str(e)[:40])

failed = 0
sys.stdout.write("\n=== 书架自检 ===\n")
for ok, name, detail in results:
    if isinstance(detail, (list, tuple)):
        detail = " / ".join(str(x) for x in detail)
    sys.stdout.write("%s  %s%s\n" % ("PASS" if ok else "FAIL", name, ("  <- " + detail) if detail and not ok else ""))
    if not ok:
        failed += 1
sys.stdout.write("\n%d 项通过，%d 项失败\n" % (len(results) - failed, failed))
sys.exit(1 if failed else 0)
