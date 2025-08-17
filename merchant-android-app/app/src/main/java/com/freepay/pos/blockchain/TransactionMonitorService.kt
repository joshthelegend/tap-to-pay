package com.freepay.pos.blockchain

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.freepay.pos.MainActivity
import com.freepay.pos.R
import kotlinx.coroutines.*
import java.math.BigDecimal

class TransactionMonitorService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var blockchainService: BlockchainService
    private var monitoringJob: Job? = null
    
    companion object {
        private const val TAG = "TransactionMonitor"
        private const val CHANNEL_ID = "transaction_monitor"
        private const val NOTIFICATION_ID = 1
        private const val CHECK_INTERVAL_MS = 1000L
        private const val MAX_CHECKS = 100 // ~5 minutes
    }
    
    override fun onCreate() {
        super.onCreate()
        blockchainService = BlockchainService(this)
        createNotificationChannel()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val chainId = intent?.getIntExtra("chainId", 0) ?: 0
        val amountWei = intent?.getStringExtra("amountWei")
        val tokenAddress = intent?.getStringExtra("tokenAddress")
        val displayAmount = intent?.getStringExtra("displayAmount")
        
        if (chainId != 0 && amountWei != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, createNotification("Monitoring payment..."), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, createNotification("Monitoring payment..."))
            }
            monitorPayment(chainId, amountWei, tokenAddress, displayAmount)
        }
        
        return START_NOT_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        super.onDestroy()
        monitoringJob?.cancel()
        serviceScope.cancel()
    }
    
    private fun monitorPayment(chainId: Int, amountWei: String, tokenAddress: String?, displayAmount: String?) {
        monitoringJob?.cancel()
        monitoringJob = serviceScope.launch {
            try {
                Log.d(TAG, "🔍 Starting payment monitoring")
                Log.d(TAG, "Chain ID: $chainId")
                Log.d(TAG, "Amount Wei: $amountWei")
                Log.d(TAG, "Token Address: ${tokenAddress ?: "Native token"}")
                
                var paymentReceived = false
                
                val job = blockchainService.monitorIncomingTransfers(
                    chainId = chainId,
                    expectedAmountWei = amountWei,
                    expectedTokenAddress = tokenAddress
                ) { transfer ->
                    Log.d(TAG, "✅ Payment received! Transaction: ${transfer.hash}")
                    paymentReceived = true
                    
                    // Run on main thread to ensure proper broadcast delivery
                    runBlocking {
                        launch(Dispatchers.Main) {
                            // Send broadcast and show notification
                            broadcastPaymentSuccess(transfer.hash)
                            showSuccessNotification(displayAmount, transfer.hash)
                            
                            // Also try to start MainActivity with success flag
                            val activityIntent = Intent(this@TransactionMonitorService, MainActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                putExtra("payment_success", true)
                                putExtra("txHash", transfer.hash)
                            }
                            startActivity(activityIntent)
                        }
                    }
                    
                    // Schedule service stop after a delay
                    serviceScope.launch {
                        delay(1000) // Give more time for broadcast/activity
                        stopSelf()
                    }
                }
                
                // Timeout after 5 minutes
                delay(MAX_CHECKS * CHECK_INTERVAL_MS)
                
                if (!paymentReceived && isActive) {
                    job.cancel()
                    showTimeoutNotification()
                    stopSelf()
                }
            } catch (e: CancellationException) {
                // This is expected when payment is found
                if (e.message?.contains("Payment received") != true) {
                    Log.d(TAG, "Monitoring cancelled")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error monitoring payment", e)
                showFailureNotification()
                stopSelf()
            }
        }
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Transaction Monitor",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Monitors blockchain transactions"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FreePay Transaction")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
    }
    
    private fun updateNotification(text: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, createNotification(text))
    }
    
    private fun showSuccessNotification(amount: String?, txHash: String) {
        val text = if (amount != null) {
            "Payment of $amount completed successfully!"
        } else {
            "Payment completed successfully!"
        }
        
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Payment Successful")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_success)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID + 1, notification)
    }
    
    private fun broadcastPaymentSuccess(txHash: String) {
        Log.d(TAG, "📢 Sending payment success broadcast with txHash: $txHash")
        val intent = Intent("com.freepay.pos.PAYMENT_SUCCESS")
        intent.putExtra("txHash", txHash)
        intent.setPackage(packageName) // Ensure it's sent to our app only
        sendBroadcast(intent)
        Log.d(TAG, "📢 Broadcast sent")
    }
    
    private fun showFailureNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Payment Failed")
            .setContentText("The transaction was not successful")
            .setSmallIcon(R.drawable.ic_error)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID + 1, notification)
    }
    
    private fun showTimeoutNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Transaction Timeout")
            .setContentText("Unable to confirm transaction status")
            .setSmallIcon(R.drawable.ic_warning)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID + 1, notification)
    }
}