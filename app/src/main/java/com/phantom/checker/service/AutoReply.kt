package com.phantom.checker.service

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 自動回覆分兩種：
 * 1. SMS：完全可行，直接回傳簡訊（時段控制）
 * 2. LINE/IG 等通知：不可代送（會已讀），改用「通知快速回覆 RemoteInput」
 *    → 見 QuickReplyHelper
 */
object AutoReplyConfig {
    var enabled = false
    var startMinute = 22 * 60   // 22:00 起
    var endMinute = 8 * 60      // 08:00 止
    var smsTemplate = "目前在忙，稍後回覆你 🙂"
}

class SmsAutoReplyReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (!AutoReplyConfig.enabled) return
        val now = java.util.Calendar.getInstance()
        val mins = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE)
        val inWindow = if (AutoReplyConfig.startMinute <= AutoReplyConfig.endMinute)
            mins in AutoReplyConfig.startMinute..AutoReplyConfig.endMinute
        else mins >= AutoReplyConfig.startMinute || mins <= AutoReplyConfig.endMinute
        if (!inWindow) return

        val msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val sender = msgs.firstOrNull()?.displayOriginatingAddress ?: return
        val body = msgs.joinToString("") { it.displayMessageBody ?: "" }

        CoroutineScope(Dispatchers.IO).launch {
            val db = AppDatabaseHolder.db(ctx)
            db.messageDao().insert(
                com.phantom.checker.data.MessageEntity(
                    packageName = "sms", appLabel = "簡訊",
                    sender = sender, content = body,
                    timestamp = System.currentTimeMillis()
                )
            )
            android.telephony.SmsManager.getDefault().sendTextMessage(
                sender, null, AutoReplyConfig.smsTemplate, null, null
            )
        }
    }
}

/** 讓使用者在原通知上快速回覆（不開啟原 App、不觸發已讀的替代方案） */
object QuickReplyHelper {
    fun canReply(sbn: Notification): Boolean =
        sbn.actions.any { it.remoteInputs?.isNotEmpty() == true }

    fun buildQuickReplyAction(ctx: Context, sbn: Notification): Notification.Action? =
        sbn.actions.firstOrNull { it.remoteInputs?.isNotEmpty() == true }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        // TODO: 開機後提示使用者確認通知權限仍在（Android 13+ 會重置）
    }
}
