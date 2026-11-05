package com.example.ui.perf

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import com.example.ui.components.*
import com.example.ui.theme.MyApplicationTheme
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Debug-only real-window timing probe. Optional stalls model missed frames, never product behavior. */
class PaperMotionProbeActivity : ComponentActivity() {
    private val handler=Handler(Looper.getMainLooper())
    private var open by mutableStateOf(false)
    private var life:PaperLifecycle?=null
    private var close:(()->Unit)?=null
    private var source:PullOutSource?=null
    private var dialog:Window?=null
    private var phase="idle"
    private var started=0L
    private var openingMs=0L
    private var cycle=0
    private var finished=false
    private var rootRecords=0
    private val cycles=JSONArray()
    private var samples=JSONArray()
    private val blockMs get()=intent.getIntExtra("blockMs",0).coerceIn(0,100)
    private val variant get()=intent.getStringExtra("variant") ?: "optimized"
    private val radiusMethod by lazy { GlassWindowBlur::class.java.declaredMethods.firstOrNull { it.name.startsWith("radius") && it.parameterCount==1 } }
    private fun radius():Float {
        radiusMethod?.let { return (it.invoke(GlassWindowBlur,window.decorView) as Number).toFloat() }
        val field=GlassWindowBlur::class.java.getDeclaredField("owners").apply { isAccessible=true }
        val map=field.get(null) as java.util.WeakHashMap<*,*>
        return ((map[window.decorView] as? Map<*,*>)?.values?.lastOrNull() as? Number)?.toFloat() ?: 0f
    }
    private val frameBlocker=object:Choreographer.FrameCallback {
        override fun doFrame(time:Long) {
            if(finished || isDestroyed) return
            if(phase!="idle" && blockMs>0) SystemClock.sleep(blockMs.toLong())
            Choreographer.getInstance().postFrameCallback(this)
        }
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MyApplicationTheme(darkTheme=false) {
            CompositionLocalProvider(LocalRenderQuality provides RenderQuality.HIGH) {
                val trigger=rememberPaperTrigger()
                SideEffect { source=trigger }
                Box(Modifier.fillMaxSize().background(Color.White)) {
                    Canvas(Modifier.fillMaxSize()) {
                        rootRecords++
                        for(y in 0..80) for(x in 0..40) drawRect(if((x+y)%2==0) Color(0xffe1ece5) else Color(0xff294938),Offset(x*24f,y*24f),Size(24f,24f))
                    }
                    Column(Modifier.fillMaxWidth().padding(24.dp)) {
                        Spacer(Modifier.height(140.dp))
                        Text("Paper motion reference",color=Color.Black)
                        Box(Modifier.size(160.dp,48.dp).pullOutAnchor(trigger).background(MaterialTheme.colorScheme.surface)) { Text("Open panel") }
                    }
                }
                if(open) AcrylicDialog(onDismissRequest={
                    val closeMs=SystemClock.uptimeMillis()-started
                    cycles.put(JSONObject().put("cycle",cycle).put("openMs",openingMs).put("closeMs",closeMs).put("samples",samples))
                    phase="idle";life=null;close=null;dialog=null
                    open=false
                    if(++cycle<4) handler.postDelayed(::begin,400) else finishReport()
                },title={
                    val owner=LocalPaperOwner.current;val closer=LocalOverlayClose.current
                    val view=LocalView.current
                    SideEffect { life=owner;close={closer {}};dialog=(view.parent as? DialogWindowProvider)?.window }
                    Text("Paper motion")
                },text={ Column { repeat(5) { index -> GlassCard(Modifier.fillMaxWidth()) { Text("Live glass $index");Text("Stable text and unchanged paper geometry") } } } })
            }
        } }
        Choreographer.getInstance().postFrameCallback(frameBlocker)
        handler.postDelayed(::begin,1800)
    }
    private fun begin() {
        val origin=source
        if(origin==null || origin.bounds.isEmpty) { handler.postDelayed(::begin,50);return }
        PullOutSources.activate(origin)
        samples=JSONArray();started=SystemClock.uptimeMillis();phase="opening";open=true
        handler.postDelayed(::sample,16)
    }
    private fun sample() {
        if(finished || isDestroyed || phase=="idle") return
        samples.put(JSONObject().put("phase",phase).put("ms",SystemClock.uptimeMillis()-started)
            .put("progress",life?.progress ?: 0f).put("blurPx",radius()).put("dim",dialog?.attributes?.dimAmount ?: 0f))
        if(phase=="opening" && life?.flat==true) {
            openingMs=SystemClock.uptimeMillis()-started
            phase="idle"
            handler.postDelayed({ started=SystemClock.uptimeMillis();phase="closing";close?.invoke();handler.postDelayed(::sample,16) },250)
        } else handler.postDelayed(::sample,16)
    }
    private fun finishReport() {
        finished=true
        val result=JSONObject().put("variant",variant).put("blockMs",blockMs).put("cycles",cycles).put("rootRecords",rootRecords)
        File(filesDir,"paper-motion-$variant-$blockMs.json").writeText(result.toString(2))
    }
    override fun onDestroy() {
        finished=true;handler.removeCallbacksAndMessages(null);Choreographer.getInstance().removeFrameCallback(frameBlocker)
        super.onDestroy()
    }
}
