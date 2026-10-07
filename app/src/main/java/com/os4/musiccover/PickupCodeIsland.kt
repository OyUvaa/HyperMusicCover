package com.os4.musiccover

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.assist.AssistStructure
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Icon
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import org.json.JSONObject

/**
 * A 微信 order page's pickup code, as a focus island - ColorOS's 取餐码 (Gleaner's observeagent),
 * the part of it that needs no background display: the code is read while the page is open.
 *
 * ColorOS reads the page with ViewExtract, its own framework code inside 微信's process. Here it
 * is the assist structure, asked for from SystemUI (which holds GET_TOP_ACTIVITY_INFO) by task:
 * IActivityTaskManager.requestAssistDataForTask. A mini program is an XWeb WebView, and its DOM
 * text comes back in the structure - 755 and 823 nodes on two real order pages, 5-17 ms - with
 * nothing hooked in 微信 and no accessibility service. [PickupParse] finds the code in it.
 *
 * Only the mini programs [BRANDS] names are read, by the task's own label (the mini program's
 * name, 「霸王茶姬」); ColorOS's list is cloud config by appId, which SystemUI is not handed. While
 * one is in front it is read a second after it arrives and every [EVERY] ms after, as the
 * order list and the order are pages of one task and moving between them changes nothing
 * SystemUI hears. A code found is shown whatever the order's state; the island goes [LIFE] after
 * the code was last seen, or when swiped away (that code is then not shown again for [LIFE]).
 *
 * A new code floats the island open, and so does the order turning ready to collect ([READY]);
 * anything else about a code already up only updates it. The island's being up is asked of the
 * system rather than remembered, since the notification's own timeout takes it away unannounced.
 */
internal object PickupCodeIsland {

    private const val TAG = "MCPickup: "
    private const val SYSUI = "com.android.systemui"
    private const val WECHAT = "com.tencent.mm"
    private const val MINI = "com.tencent.mm.plugin.appbrand.ui.AppBrandUI"
    private const val TOP_OBSERVER = "com.miui.systemui.functions.MiuiTopActivityObserver"

    private const val ID = 1241
    private const val CHANNEL = "mc_pickup"
    private const val PIC = "miui.focus.pic_mc_pickup"
    private const val ACTION_OPEN = "com.os4.musiccover.PICKUP_OPEN"
    private const val ACTION_GONE = "com.os4.musiccover.PICKUP_GONE"

    private const val FIRST = 1_000L
    /** Reads after the first that come quickly, a page just brought back may still be filling. */
    private const val QUICK = 2
    private const val QUICK_GAP = 1_000L
    private const val RETRIES = 10
    private const val RETRY_GAP = 300L
    private const val EVERY = 5_000L
    /** Reads of one stay in front, at most: an hour at [EVERY]. */
    private const val MAX_READS = 720
    private const val LIFE = 30 * 60_000L
    private const val TIMEOUT = 3_000L

    /**
     * The order states worth floating the island open for - the food can be collected. [PickupParse]
     * keeps the order's wording in its own list; this is what is done with a state, not reading it.
     * 已完成 is in: some pages call the ready state that (蜜雪冰城's read 「订单已完成」).
     */
    private val READY = setOf(
        "待取餐", "请取餐", "可取餐", "已出餐", "已准备完毕", "制作完成", "已完成",
    )

    /** The mini programs read, matched in the task label. */
    private val BRANDS = listOf(
        // ColorOS's own (Gleaner, assets/observeAgent/applet-wechat-observe-config.json, v5) ...
        "麦当劳", "肯德基", "瑞幸", "奈雪", "霸王茶姬", "CoCo", "茶百道", "古茗", "沪上阿姨", "书亦",
        "益禾堂", "1点点", "幸运咖", "挪瓦", "NOWWA", "库迪", "喜茶", "甜啦啦", "M Stand", "Manner",
        "太平洋咖啡", "星巴克", "去茶山", "kuddo", "阿嬷手作", "peet", "混果汁", "konomi", "丘大叔", "华莱士",
        // ... and more of the same kind.
        "luckin", "蜜雪冰城", "一点点", "都可", "七分甜", "柠季", "茶颜悦色", "Tims", "汉堡王", "塔斯汀",
        "必胜客", "德克士", "老乡鸡", "袁记",
    )

    /** The settings switch, kept with Main's state. On unless turned off. */
    @JvmField var sOn = true

    private val bg: Handler by lazy { Handler(HandlerThread("mc-pickup").apply { start() }.looper) }

    // All below on [bg].
    private var lastTop: ComponentName? = null
    private var taskId = -1
    private var brand = ""
    private var reads = 0
    private var gen = 0
    private var shownKey: String? = null
    /** The state [shownKey] was posted with, to tell 制作中 → 待取餐 from a re-read of the same. */
    private var shownStatus: String? = null
    private var shownTask = -1
    private var muted: String? = null
    private var mutedUntil = 0L
    private var receivers = false
    private var lastRead = ""
    /** The front page's first texts when no code was found, for the probe only (not logged). */
    private var lastHead = ""
    private var iconSaid = false

    fun install(cl: ClassLoader) {
        runCatching {
            Xp.hookAll(Xp.findClass(TOP_OBSERVER, cl), "updateTopActivity") { chain ->
                val out = chain.proceed()
                runCatching {
                    val state = Xp.getObjectField(chain.thisObject, "mState")
                    val top = Xp.getObjectField(state, "topActivity") as? ComponentName
                    bg.post { front(top) }
                }
                out
            }
            Xp.log(TAG + "watching the front activity")
        }.onFailure { Xp.log(TAG + "front activity not watched: $it") }
    }

    fun setOn(on: Boolean) {
        sOn = on
        bg.post { if (!on) { stop(); takeDown("switched off") } }
    }

    fun describe(): String = "on=$sOn task=$taskId brand=$brand reads=$reads shown=${shownKey != null}" +
        " up=${Main.appContext()?.let { up(it) }}" +
        " last=$lastRead" + if (lastHead.isEmpty()) "" else " head=[$lastHead]"

    /** The probe's `do=read`: one read now, of whatever is tracked. */
    fun readNow() = bg.post { if (taskId >= 0) read(taskId, gen) }

    /**
     * [top] is MiuiTopActivityObserver's, which can be ahead of the task list: the task it names
     * may not be listed yet, or not carry its label yet (微信 sets a mini program's label once
     * the mini program is up). Looked for again then, [RETRIES] times.
     */
    private fun front(top: ComponentName?, retry: Int = 0) {
        if (retry == 0) {
            if (top == lastTop) return
            lastTop = top
        } else if (top != lastTop) {
            return
        }
        if (!sOn || top == null || top.packageName != WECHAT || !top.className.startsWith(MINI)) {
            stop()
            return
        }
        val ctx = Main.appContext() ?: return
        @Suppress("DEPRECATION")
        val info = runCatching {
            ctx.getSystemService(ActivityManager::class.java).getRunningTasks(8)
                .firstOrNull { it.topActivity == top }
        }.getOrNull()
        val label = info?.taskDescription?.label.orEmpty()
        if (info == null || label.isEmpty()) {
            if (retry < RETRIES) bg.postDelayed({ front(top, retry + 1) }, RETRY_GAP)
            else Xp.log(TAG + "no labelled task for ${top.shortClassName}")
            return
        }
        val name = BRANDS.firstOrNull { label.contains(it, ignoreCase = true) }
        if (name == null) {
            Xp.d(TAG + "mini program not on the list: $label")
            stop()
            return
        }
        if (info.taskId == taskId) return
        stop()
        taskId = info.taskId
        brand = label
        reads = 0
        val g = gen
        Xp.log(TAG + "tracking task $taskId ($label)")
        bg.postDelayed({ read(taskId, g) }, FIRST)
    }

    private fun stop() {
        gen++
        taskId = -1
        bg.removeCallbacksAndMessages(null)
    }

    private fun read(task: Int, g: Int) {
        if (g != gen || task != taskId) return
        if (++reads > MAX_READS) return
        // Not while the screen is off or locked: the page is not being looked at.
        val ctx = Main.appContext() ?: return
        val power = ctx.getSystemService(android.os.PowerManager::class.java)
        val keyguard = ctx.getSystemService(android.app.KeyguardManager::class.java)
        if (power?.isInteractive == false || keyguard?.isKeyguardLocked == true) {
            next(task, g)
            return
        }
        val t0 = SystemClock.uptimeMillis()
        val timeout = Runnable { if (g == gen) { lastRead = "timeout"; next(task, g) } }
        bg.postDelayed(timeout, TIMEOUT)
        val asked = request(task) { st ->
            bg.post {
                bg.removeCallbacks(timeout)
                if (g != gen) return@post
                val nodes = if (st != null) runCatching { nodes(st) }.getOrNull() else null
                val r = nodes?.let { PickupParse.parse(it) }
                val ms = SystemClock.uptimeMillis() - t0
                lastRead = when {
                    st == null -> "no structure (${ms}ms)"
                    nodes == null -> "unreadable (${ms}ms)"
                    r == null -> "no code in ${nodes.size} texts (${ms}ms)"
                    else -> "code found in ${nodes.size} texts (${ms}ms)"
                }
                if (r != null) {
                    lastHead = ""
                    Xp.d(TAG + "$brand: ${r.code} ${r.label} ${r.status} ${r.store}")
                    show(r, task)
                } else if (nodes != null) {
                    val front = nodes.maxOfOrNull { it.page } ?: -1
                    lastHead = "page $front: " + nodes.filter { it.page == front }.take(12)
                        .joinToString("|") { it.text.take(12) }
                }
                next(task, g)
            }
        }
        if (!asked) {
            bg.removeCallbacks(timeout)
            lastRead = "not asked"
            next(task, g)
        }
    }

    private fun next(task: Int, g: Int) {
        if (g == gen && task == taskId) bg.postDelayed({ read(task, g) }, if (reads <= QUICK) QUICK_GAP else EVERY)
    }

    /**
     * IActivityTaskManager.requestAssistDataForTask(receiver, taskId, callingPackage,
     * attributionTag, fetchStructure) - Android 17's five; the appop it notes is checked against
     * SystemUI's own package. The receiver is a bare Binder: IAssistDataReceiver's transaction 1
     * is onHandleAssistData(Bundle), 2 the screenshot, both oneway.
     */
    private fun request(task: Int, done: (AssistStructure?) -> Unit): Boolean {
        val receiver = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code == 1) {
                    val st = runCatching {
                        data.enforceInterface("android.app.IAssistDataReceiver")
                        val b = if (data.readInt() != 0) Bundle.CREATOR.createFromParcel(data) else null
                        b?.classLoader = AssistStructure::class.java.classLoader
                        @Suppress("DEPRECATION")
                        b?.getParcelable<AssistStructure>("structure")
                    }.getOrNull()
                    done(st)
                    return true
                }
                if (code == 2) return true
                return super.onTransact(code, data, reply, flags)
            }
        }
        receiver.attachInterface(null, "android.app.IAssistDataReceiver")
        return runCatching {
            val atm = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)
            val recv = Class.forName("android.app.IAssistDataReceiver")
            val proxy = Class.forName("android.app.IAssistDataReceiver\$Stub")
                .getMethod("asInterface", IBinder::class.java).invoke(null, receiver)
            val m = atm.javaClass.methods.firstOrNull { it.name == "requestAssistDataForTask" }
                ?: error("no requestAssistDataForTask")
            val ok = when (m.parameterTypes.size) {
                5 -> m.invoke(atm, proxy, task, SYSUI, null, true)
                4 -> m.invoke(atm, proxy, task, SYSUI, null)
                else -> error("requestAssistDataForTask${m.parameterTypes.toList()}")
            }
            ok as? Boolean ?: true
        }.onFailure {
            Xp.log(TAG + "assist request failed: $it")
        }.getOrDefault(false)
    }

    /** The structure's texts in tree order, each with the WebView it is in. */
    private fun nodes(st: AssistStructure): List<PickupParse.Node> {
        val out = ArrayList<PickupParse.Node>()
        var pages = -1
        fun walk(n: AssistStructure.ViewNode, page: Int) {
            var p = page
            if (n.className?.endsWith("WebView") == true) p = ++pages
            n.text?.let { if (it.isNotBlank()) out.add(PickupParse.Node(it.toString(), p)) }
            for (i in 0 until n.childCount) walk(n.getChildAt(i), p)
        }
        for (i in 0 until st.windowNodeCount) walk(st.getWindowNodeAt(i).rootViewNode, -1)
        return out
    }

    // ---------------------------------------------------------------- the island

    private fun show(r: PickupParse.Result, task: Int) {
        val key = "$brand|${r.code}|${r.status}|${r.store}"
        val now = SystemClock.elapsedRealtime()
        if (muted == "$brand|${r.code}" && now < mutedUntil) return
        val ctx = Main.appContext() ?: return
        // The notification goes by itself [LIFE] after it was posted, and the island with it; nothing
        // on that path tells us (a timeout is not a dismissal, so no delete intent, and [takeDown] is
        // only for the switch). So ask the system: with the island gone, shownKey is not evidence of
        // anything, and what comes next is a fresh code as far as the island is concerned.
        if (shownKey != null && up(ctx) == false) clear()
        if (key == shownKey) return
        // A new code floats the island open; so does the order turning ready to collect. A state
        // changing again while it is already ready only updates, and so does the shop.
        val sameCode = shownKey?.startsWith("$brand|${r.code}|") == true
        val fresh = !sameCode || ready(r.status) && !ready(shownStatus)
        runCatching { post(ctx, r, task, fresh) }
            .onSuccess { shownKey = key; shownStatus = r.status; shownTask = task }
            .onFailure { Xp.log(TAG + "not posted: $it") }
    }

    private fun ready(status: String?): Boolean = status != null && READY.any { status.contains(it) }

    /** Nothing is up: forget what the island was showing. */
    private fun clear() {
        shownKey = null
        shownStatus = null
    }

    /**
     * Whether our own notification is still posted, the island going when it goes. Only this
     * process's notifications are listed, and only this one is ours ([ID], [CHANNEL]). null is the
     * system not saying - not an answer, so the caller keeps what it had; reading a failure as
     * "gone" would float the island open again on every read.
     */
    private fun up(c: Context): Boolean? = runCatching {
        c.getSystemService(NotificationManager::class.java)?.activeNotifications
            ?.any { it.id == ID && it.notification.channelId == CHANNEL }
    }.getOrNull()

    private fun takeDown(why: String) {
        if (shownKey == null) return
        clear()
        Main.appContext()?.getSystemService(NotificationManager::class.java)?.cancel(ID)
        Xp.log(TAG + "taken down: $why")
    }

    private fun receivers(ctx: Context) {
        if (receivers) return
        receivers = true
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.action) {
                    ACTION_OPEN -> bg.post { open(c) }
                    ACTION_GONE -> bg.post {
                        val k = shownKey ?: return@post
                        muted = k.split('|').take(2).joinToString("|")
                        mutedUntil = SystemClock.elapsedRealtime() + LIFE
                        clear()
                        Xp.log(TAG + "swiped away")
                    }
                }
            }
        }
        ctx.registerReceiver(r, IntentFilter().apply {
            addAction(ACTION_OPEN)
            addAction(ACTION_GONE)
        }, Context.RECEIVER_NOT_EXPORTED)
    }

    /** The mini program's task back in front; 微信 if the task is gone. */
    private fun open(c: Context) {
        val am = c.getSystemService(ActivityManager::class.java)
        val ok = shownTask >= 0 && runCatching {
            @Suppress("DEPRECATION")
            am.getRunningTasks(64).any { it.taskId == shownTask } || error("gone")
            am.moveTaskToFront(shownTask, ActivityManager.MOVE_TASK_WITH_HOME)
        }.isSuccess
        if (!ok) {
            runCatching {
                c.packageManager.getLaunchIntentForPackage(WECHAT)
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { c.startActivity(it) }
            }.onFailure { Xp.log(TAG + "微信 not opened: $it") }
        }
    }

    @android.annotation.SuppressLint("NotificationPermission")
    private fun post(c: Context, r: PickupParse.Result, task: Int, fresh: Boolean) {
        receivers(c)
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "取餐码",
                NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            })
        }
        val pic = icon(c, task)
        val title = r.status ?: r.label
        // 「麦当劳示例广场餐厅」 under 麦当劳 is 「示例广场餐厅」.
        val store = r.store?.removePrefix(brand)?.trimStart('（', '(', ' ')?.ifEmpty { null }
        val content = listOfNotNull(brand, store).joinToString(" · ")
        val island = JSONObject()
            .put("islandProperty", 1)
            .put("islandPriority", 2)
            .put("bigIslandArea", JSONObject()
                .put("imageTextInfoLeft", JSONObject()
                    .put("type", 1)
                    .put("picInfo", JSONObject().put("type", 1).put("pic", PIC))
                    .put("textInfo", JSONObject().put("title", r.code)))
                .put("imageTextInfoRight", JSONObject()
                    .put("type", 2)
                    .put("textInfo", JSONObject().put("title", title))))
            .put("smallIslandArea", JSONObject()
                .put("picInfo", JSONObject().put("type", 1).put("pic", PIC)))
        val param = JSONObject()
            .put("protocol", 1)
            .put("business", "pickup_code")
            .put("scene", "template_v2")
            .put("ticker", "${r.label} ${r.code}")
            .put("tickerPic", PIC)
            .put("aodTitle", "${r.label} ${r.code}")
            .put("aodPic", PIC)
            .put("enableFloat", fresh)
            // The island's own float, a second gate: FocusNotifPreHandler writes it as
            // miui.island.firstFloat from this key and defaults it to false when the key is missing,
            // and FocusNotificationController floats only when it is true. Left out, an update never
            // brings the island up - a new code in the same notification (id 1241 is one key) showed
            // in the shade alone. 地铁乘车码's card sets the same key the same way.
            .put("islandFirstFloat", fresh)
            .put("updatable", true)
            .put("param_island", island)
            .put("title", r.code)
            .put("content", content)
            .put("baseInfo", JSONObject()
                .put("type", 2)
                .put("title", r.code)
                .put("content", content)
                .put("subContent", title))
            .put("picInfo", JSONObject().put("type", 1).put("pic", PIC))
        val extras = Bundle()
        extras.putString("miui.focus.param", JSONObject().put("param_v2", param).toString())
        extras.putBundle("miui.focus.pics", Bundle().apply { putParcelable(PIC, Icon.createWithBitmap(pic)) })
        val tap = PendingIntent.getBroadcast(c, ID, Intent(ACTION_OPEN).setPackage(SYSUI),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val gone = PendingIntent.getBroadcast(c, ID + 1, Intent(ACTION_GONE).setPackage(SYSUI),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(c, CHANNEL)
            .setSmallIcon(Icon.createWithBitmap(pic))
            .setContentTitle("${r.label} ${r.code}")
            .setContentText(listOf(content, title).filter { it.isNotEmpty() }.joinToString(" · "))
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(!fresh)
            .setShowWhen(false)
            .setAutoCancel(false)
            .setTimeoutAfter(LIFE)
            .setDeleteIntent(gone)
            .setContentIntent(tap)
            .addExtras(extras)
            .build()
        nm.notify(ID, n)
        Xp.log(TAG + (if (fresh) "up: " else "updated: ") + brand)
    }

    /**
     * The mini program's own icon, which its task carries (TaskDescription: in memory, or the
     * file the system keeps it in); 微信's when it has none.
     */
    private fun icon(c: Context, task: Int): Bitmap {
        val fromTask = runCatching {
            @Suppress("DEPRECATION")
            val td = c.getSystemService(ActivityManager::class.java).getRunningTasks(64)
                .firstOrNull { it.taskId == task }?.taskDescription ?: return@runCatching null
            val cls = td.javaClass
            (cls.getMethod("getInMemoryIcon").invoke(td) as? Bitmap) ?: run {
                val file = cls.getMethod("getIconFilename").invoke(td) as? String ?: return@run null
                cls.getMethod("loadTaskDescriptionIcon", String::class.java, Int::class.java)
                    // UserHandle's hash is its user id.
                    .invoke(null, file, android.os.Process.myUserHandle().hashCode()) as? Bitmap
            }
        }.onFailure {
            if (!iconSaid) { iconSaid = true; Xp.log(TAG + "task icon: $it") }
        }.getOrNull()
        if (fromTask != null) return fromTask
        if (!iconSaid) { iconSaid = true; Xp.log(TAG + "task icon: none, 微信's instead") }
        val d = c.packageManager.getApplicationIcon(WECHAT)
        val size = 144
        return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also {
            d.setBounds(0, 0, size, size)
            d.draw(Canvas(it))
        }
    }
}
