package com.example.ui.perf

import android.app.Activity
import android.os.Bundle
import android.os.Debug
import android.widget.TextView
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random
import me.trishiraj.shadowglow.boxBlurVertical
import me.trishiraj.shadowglow.boxBlurHorizontal
import org.json.JSONArray
import org.json.JSONObject

/** Opt-in release probe only; never part of a normal source set. */
class ShadowKernelProbeActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(TextView(this).apply { text = "Shadow kernel CPU probe" })
        Thread {
            val rows = JSONArray()
            val rng = Random(42)
            for ((w,h,r) in listOf(Triple(32,32,2),Triple(64,64,4),Triple(128,32,4),Triple(64,128,8),
                Triple(224,32,6),Triple(256,64,12),Triple(32,1024,7),Triple(1024,32,8),
                Triple(512,96,8),Triple(1024,128,4),Triple(640,240,32))) {
                val src = IntArray(w*h+17) { if(it<w*h) rng.nextInt(256) else 0x13579bdf }
                val old = IntArray(w*h+17) { 0x13579bdf }
                val fresh = old.copyOf()
                legacy(src,old,w,h,r); boxBlurVertical(src,fresh,w,h,r)
                check(old.contentEquals(fresh))
                repeat(100) { legacy(src,old,w,h,r); boxBlurVertical(src,fresh,w,h,r) }
                val a=JSONArray();val b=JSONArray()
                fun sample(original:Boolean):Long {
                    val start=Debug.threadCpuTimeNanos()
                    repeat(100) { if(original) legacy(src,old,w,h,r) else boxBlurVertical(src,fresh,w,h,r) }
                    return Debug.threadCpuTimeNanos()-start
                }
                repeat(10) { i ->
                    if(i%2==0) { a.put(sample(true));b.put(sample(false)) }
                    else { val n=sample(false);a.put(sample(true));b.put(n) }
                }
                rows.put(JSONObject().put("width",w).put("height",h).put("radius",r)
                    .put("legacyNs",a).put("rowMajorNs",b).put("passesPerSample",100).put("bitExact",true))
            }
            val horizontalRows = JSONArray()
            val horizontalCases = listOf(Triple(32,32,2),Triple(64,64,4),Triple(128,32,4),Triple(64,128,8),
                Triple(224,32,6),Triple(256,64,12),Triple(32,1024,7),Triple(1024,32,8),
                Triple(512,96,8),Triple(1024,128,4),Triple(640,240,32),
                Triple(1,1,256),Triple(1,97,128),Triple(127,1,256),Triple(2,3,31),
                Triple(3,2,128),Triple(7,9,0),Triple(9,7,7),Triple(32,1024,256))
            for ((w,h,r) in horizontalCases) {
                val n = w * h
                val src = IntArray(n+17) { if(it<n) rng.nextInt(256) else 0x13579bdf }
                val old = IntArray(n+17) { 0x13579bdf }
                val fresh = old.copyOf()
                legacyHorizontal(src,old,w,h,r); boxBlurHorizontal(src,fresh,w,h,r)
                check(old.contentEquals(fresh)) { "Horizontal pixel/tail mismatch: $w x $h radius $r" }
                repeat(100) { legacyHorizontal(src,old,w,h,r); boxBlurHorizontal(src,fresh,w,h,r) }
                val passes = (500_000 / n).coerceIn(100, 512)
                val a = JSONArray(); val b = JSONArray()
                fun horizontalSample(original: Boolean): Long {
                    val start = Debug.threadCpuTimeNanos()
                    repeat(passes) {
                        if(original) legacyHorizontal(src,old,w,h,r) else boxBlurHorizontal(src,fresh,w,h,r)
                    }
                    return Debug.threadCpuTimeNanos() - start
                }
                repeat(10) { pair ->
                    if(pair % 2 == 0) { a.put(horizontalSample(true)); b.put(horizontalSample(false)) }
                    else { val next=horizontalSample(false); a.put(horizontalSample(true)); b.put(next) }
                    check(old.contentEquals(fresh)) { "Horizontal output changed during timing: $w x $h radius $r" }
                }
                horizontalRows.put(JSONObject().put("width",w).put("height",h).put("radius",r)
                    .put("legacyNs",a).put("segmentedNs",b).put("passesPerSample",passes)
                    .put("bitExact",true).put("tailLength",17))
            }
            val dir=File(getExternalFilesDir(null),"motion-resource").apply{mkdirs()}
            File(dir,"shadow-kernel.json").writeText(JSONObject().put("cases",rows).put("horizontalCases",horizontalRows)
                .put("horizontalReferenceCommit","10d1f206404ac49cc77bc7867baf1fefed22dfa2").toString(2))
            runOnUiThread { (findViewById<android.view.View>(android.R.id.content) as android.view.ViewGroup).let { (it.getChildAt(0) as TextView).text="Complete" } }
        }.start()
    }
    // Frozen original vertical pass, including its Float multiply/add/truncate order.
    private fun legacy(src:IntArray,dst:IntArray,w:Int,h:Int,r:Int) {
        val inv=1f/(2*r+1)
        for(x in 0 until w) {
            var sum=0
            for(i in -r..r) sum+=src[i.coerceIn(0,h-1)*w+x]
            for(y in 0 until h) {
                dst[y*w+x]=(sum*inv+.5f).toInt()
                sum+=src[min(y+r+1,h-1)*w+x]-src[max(y-r,0)*w+x]
            }
        }
    }
    // Frozen horizontal pass from 10d1f206; integer updates and Float order unchanged.
    private fun legacyHorizontal(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = 2 * r + 1
        val inv = 1f / div
        val last = w - 1
        for (y in 0 until h) {
            val base = y * w
            // 右端：x + r + 1 ≥ w 后统一取 last；左端：x - r < 0 期间取 0。
            // 用「越界前 / 全越界后」两段循环替代逐像素 coerceIn，数学结果完全一致。
            var sum = 0
            for (i in -r..r) {
                sum += src[base + if (i < 0) 0 else if (i > last) last else i]
            }
            for (x in 0 until w) {
                dst[base + x] = ((sum * inv) + 0.5f).toInt()
                val addIdx = x + r + 1
                val subIdx = x - r
                sum += (if (addIdx > last) src[base + last] else src[base + addIdx]) -
                    (if (subIdx < 0) src[base] else src[base + subIdx])
            }
        }
    }
}
