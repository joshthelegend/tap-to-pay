package com.freepay.pos.nfc

import android.content.Context
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.IsoDep
import android.util.Log
import com.freepay.pos.blockchain.BlockchainService
import com.freepay.pos.blockchain.PaymentToken
import com.freepay.pos.utils.ConfigManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.nio.charset.StandardCharsets

data class PaymentResult(
    val success: Boolean,
    val transactionHash: String? = null,
    val chainId: Int? = null,
    val amountWei: String? = null,
    val tokenAddress: String? = null,
    val error: String? = null
)

class NFCPaymentHandler(private val context: Context) {
    private val blockchainService = BlockchainService(context)
    
    init {
        ConfigManager.initialize(context)
    }
    
    private val merchantAddress: String = ConfigManager.getMerchantAddress()
    
    companion object {
        private const val TAG = "NFCPaymentHandler"
        private const val WALLET_ADDRESS_URI = "wallet:address"
        
        // Custom command that might be used by the wallet
        private const val GET_WALLET_CMD: Byte = 0x01
        private const val SEND_PAYMENT_CMD: Byte = 0x02
    }
    
    suspend fun processPayment(tag: Tag, amount: BigDecimal): PaymentResult = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "📱 Processing payment for amount: $amount")
            Log.d(TAG, "📱 Tag ID: ${tag.id.toHex()}")
            Log.d(TAG, "📱 Available tag technologies: ${tag.techList.joinToString()}")
            
            // Check which technology is available
            when {
                tag.techList.contains(Ndef::class.java.name) -> {
                    Log.d(TAG, "✅ NDEF technology available")
                    val ndef = Ndef.get(tag)!!
                    Log.d(TAG, "📊 NDEF details: MaxSize=${ndef.maxSize}, Type=${ndef.type}, isWritable=${ndef.isWritable}")
                    
                    // Connect to tag
                    if (!ndef.isConnected) {
                        ndef.connect()
                    }
                    
                    // Process using NDEF
                    return@withContext processWithNdef(ndef, amount)
                }
                
                tag.techList.contains(IsoDep::class.java.name) -> {
                    Log.d(TAG, "📱 IsoDep technology available")
                    val isoDep = IsoDep.get(tag)!!
                    
                    // Connect
                    isoDep.connect()
                    isoDep.timeout = 5000
                    
                    // Try simple custom protocol first
                    return@withContext processWithSimpleProtocol(isoDep, amount)
                }
                
                else -> {
                    Log.e(TAG, "❌ No supported technology found")
                    return@withContext PaymentResult(
                        success = false,
                        error = "Tag does not support NDEF or IsoDep. Technologies: ${tag.techList.joinToString()}"
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error processing payment", e)
            PaymentResult(
                success = false,
                error = e.message ?: "Unknown error"
            )
        }
    }
    
    private suspend fun processWithSimpleProtocol(isoDep: IsoDep, amount: BigDecimal): PaymentResult = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "🔄 Trying FreePay protocol")
            
            // First select the FreePay AID
            val selectFreepayAID = byteArrayOf(
                0x00, // CLA
                0xA4.toByte(), // INS (SELECT)
                0x04, // P1 (Select by AID)
                0x00, // P2
                0x07, // Lc (length of AID)
                0xF0.toByte(), 0x46, 0x52, 0x45, 0x45, 0x50, 0x41, 0x59 // "FREEPAY" in hex
            )
            
            Log.d(TAG, "📤 Selecting FreePay AID: F046524545504159")
            var response = isoDep.transceive(selectFreepayAID)
            Log.d(TAG, "📥 Select response: ${response.toHex()}")
            
            // Check if selection was successful
            if (response.size >= 2 && 
                response[response.size-2] == 0x90.toByte() && 
                response[response.size-1] == 0x00.toByte()) {
                Log.d(TAG, "✅ FreePay app selected successfully")
                
                // Create NDEF message for wallet:address
                val ndefMessage = NdefMessage(NdefRecord.createUri(WALLET_ADDRESS_URI))
                val ndefBytes = ndefMessage.toByteArray()
                
                // Build PAYMENT command: 80 CF 00 00 + NDEFLength(1 byte) + NDEF data
                val paymentCommand = ByteArray(4 + 1 + ndefBytes.size)
                paymentCommand[0] = 0x80.toByte() // CLA
                paymentCommand[1] = 0xCF.toByte() // INS (PAYMENT)
                paymentCommand[2] = 0x00 // P1
                paymentCommand[3] = 0x00 // P2
                paymentCommand[4] = ndefBytes.size.toByte() // NDEF Length (1 byte)
                System.arraycopy(ndefBytes, 0, paymentCommand, 5, ndefBytes.size)
                
                Log.d(TAG, "📤 Sending PAYMENT command: ${paymentCommand.toHex()}")
                Log.d(TAG, "   Command: ${paymentCommand.take(4).toByteArray().toHex()}")
                Log.d(TAG, "   NDEF Length: ${ndefBytes.size} (0x${String.format("%02X", ndefBytes.size)})")
                Log.d(TAG, "   NDEF Data: ${ndefBytes.toHex()}")
                
                response = isoDep.transceive(paymentCommand)
                Log.d(TAG, "📥 PAYMENT response: ${response.toHex()}")
                
                // Check if we got wallet address
                if (response.size > 0 && response[0] != 0x6A.toByte()) {
                    // Response should be the wallet address in CAIP-10 format
                    val walletAddressText = String(response, StandardCharsets.UTF_8)
                    Log.d(TAG, "📥 Response as text: $walletAddressText")
                    
                    val walletAddress = parseWalletAddress(walletAddressText)
                    Log.d(TAG, "💳 Got wallet address: $walletAddress")
                    
                    return@withContext continuePaymentFlow(isoDep, walletAddress, amount)
                }
                
                // Try sending NDEF as APDU data
                val ndefApdu = ByteArray(5 + ndefBytes.size)
                ndefApdu[0] = 0x00 // CLA
                ndefApdu[1] = 0xD6.toByte() // INS (UPDATE BINARY)
                ndefApdu[2] = 0x00 // P1
                ndefApdu[3] = 0x00 // P2
                ndefApdu[4] = ndefBytes.size.toByte() // Lc
                System.arraycopy(ndefBytes, 0, ndefApdu, 5, ndefBytes.size)
                
                Log.d(TAG, "📤 Sending NDEF message as APDU: ${ndefApdu.toHex()}")
                response = isoDep.transceive(ndefApdu)
                Log.d(TAG, "📥 Response: ${response.toHex()}")
                
                // If UPDATE BINARY didn't work, try a simple ENVELOPE command
                if (response.size == 2 && response[0] == 0x6A.toByte()) {
                    Log.d(TAG, "🔄 UPDATE BINARY failed, trying ENVELOPE command")
                    
                    val envelopeApdu = ByteArray(5 + ndefBytes.size)
                    envelopeApdu[0] = 0x00 // CLA
                    envelopeApdu[1] = 0xC2.toByte() // INS (ENVELOPE)
                    envelopeApdu[2] = 0x00 // P1
                    envelopeApdu[3] = 0x00 // P2
                    envelopeApdu[4] = ndefBytes.size.toByte() // Lc
                    System.arraycopy(ndefBytes, 0, envelopeApdu, 5, ndefBytes.size)
                    
                    response = isoDep.transceive(envelopeApdu)
                    Log.d(TAG, "📥 ENVELOPE response: ${response.toHex()}")
                }
                
                // Try one more approach - send just the URI string
                if (response.size == 2 && response[0] == 0x6A.toByte()) {
                    Log.d(TAG, "🔄 ENVELOPE failed, trying PUT DATA command")
                    
                    val putDataApdu = ByteArray(5 + ndefBytes.size)
                    putDataApdu[0] = 0x00 // CLA
                    putDataApdu[1] = 0xDA.toByte() // INS (PUT DATA)
                    putDataApdu[2] = 0x00 // P1
                    putDataApdu[3] = 0x00 // P2
                    putDataApdu[4] = ndefBytes.size.toByte() // Lc
                    System.arraycopy(ndefBytes, 0, putDataApdu, 5, ndefBytes.size)
                    
                    response = isoDep.transceive(putDataApdu)
                    Log.d(TAG, "📥 PUT DATA response: ${response.toHex()}")
                }
                
                // If still failing, try GET DATA to see if we can read anything
                if (response.size == 2 && response[0] == 0x6A.toByte()) {
                    Log.d(TAG, "🔄 All write attempts failed, trying GET DATA")
                    
                    val getDataApdu = byteArrayOf(
                        0x00, // CLA
                        0xCA.toByte(), // INS (GET DATA)
                        0x00, // P1
                        0x00, // P2
                        0x00  // Le
                    )
                    
                    response = isoDep.transceive(getDataApdu)
                    Log.d(TAG, "📥 GET DATA response: ${response.toHex()}")
                    
                    if (response.size > 2) {
                        val data = response.copyOfRange(0, response.size - 2)
                        Log.d(TAG, "📥 GET DATA content: ${String(data, StandardCharsets.UTF_8)}")
                    }
                }
                
                // Check if we got a valid response
                if (response.size >= 2) {
                    val sw1 = response[response.size - 2].toInt() and 0xFF
                    val sw2 = response[response.size - 1].toInt() and 0xFF
                    
                    if (sw1 == 0x90 && sw2 == 0x00 && response.size > 2) {
                        // Success - parse wallet address
                        val addressData = response.copyOfRange(0, response.size - 2)
                        val walletAddress = parseWalletAddress(String(addressData, StandardCharsets.UTF_8))
                        Log.d(TAG, "💳 Got wallet address: $walletAddress")
                        
                        // Continue with payment
                        return@withContext continuePaymentFlow(isoDep, walletAddress, amount)
                    }
                }
            } else {
                Log.d(TAG, "❌ Failed to select FreePay app - status: ${response.toHex()}")
            }
            
            // If custom command didn't work, try sending the NDEF message as raw bytes
            Log.d(TAG, "🔄 Custom command failed, trying raw NDEF bytes with text wrapper")
            
            // Create a simple text message
            val message = WALLET_ADDRESS_URI.toByteArray(StandardCharsets.UTF_8)
            val textCmd = ByteArray(5 + message.size)
            textCmd[0] = 0x00 // CLA
            textCmd[1] = 0x00 // INS - generic
            textCmd[2] = 0x00 // P1
            textCmd[3] = 0x00 // P2
            textCmd[4] = message.size.toByte() // Lc
            System.arraycopy(message, 0, textCmd, 5, message.size)
            
            Log.d(TAG, "📤 Sending text command: ${textCmd.toHex()}")
            response = isoDep.transceive(textCmd)
            Log.d(TAG, "📥 Response: ${response.toHex()}")
            
            // Parse any response we get
            if (response.size > 0) {
                val responseText = String(response, StandardCharsets.UTF_8).trim()
                Log.d(TAG, "📥 Response as text: $responseText")
                
                // Check if it looks like a wallet address
                if (responseText.contains("0x") && responseText.length >= 42) {
                    val walletAddress = parseWalletAddress(responseText)
                    Log.d(TAG, "💳 Found wallet address in response: $walletAddress")
                    return@withContext continuePaymentFlow(isoDep, walletAddress, amount)
                }
            }
            
            PaymentResult(
                success = false,
                error = "Failed to communicate with wallet app - unsupported protocol"
            )
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error in simple protocol processing", e)
            try { isoDep.close() } catch (_: Exception) {}
            PaymentResult(
                success = false,
                error = e.message ?: "Unknown error"
            )
        }
    }
    
    private suspend fun processWithNdef(ndef: Ndef, amount: BigDecimal): PaymentResult = withContext(Dispatchers.IO) {
        try {
            // Step 1: Request wallet address
            Log.d(TAG, "📡 Sending wallet:address request via NDEF")
            val walletAddress = requestWalletAddressNdef(ndef) ?: return@withContext PaymentResult(
                success = false,
                error = "Failed to get wallet address"
            )
            
            Log.d(TAG, "💳 Received wallet address: $walletAddress")
            
            // Step 2: Fetch multi-chain balances
            Log.d(TAG, "🔍 Fetching multi-chain balances for $walletAddress")
            val balances = blockchainService.fetchMultiChainBalances(walletAddress)
            
            // Step 3: Select optimal payment token
            val selectedToken = selectOptimalPaymentToken(balances, amount)
                ?: return@withContext PaymentResult(
                    success = false,
                    error = "Insufficient funds in wallet"
                )
            
            Log.d(TAG, "💎 Selected token: ${selectedToken.symbol} on chain ${selectedToken.chainId}")
            
            // Step 4: Send EIP-681 payment request
            val paymentUri = createEIP681URI(selectedToken, amount)
            Log.d(TAG, "💸 Sending payment request: $paymentUri")
            
            if (sendPaymentRequestNdef(ndef, paymentUri)) {
                ndef.close()
                val amountInWei = blockchainService.toWei(amount, selectedToken.decimals)
                PaymentResult(
                    success = true,
                    chainId = selectedToken.chainId,
                    amountWei = amountInWei,
                    tokenAddress = if (selectedToken.address == "0x0000000000000000000000000000000000000000") null else selectedToken.address
                )
            } else {
                PaymentResult(
                    success = false,
                    error = "Failed to send payment request"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error in NDEF processing", e)
            PaymentResult(
                success = false,
                error = e.message ?: "Unknown error"
            )
        }
    }
    
    private suspend fun continuePaymentFlow(isoDep: IsoDep, walletAddress: String, amount: BigDecimal): PaymentResult {
        // Fetch balances
        val balances = blockchainService.fetchMultiChainBalances(walletAddress)
        val selectedToken = selectOptimalPaymentToken(balances, amount)
            ?: return PaymentResult(
                success = false,
                error = "Insufficient funds in wallet"
            )
        
        Log.d(TAG, "💎 Selected token: ${selectedToken.symbol} on chain ${selectedToken.chainId}")
        
        // Send payment request
        val paymentUri = createEIP681URI(selectedToken, amount)
        Log.d(TAG, "💸 Payment URI: $paymentUri")
        
        // Create NDEF message with payment URI
        val ndefMessage = NdefMessage(NdefRecord.createUri(paymentUri))
        val ndefBytes = ndefMessage.toByteArray()
        
        // Build PAYMENT command: 80 CF 00 00 + NDEFLength(1 byte) + NDEF data
        val paymentCommand = ByteArray(4 + 1 + ndefBytes.size)
        paymentCommand[0] = 0x80.toByte() // CLA
        paymentCommand[1] = 0xCF.toByte() // INS (PAYMENT)
        paymentCommand[2] = 0x00 // P1
        paymentCommand[3] = 0x00 // P2
        paymentCommand[4] = ndefBytes.size.toByte() // NDEF Length (1 byte)
        System.arraycopy(ndefBytes, 0, paymentCommand, 5, ndefBytes.size)
        
        Log.d(TAG, "📤 Sending payment NDEF via PAYMENT command")
        val response = isoDep.transceive(paymentCommand)
        Log.d(TAG, "📥 Payment response: ${response.toHex()}")
        
        isoDep.close()
        val amountInWei = blockchainService.toWei(amount, selectedToken.decimals)
        return PaymentResult(
            success = true,
            chainId = selectedToken.chainId,
            amountWei = amountInWei,
            tokenAddress = if (selectedToken.address == "0x0000000000000000000000000000000000000000") null else selectedToken.address
        )
    }
    
    private suspend fun requestWalletAddressNdef(ndef: Ndef): String? = withContext(Dispatchers.IO) {
        try {
            // Create NDEF URI record for wallet:address
            val uriRecord = NdefRecord.createUri(WALLET_ADDRESS_URI)
            val message = NdefMessage(uriRecord)
            
            Log.d(TAG, "📤 Writing NDEF message: $WALLET_ADDRESS_URI")
            Log.d(TAG, "📤 NDEF Record details:")
            Log.d(TAG, "   TNF: ${uriRecord.tnf}")
            Log.d(TAG, "   Type: ${uriRecord.type?.toString(Charsets.UTF_8)}")
            Log.d(TAG, "   Payload: ${uriRecord.payload.toHex()}")
            
            // Write the message
            ndef.writeNdefMessage(message)
            
            // Give wallet app time to process and write response
            Thread.sleep(500)
            
            // Read response
            Log.d(TAG, "📥 Reading response from tag...")
            val response = ndef.ndefMessage
            
            if (response != null && response.records.isNotEmpty()) {
                val record = response.records[0]
                Log.d(TAG, "📥 Response received:")
                Log.d(TAG, "   TNF: ${record.tnf}")
                Log.d(TAG, "   Type: ${record.type?.toString(Charsets.UTF_8) ?: "null"}")
                Log.d(TAG, "   Payload (${record.payload.size} bytes): ${record.payload.toHex()}")
                
                // Parse the response based on record type
                val addressText = when {
                    // Text record
                    record.tnf == NdefRecord.TNF_WELL_KNOWN && 
                    record.type.contentEquals(NdefRecord.RTD_TEXT) -> {
                        parseTextRecord(record.payload)
                    }
                    // URI record (wallet might respond with ethereum: URI)
                    record.tnf == NdefRecord.TNF_WELL_KNOWN && 
                    record.type.contentEquals(NdefRecord.RTD_URI) -> {
                        parseUriRecord(record.payload)
                    }
                    // Raw payload (most common based on logs)
                    else -> {
                        String(record.payload, StandardCharsets.UTF_8)
                    }
                }
                
                Log.d(TAG, "📱 Parsed address text: $addressText")
                
                // Extract the actual address
                val parsedAddress = parseWalletAddress(addressText)
                Log.d(TAG, "✅ Final parsed address: $parsedAddress")
                
                return@withContext parsedAddress
            } else {
                Log.e(TAG, "❌ No response received from wallet")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error requesting wallet address", e)
            null
        }
    }
    
    private fun parseTextRecord(payload: ByteArray): String {
        if (payload.isEmpty()) return ""
        
        // Text records have a status byte followed by language code
        val statusByte = payload[0].toInt()
        val languageCodeLength = statusByte and 0x3F
        
        return if (payload.size > languageCodeLength + 1) {
            String(payload, languageCodeLength + 1, payload.size - languageCodeLength - 1, StandardCharsets.UTF_8)
        } else {
            ""
        }
    }
    
    private fun parseUriRecord(payload: ByteArray): String {
        if (payload.isEmpty()) return ""
        
        // URI records have an abbreviation byte followed by the URI
        return if (payload.size > 1) {
            String(payload, 1, payload.size - 1, StandardCharsets.UTF_8)
        } else {
            ""
        }
    }
    
    private fun parseWalletAddress(payload: String): String {
        return when {
            // CAIP-10 format: chainNamespace:chainId:address
            payload.contains(":") -> {
                payload.substringAfterLast(":")
            }
            // Direct address
            payload.startsWith("0x") -> {
                payload
            }
            // Try to extract address if there's other formatting
            else -> {
                val addressRegex = "0x[a-fA-F0-9]{40}".toRegex()
                addressRegex.find(payload)?.value ?: payload
            }
        }
    }
    
    private suspend fun sendPaymentRequestNdef(ndef: Ndef, paymentUri: String): Boolean = 
        withContext(Dispatchers.IO) {
        try {
            // Create NDEF URI record with EIP-681 payment request
            val uriRecord = NdefRecord.createUri(paymentUri)
            val message = NdefMessage(uriRecord)
            
            Log.d(TAG, "💸 Writing payment URI: $paymentUri")
            Log.d(TAG, "💸 NDEF Record details:")
            Log.d(TAG, "   TNF: ${uriRecord.tnf}")
            Log.d(TAG, "   Type: ${uriRecord.type?.toString(Charsets.UTF_8)}")
            Log.d(TAG, "   Payload length: ${uriRecord.payload.size}")
            
            ndef.writeNdefMessage(message)
            
            Log.d(TAG, "✅ Payment request sent successfully!")
            Log.d(TAG, "📱 Wallet app should now process the payment...")
            
            // Payment request sent successfully
            // The wallet app will process it and submit the transaction
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error sending payment request", e)
            false
        }
    }
    
    private fun selectOptimalPaymentToken(
        balances: List<PaymentToken>,
        requiredAmount: BigDecimal
    ): PaymentToken? {
        Log.d(TAG, "🎯 Selecting optimal payment token for amount: $requiredAmount")
        Log.d(TAG, "📊 Available tokens with sufficient balance:")
        
        val eligibleTokens = balances.filter { it.balance >= requiredAmount }
        if (eligibleTokens.isEmpty()) {
            Log.e(TAG, "❌ No tokens have sufficient balance for payment of $requiredAmount")
            balances.forEach { token ->
                Log.d(TAG, "  ❌ ${token.symbol} on chain ${token.chainId}: ${token.balance} (need $requiredAmount)")
            }
            return null
        }
        
        eligibleTokens.forEach { token ->
            val chainName = when(token.chainId) {
                1 -> "Ethereum"
                10 -> "Optimism"
                137 -> "Polygon"
                42161 -> "Arbitrum"
                8453 -> "Base"
                else -> "Chain ${token.chainId}"
            }
            Log.d(TAG, "  ✅ ${token.symbol} on $chainName: ${token.balance}")
        }
        
        // Priority order from the spec:
        // 1. L2 Stablecoins
        // 2. L2 Other Tokens  
        // 3. L2 Native Tokens
        // 4. L1 Stablecoins
        // 5. L1 Other Tokens
        // 6. L1 ETH
        
        val priorityOrder = listOf(
            // L2 Native USDC (highest priority)
            Pair(10, "USDC"), Pair(137, "USDC"), Pair(42161, "USDC"), Pair(8453, "USDC"),
            // L2 Other Stablecoins
            Pair(10, "USDT"), Pair(137, "USDT"), Pair(42161, "USDT"), Pair(8453, "USDT"),
            Pair(10, "DAI"), Pair(137, "DAI"), Pair(42161, "DAI"), Pair(8453, "DAI"),
            // L2 Bridged USDC (lower priority than native)
            Pair(10, "USDC.e"), Pair(137, "USDC.e"), Pair(42161, "USDC.e"),
            // L1 Stablecoins
            Pair(1, "USDC"), Pair(1, "USDT"), Pair(1, "DAI"),
            // L2 Native
            Pair(10, "ETH"), Pair(137, "MATIC"), Pair(42161, "ETH"), Pair(8453, "ETH"),
            // L1 Native
            Pair(1, "ETH")
        )
        
        Log.d(TAG, "🔍 Checking priority order...")
        
        // First try priority order
        for ((chainId, symbol) in priorityOrder) {
            val token = balances.find { 
                it.chainId == chainId && it.symbol == symbol && it.balance >= requiredAmount 
            }
            if (token != null) {
                val chainName = when(chainId) {
                    1 -> "Ethereum"
                    10 -> "Optimism"
                    137 -> "Polygon"
                    42161 -> "Arbitrum"
                    8453 -> "Base"
                    else -> "Chain $chainId"
                }
                Log.d(TAG, "🏆 Selected $symbol on $chainName (priority match)")
                Log.d(TAG, "   Balance: ${token.balance}, Required: $requiredAmount")
                return token
            }
        }
        
        // If no preferred token found, use any token with sufficient balance
        // Prefer L2 over L1
        val l2Chains = setOf(10, 137, 42161, 8453)
        
        Log.d(TAG, "⚠️ No priority token found, checking L2 tokens...")
        
        // Try L2 tokens first
        val l2Token = balances.filter { it.chainId in l2Chains && it.balance >= requiredAmount }
            .firstOrNull()
        if (l2Token != null) {
            Log.d(TAG, "🏆 Selected ${l2Token.symbol} on chain ${l2Token.chainId} (L2 preference)")
            return l2Token
        }
        
        // Then try L1 tokens
        Log.d(TAG, "⚠️ No L2 token found, checking L1 tokens...")
        val l1Token = balances.find { it.balance >= requiredAmount }
        if (l1Token != null) {
            Log.d(TAG, "🏆 Selected ${l1Token.symbol} on chain ${l1Token.chainId} (L1 fallback)")
        }
        return l1Token
    }
    
    private fun createEIP681URI(token: PaymentToken, amount: BigDecimal): String {
        val amountInWei = blockchainService.toWei(amount, token.decimals)
        
        return if (token.address == "0x0000000000000000000000000000000000000000") {
            // Native token (ETH/MATIC)
            "ethereum:$merchantAddress@${token.chainId}?value=$amountInWei"
        } else {
            // ERC-20 token
            "ethereum:${token.address}@${token.chainId}/transfer?address=$merchantAddress&uint256=$amountInWei"
        }
    }
    
    // Extension function to convert ByteArray to hex string for logging
    private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }
}