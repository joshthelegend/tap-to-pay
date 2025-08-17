package com.freepay.pos

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.freepay.pos.blockchain.BlockchainService
import com.freepay.pos.blockchain.TransactionMonitorService
import com.freepay.pos.databinding.ActivityMainBinding
import com.freepay.pos.nfc.NFCPaymentHandler
import com.freepay.pos.utils.CurrencyFormatter
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import java.math.BigDecimal

class MainActivity : ComponentActivity() {
    private lateinit var binding: ActivityMainBinding
    private var nfcAdapter: NfcAdapter? = null
    private lateinit var nfcPaymentHandler: NFCPaymentHandler
    private lateinit var blockchainService: BlockchainService
    
    private var currentAmountCents = 0L // Store amount in cents
    private var isProcessingPayment = false
    
    private val paymentSuccessReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val txHash = intent?.getStringExtra("txHash")
            Log.d(TAG, "Payment success received! Transaction: $txHash")
            runOnUiThread {
                showPaymentApproved()
            }
        }
    }
    
    companion object {
        private const val TAG = "FreePayPOS"
        private const val MIN_AMOUNT = 0.01
        private const val MAX_AMOUNT = 10000.0
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Keep screen on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Initialize services
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        nfcPaymentHandler = NFCPaymentHandler(this)
        blockchainService = BlockchainService(this)
        
        // Check NFC availability
        if (nfcAdapter == null) {
            showError("NFC is not available on this device")
            finish()
            return
        }
        
        if (!nfcAdapter!!.isEnabled) {
            showError("Please enable NFC in settings")
        }
        
        setupUI()
        
        // Debug charge button
        Log.d(TAG, "Charge button found: ${binding.btnCharge != null}")
        Log.d(TAG, "Charge button visibility: ${binding.btnCharge.visibility}")
        Log.d(TAG, "Charge button enabled: ${binding.btnCharge.isEnabled}")
        
        // Start transaction monitoring service
        try {
            startService(Intent(this, TransactionMonitorService::class.java))
        } catch (e: Exception) {
            Log.e(TAG, "Error starting transaction monitor service", e)
        }
    }
    
    private fun setupUI() {
        // Amount display
        updateAmountDisplay()
        
        // Number pad listeners
        binding.apply {
            btn0.setOnClickListener { appendDigit("0") }
            btn1.setOnClickListener { appendDigit("1") }
            btn2.setOnClickListener { appendDigit("2") }
            btn3.setOnClickListener { appendDigit("3") }
            btn4.setOnClickListener { appendDigit("4") }
            btn5.setOnClickListener { appendDigit("5") }
            btn6.setOnClickListener { appendDigit("6") }
            btn7.setOnClickListener { appendDigit("7") }
            btn8.setOnClickListener { appendDigit("8") }
            btn9.setOnClickListener { appendDigit("9") }
            // btnDot.setOnClickListener { appendDot() } // Decimal functionality hidden for now
            btnClear.setOnClickListener { clearAmount() }
            btnCharge.setOnClickListener { startPayment() }
            btnSettings.setOnClickListener { showSettings() }
            btnCancel.setOnClickListener { cancelPayment() }
            
            // Quick amount buttons removed - calculator style only
        }
    }
    
    private fun appendDigit(digit: String) {
        if (isProcessingPayment) return
        
        // Append digit to cents
        val newAmountCents = currentAmountCents * 10 + digit.toLong()
        
        // Limit to max amount in cents ($10,000 = 1,000,000 cents)
        if (newAmountCents <= MAX_AMOUNT * 100) {
            currentAmountCents = newAmountCents
            updateAmountDisplay()
        }
    }
    
    private fun appendDot() {
        if (isProcessingPayment) return
        
        // Decimal functionality is not used in cents-based input
        // This function is kept for potential future use
    }
    
    private fun showSettings() {
        // TODO: Implement settings screen
        Toast.makeText(this, "Settings coming soon", Toast.LENGTH_SHORT).show()
    }
    
    private fun cancelPayment() {
        if (isProcessingPayment) {
            resetPaymentUI()
            showError("Payment cancelled")
        }
    }
    
    private fun clearAmount() {
        if (isProcessingPayment) return
        
        currentAmountCents = 0L
        updateAmountDisplay()
        resetPaymentUI()
    }
    
    private fun setAmount(amount: BigDecimal) {
        if (isProcessingPayment) return
        
        currentAmountCents = (amount.multiply(BigDecimal(100))).toLong()
        updateAmountDisplay()
    }
    
    private fun updateAmountDisplay() {
        val amountInDollars = BigDecimal(currentAmountCents).divide(BigDecimal(100))
        binding.amountDisplay.text = CurrencyFormatter.format(amountInDollars)
        binding.btnCharge.isEnabled = currentAmountCents >= (MIN_AMOUNT * 100).toLong()
    }
    
    private fun startPayment() {
        if (currentAmountCents < (MIN_AMOUNT * 100).toLong()) {
            showError("Amount must be at least ${CurrencyFormatter.format(BigDecimal(MIN_AMOUNT))}")
            return
        }
        
        isProcessingPayment = true
        binding.apply {
            paymentOverlay.visibility = View.VISIBLE
            statusText.text = "Tap customer's phone to begin payment"
            btnCharge.isEnabled = false
        }
        
        // Enable NFC reader mode
        enableNFCReaderMode()
    }
    
    private fun resetPaymentUI() {
        isProcessingPayment = false
        binding.apply {
            paymentOverlay.visibility = View.GONE
            btnCharge.isEnabled = currentAmountCents >= (MIN_AMOUNT * 100).toLong()
        }
        // Disable reader mode when payment is cancelled/completed
        disableNFCReaderMode()
    }
    
    override fun onResume() {
        super.onResume()
        if (isProcessingPayment) {
            enableNFCReaderMode()
        }
        // Register payment success receiver
        val filter = IntentFilter("com.freepay.pos.PAYMENT_SUCCESS")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(paymentSuccessReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(paymentSuccessReceiver, filter)
        }
    }
    
    override fun onPause() {
        super.onPause()
        disableNFCReaderMode()
        // Unregister receiver
        try {
            unregisterReceiver(paymentSuccessReceiver)
        } catch (e: Exception) {
            // Receiver might not be registered
        }
    }
    
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // Check if this is a payment success intent
        if (intent?.getBooleanExtra("payment_success", false) == true) {
            val txHash = intent.getStringExtra("txHash")
            Log.d(TAG, "Payment success via onNewIntent! Transaction: $txHash")
            showPaymentApproved()
        }
    }
    
    private fun handleNFCTag(tag: Tag) {
        lifecycleScope.launch {
            try {
                binding.statusText.text = "Processing payment..."
                Log.d(TAG, "Starting payment processing")
                
                val amountInDollars = BigDecimal(currentAmountCents).divide(BigDecimal(100))
                val paymentResult = nfcPaymentHandler.processPayment(tag, amountInDollars)
                
                if (paymentResult.success) {
                    binding.statusText.text = "Payment initiated. Monitoring transaction..."
                    
                    // Start monitoring the transaction
                    val monitorIntent = Intent(this@MainActivity, TransactionMonitorService::class.java).apply {
                        putExtra("chainId", paymentResult.chainId)
                        putExtra("amountWei", paymentResult.amountWei)
                        putExtra("tokenAddress", paymentResult.tokenAddress)
                        putExtra("displayAmount", amountInDollars.toPlainString())
                    }
                    startService(monitorIntent)
                    
                    // Show success and reset after delay
                    Handler(Looper.getMainLooper()).postDelayed({
                        showSuccess("Payment initiated successfully!")
                        clearAmount()
                    }, 2000)
                } else {
                    showError(paymentResult.error ?: "Payment failed")
                    resetPaymentUI()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing NFC payment", e)
                showError("Error: ${e.message}")
                resetPaymentUI()
            }
        }
    }
    
    private fun enableNFCReaderMode() {
        // Try reader mode with SKIP_NDEF_CHECK flag
        // This might help us handle the tag differently
        nfcAdapter?.enableReaderMode(
            this,
            { tag -> handleNFCTag(tag) },
            NfcAdapter.FLAG_READER_NFC_A or 
            NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            Bundle().apply {
                putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250)
            }
        )
        Log.d(TAG, "NFC Reader Mode enabled with SKIP_NDEF_CHECK")
    }
    
    private fun disableNFCReaderMode() {
        nfcAdapter?.disableReaderMode(this)
        Log.d(TAG, "NFC Reader Mode disabled")
    }
    
    private fun showError(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG)
            .setBackgroundTint(getColor(android.R.color.holo_red_dark))
            .show()
    }
    
    private fun showSuccess(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG)
            .setBackgroundTint(getColor(android.R.color.holo_green_dark))
            .show()
    }
    
    private fun showPaymentApproved() {
        binding.apply {
            // Hide payment overlay elements
            paymentOverlay.visibility = View.GONE
            
            // Show success overlay with big green "APPROVED" text
            // Create success overlay dynamically
                val successView = View(this@MainActivity).apply {
                    setBackgroundColor(getColor(android.R.color.holo_green_dark))
                    layoutParams = binding.root.layoutParams
                }
                binding.root.addView(successView)
                
                val approvedText = android.widget.TextView(this@MainActivity).apply {
                    text = "✓ APPROVED"
                    textSize = 48f
                    setTextColor(getColor(android.R.color.white))
                    gravity = android.view.Gravity.CENTER
                    layoutParams = android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                    ).apply {
                        gravity = android.view.Gravity.CENTER
                    }
                }
                binding.root.addView(approvedText)
                
                // Hide after 3 seconds
                Handler(Looper.getMainLooper()).postDelayed({
                    binding.root.removeView(successView)
                    binding.root.removeView(approvedText)
                    clearAmount()
                    resetPaymentUI()
                }, 3000)
        }
        
        // Play success sound if available
        try {
            val notification = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
            val ringtone = android.media.RingtoneManager.getRingtone(applicationContext, notification)
            ringtone.play()
        } catch (e: Exception) {
            Log.e(TAG, "Error playing success sound", e)
        }
    }
}