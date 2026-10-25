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
            val dir=File(getExternalFilesDir(null),"motion-resource").apply{mkdirs()}
            File(dir,"shadow-kernel.json").writeText(JSONObject().put("cases",rows).toString(2))
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
}
