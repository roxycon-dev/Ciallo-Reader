package com.example.transfer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

class TransferService:Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val notificationId=7301
    override fun onCreate() {
        super.onCreate()
        val notifications=getSystemService(NotificationManager::class.java)
        if(Build.VERSION.SDK_INT>=26) notifications.createNotificationChannel(NotificationChannel("device_transfer","设备互传",NotificationManager.IMPORTANCE_LOW))
        val manager=TransferManager.get(this)
        fun notification(state:TransferSessionState):android.app.Notification {
            val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java).putExtra("open_transfer",true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val cancel=PendingIntent.getService(this,1,Intent(this,TransferService::class.java).setAction("cancel"),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            return NotificationCompat.Builder(this,"device_transfer").setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle(state.phase.label).setContentText(state.message.ifBlank { "数据正在设备之间安全传输" })
                .setContentIntent(open).setOnlyAlertOnce(true).setOngoing(state.busy)
                .setProgress(100,(state.progress.fraction*100).toInt(),state.progress.totalBytes==0L)
                .apply { if(state.phase!=TransferPhase.IMPORTING)addAction(android.R.drawable.ic_menu_close_clear_cancel,"取消",cancel) }.build()
        }
        ServiceCompat.startForeground(this,notificationId,notification(manager.state.value),if(Build.VERSION.SDK_INT>=29)ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        scope.launch { manager.state.collectLatest { state ->
            notifications.notify(notificationId,notification(state))
            if(state.phase in setOf(TransferPhase.COMPLETED,TransferPhase.CANCELLED,TransferPhase.FAILED,TransferPhase.WAITING_TO_RESUME)) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        } }
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action=="cancel") { TransferManager.get(this).cancel(); stopSelf() }
        return START_NOT_STICKY
    }
    override fun onTimeout(startId:Int,fgsType:Int) { TransferManager.get(this).cancel(); stopSelf() }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    override fun onBind(intent:Intent?):IBinder?=null
}
