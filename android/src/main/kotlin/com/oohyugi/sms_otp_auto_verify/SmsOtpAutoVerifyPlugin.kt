package com.oohyugi.sms_otp_auto_verify

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentSender
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Log
import com.google.android.gms.auth.api.credentials.Credential
import com.google.android.gms.auth.api.credentials.Credentials
import com.google.android.gms.auth.api.credentials.HintRequest
import com.google.android.gms.auth.api.phone.SmsRetriever
import com.google.android.gms.auth.api.phone.SmsRetrieverClient
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.PluginRegistry

/** SmsOtpAutoVerifyPlugin */
class SmsOtpAutoVerifyPlugin : FlutterPlugin, MethodChannel.MethodCallHandler,
    MySmsListener, ActivityAware {

    private var channel: MethodChannel? = null
    private var pendingResult: MethodChannel.Result? = null
    private var receiver: SmsBroadcastReceiver? = null
    private var alreadyCalledSmsRetrieve = false
    private var client: SmsRetrieverClient? = null
    private var activity: Activity? = null
    private var binding: ActivityPluginBinding? = null

    private val activityResultListener =
        PluginRegistry.ActivityResultListener { requestCode, resultCode, data ->
            if (requestCode == REQUEST_RESOLVE_HINT) {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    val credential: Credential? = data.getParcelableExtra(Credential.EXTRA_KEY)
                    val phoneNumber: String? = credential?.id
                    pendingResult?.success(phoneNumber)
                } else {
                    pendingResult?.success(null)
                }
                return@ActivityResultListener true
            }
            false
        }

    companion object {
        private const val CHANNEL_NAME = "sms_otp_auto_verify"
        private const val REQUEST_RESOLVE_HINT = 1256

        @JvmStatic
        fun setup(plugin: SmsOtpAutoVerifyPlugin, messenger: BinaryMessenger) {
            plugin.channel = MethodChannel(messenger, CHANNEL_NAME)
            plugin.channel?.setMethodCallHandler(plugin)
            plugin.binding?.addActivityResultListener(plugin.activityResultListener)
        }
    }

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        setup(this, binding.binaryMessenger)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        unregister()
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "getAppSignature" -> {
                activity?.let {
                    val signature = AppSignatureHelper(it).getAppSignatures().firstOrNull()
                    result.success(signature)
                } ?: result.success(null)
            }

            "startListening" -> {
                pendingResult = result
                receiver = SmsBroadcastReceiver()
                startListening()
            }

            "stopListening" -> {
                pendingResult = null
                unregister()
                result.success(null)
            }

            "requestPhoneHint" -> {
                pendingResult = result
                requestHint()
            }

            else -> result.notImplemented()
        }
    }

    private fun requestHint() {
        if (!isSimSupport()) {
            pendingResult?.success(null)
            return
        }

        val hintRequest = HintRequest.Builder()
            .setPhoneNumberIdentifierSupported(true)
            .build()

        activity?.let {
            val intent = Credentials.getClient(it).getHintPickerIntent(hintRequest)
            try {
                it.startIntentSenderForResult(
                    intent.intentSender,
                    REQUEST_RESOLVE_HINT, null, 0, 0, 0
                )
            } catch (e: IntentSender.SendIntentException) {
                e.printStackTrace()
            }
        }
    }

    private fun isSimSupport(): Boolean {
        val telephonyManager =
            activity?.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        return telephonyManager?.simState != TelephonyManager.SIM_STATE_ABSENT
    }

    private fun startListening() {
        activity?.let { act ->
            client = SmsRetriever.getClient(act)
            val task = client?.startSmsRetriever()
            task?.addOnSuccessListener {
                unregister()
                receiver = SmsBroadcastReceiver()
                receiver?.setSmsListener(this)
    
                val filter = IntentFilter(SmsRetriever.SMS_RETRIEVED_ACTION)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    act.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
                } else {
                    act.registerReceiver(receiver, filter)
                }
                Log.d("SmsOtpAutoVerify", "SMS Retriever started successfully")
            }
        }
    }



    private fun unregister() {
        alreadyCalledSmsRetrieve = false
        receiver?.let {
            try {
                activity?.unregisterReceiver(it)
                Log.d("SmsOtpAutoVerify", "SMS Retriever stopped")
            } catch (e: Exception) {
                Log.e("SmsOtpAutoVerify", "Error unregistering receiver: ${e.message}")
            } finally {
                receiver = null
            }
        }
    }

    override fun onOtpReceived(message: String?) {
        if (!alreadyCalledSmsRetrieve && message != null) {
            pendingResult?.success(message)
            alreadyCalledSmsRetrieve = true
        }
    }

    override fun onOtpTimeout() {}

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
        this.binding = binding
        binding.addActivityResultListener(activityResultListener)
    }

    override fun onDetachedFromActivityForConfigChanges() = unregister()

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activity = binding.activity
        this.binding = binding
        binding.addActivityResultListener(activityResultListener)
    }

    override fun onDetachedFromActivity() = unregister()
}
