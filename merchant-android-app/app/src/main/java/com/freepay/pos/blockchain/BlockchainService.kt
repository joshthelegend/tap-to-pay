package com.freepay.pos.blockchain

import android.content.Context
import android.util.Log
import com.freepay.pos.utils.ConfigManager
import com.google.gson.Gson
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.web3j.utils.Convert
import java.math.BigDecimal
import java.math.BigInteger
import java.util.concurrent.TimeUnit

data class PaymentToken(
    val chainId: Int,
    val symbol: String,
    val address: String,
    val decimals: Int,
    val balance: BigDecimal
)

data class AlchemyTokenBalance(
    val contractAddress: String,
    val tokenBalance: String
)

data class AlchemyBalanceResponse(
    val address: String,
    val tokenBalances: List<AlchemyTokenBalance>
)

class BlockchainService(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    
    private val gson = Gson()
    init {
        ConfigManager.initialize(context)
    }
    
    private val alchemyApiKey: String = ConfigManager.getAlchemyApiKey()
    private val merchantAddress: String = ConfigManager.getMerchantAddress()
    
    companion object {
        private const val TAG = "BlockchainService"
        
        private val CHAIN_CONFIGS = mapOf(
            // Testnets for demo (switch to mainnet for production)
            11155111 to ChainConfig("ethereum-sepolia", "https://eth-sepolia.g.alchemy.com/v2/"),
            84532 to ChainConfig("base-sepolia", "https://base-sepolia.g.alchemy.com/v2/"),
            // Mainnets (commented out for demo)
            // 1 to ChainConfig("ethereum", "https://eth-mainnet.g.alchemy.com/v2/"),
            // 10 to ChainConfig("optimism", "https://opt-mainnet.g.alchemy.com/v2/"),
            // 137 to ChainConfig("polygon", "https://polygon-mainnet.g.alchemy.com/v2/"),
            // 42161 to ChainConfig("arbitrum", "https://arb-mainnet.g.alchemy.com/v2/"),
            // 8453 to ChainConfig("base", "https://base-mainnet.g.alchemy.com/v2/")
        )
        
        private val STABLECOIN_ADDRESSES = mapOf(
            // Testnet tokens
            11155111 to mapOf( // Ethereum Sepolia
                "USDC" to TokenInfo("0x1c7D4B196Cb0C7B01d743Fbc6116a902379C7238", 6), // Sepolia USDC
                "USDT" to TokenInfo("0x7169D38820dfd117C3FA1f22a697dBA58d90BA06", 6), // Sepolia USDT (example)
            ),
            84532 to mapOf( // Base Sepolia  
                "USDC" to TokenInfo("0x036CbD53842c5426634e7929541eC2318f3dCF7e", 6), // Base Sepolia USDC
                "ETH" to TokenInfo("0x0000000000000000000000000000000000000000", 18), // Native ETH
            )
            // Mainnet tokens (commented out for demo safety)
            // 8453 to mapOf( // Base Mainnet
            //     "USDC" to TokenInfo("0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913", 6),
            //     "USDT" to TokenInfo("0xfde4C96c8593536E31F229EA8f37b2ADa2699bb2", 6),
            //     "DAI" to TokenInfo("0x50c5725949A6F0c72E6C4a641F24049A917DB0Cb", 18)
            // )
        )
    }
    
    data class ChainConfig(val name: String, val rpcUrl: String)
    data class TokenInfo(val address: String, val decimals: Int)
    
    fun getMerchantAddress(): String = merchantAddress
    
    suspend fun fetchMultiChainBalances(walletAddress: String): List<PaymentToken> = 
        withContext(Dispatchers.IO) {
        Log.d(TAG, "🔍 Starting multi-chain balance check for wallet: $walletAddress")
        Log.d(TAG, "📊 Checking ${CHAIN_CONFIGS.size} chains: ${CHAIN_CONFIGS.map { "${it.value.name} (${it.key})" }.joinToString(", ")}")
        
        val allBalances = mutableListOf<PaymentToken>()
        
        val balanceJobs = CHAIN_CONFIGS.map { (chainId, config) ->
            async {
                try {
                    Log.d(TAG, "🌐 Fetching balances for ${config.name} (chainId: $chainId)...")
                    val balances = fetchChainBalances(chainId, config, walletAddress)
                    Log.d(TAG, "✅ ${config.name}: Found ${balances.size} tokens with non-zero balance")
                    balances
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error fetching balances for ${config.name} (chainId: $chainId)", e)
                    emptyList()
                }
            }
        }
        
        balanceJobs.awaitAll().forEach { balances ->
            allBalances.addAll(balances)
        }
        
        Log.d(TAG, "💰 Total tokens found across all chains: ${allBalances.size}")
        allBalances.forEach { token ->
            Log.d(TAG, "  📍 ${token.symbol} on chain ${token.chainId}: ${token.balance} (${token.decimals} decimals)")
        }
        
        allBalances
    }
    
    private suspend fun fetchChainBalances(
        chainId: Int,
        config: ChainConfig,
        walletAddress: String
    ): List<PaymentToken> = withContext(Dispatchers.IO) {
        val balances = mutableListOf<PaymentToken>()
        
        // Fetch native token balance
        val nativeBalance = fetchNativeBalance(chainId, config, walletAddress)
        if (nativeBalance > BigDecimal.ZERO) {
            val symbol = when (chainId) {
                137 -> "MATIC"
                else -> "ETH"
            }
            Log.d(TAG, "  💎 Native token $symbol balance: $nativeBalance")
            balances.add(
                PaymentToken(
                    chainId = chainId,
                    symbol = symbol,
                    address = "0x0000000000000000000000000000000000000000",
                    decimals = 18,
                    balance = nativeBalance
                )
            )
        } else {
            val symbol = when (chainId) {
                137 -> "MATIC"
                else -> "ETH"
            }
            Log.d(TAG, "  ⚪ Native token $symbol balance: 0")
        }
        
        // Fetch token balances
        val tokenBalances = fetchTokenBalances(chainId, config, walletAddress)
        balances.addAll(tokenBalances)
        
        balances
    }
    
    @Suppress("UNUSED_PARAMETER")
    private suspend fun fetchNativeBalance(
        chainId: Int,
        config: ChainConfig,
        walletAddress: String
    ): BigDecimal = withContext(Dispatchers.IO) {
        try {
            val url = "${config.rpcUrl}$alchemyApiKey"
            val json = """
                {
                    "jsonrpc": "2.0",
                    "method": "eth_getBalance",
                    "params": ["$walletAddress", "latest"],
                    "id": 1
                }
            """.trimIndent()
            
            val request = Request.Builder()
                .url(url)
                .post(json.toRequestBody("application/json".toMediaType()))
                .build()
            
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: return@withContext BigDecimal.ZERO
            
            val balanceHex = gson.fromJson(responseBody, Map::class.java)["result"] as? String
            if (balanceHex != null) {
                val balanceWei = BigInteger(balanceHex.removePrefix("0x"), 16)
                return@withContext Convert.fromWei(balanceWei.toString(), Convert.Unit.ETHER)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching native balance", e)
        }
        
        BigDecimal.ZERO
    }
    
    private suspend fun fetchTokenBalances(
        chainId: Int,
        config: ChainConfig,
        walletAddress: String
    ): List<PaymentToken> = withContext(Dispatchers.IO) {
        val tokens = mutableListOf<PaymentToken>()
        
        try {
            // Get token addresses for this chain
            val chainTokens = STABLECOIN_ADDRESSES[chainId] ?: return@withContext emptyList()
            val tokenAddresses = chainTokens.values.map { it.address }
            
            Log.d(TAG, "  🔍 Checking ${chainTokens.size} tokens on ${config.name}: ${chainTokens.keys.joinToString(", ")}")
            
            val url = "${config.rpcUrl}$alchemyApiKey"
            val json = """
                {
                    "jsonrpc": "2.0",
                    "method": "alchemy_getTokenBalances",
                    "params": ["$walletAddress", ${gson.toJson(tokenAddresses)}],
                    "id": 1
                }
            """.trimIndent()
            
            val request = Request.Builder()
                .url(url)
                .post(json.toRequestBody("application/json".toMediaType()))
                .build()
            
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: return@withContext emptyList()
            
            val result = gson.fromJson(responseBody, Map::class.java)["result"] as? Map<*, *>
            val tokenBalances = result?.get("tokenBalances") as? List<*>
            
            tokenBalances?.forEach { tokenBalance ->
                val balance = tokenBalance as? Map<*, *>
                val contractAddress = balance?.get("contractAddress") as? String
                val tokenBalanceHex = balance?.get("tokenBalance") as? String
                
                if (contractAddress != null && tokenBalanceHex != null) {
                    // Find token info
                    val tokenEntry = chainTokens.entries.find { 
                        it.value.address.equals(contractAddress, ignoreCase = true) 
                    }
                    
                    if (tokenEntry != null) {
                        val balanceWei = BigInteger(tokenBalanceHex.removePrefix("0x"), 16)
                        if (balanceWei > BigInteger.ZERO) {
                            val decimals = tokenEntry.value.decimals
                            val balanceDecimal = BigDecimal(balanceWei).divide(
                                BigDecimal.TEN.pow(decimals)
                            )
                            
                            Log.d(TAG, "  💰 ${tokenEntry.key} balance: $balanceDecimal")
                            
                            tokens.add(
                                PaymentToken(
                                    chainId = chainId,
                                    symbol = tokenEntry.key,
                                    address = contractAddress,
                                    decimals = decimals,
                                    balance = balanceDecimal
                                )
                            )
                        } else {
                            Log.d(TAG, "  ⚪ ${tokenEntry.key} balance: 0")
                        }
                    }
                }
            }
            
            if (tokens.isEmpty()) {
                Log.d(TAG, "  ⚪ No token balances found on ${config.name}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error fetching token balances for ${config.name} (chain $chainId)", e)
        }
        
        tokens
    }
    
    fun toWei(amount: BigDecimal, decimals: Int): String {
        return amount.multiply(BigDecimal.TEN.pow(decimals)).toBigInteger().toString()
    }
    
    suspend fun getTransactionStatus(txHash: String, chainId: Int): TransactionStatus = 
        withContext(Dispatchers.IO) {
        try {
            val config = CHAIN_CONFIGS[chainId] ?: return@withContext TransactionStatus.UNKNOWN
            val url = "${config.rpcUrl}$alchemyApiKey"
            
            val json = """
                {
                    "jsonrpc": "2.0",
                    "method": "eth_getTransactionReceipt",
                    "params": ["$txHash"],
                    "id": 1
                }
            """.trimIndent()
            
            val request = Request.Builder()
                .url(url)
                .post(json.toRequestBody("application/json".toMediaType()))
                .build()
            
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: return@withContext TransactionStatus.PENDING
            
            val result = gson.fromJson(responseBody, Map::class.java)["result"] as? Map<*, *>
            if (result != null) {
                val status = result["status"] as? String
                return@withContext when (status) {
                    "0x1" -> TransactionStatus.SUCCESS
                    "0x0" -> TransactionStatus.FAILED
                    else -> TransactionStatus.PENDING
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking transaction status", e)
        }
        
        TransactionStatus.PENDING
    }
    
    enum class TransactionStatus {
        PENDING,
        SUCCESS,
        FAILED,
        UNKNOWN
    }
    
    data class AssetTransfer(
        val hash: String,
        val from: String,
        val to: String,
        val value: BigDecimal,
        val asset: String?,
        val tokenAddress: String?,
        val decimals: Int,
        val category: String,
        val blockNum: String,
        val rawValue: Any? = null
    )
    
    data class AssetTransfersResponse(
        val transfers: List<AssetTransfer>
    )
    
    suspend fun monitorIncomingTransfers(
        chainId: Int,
        expectedAmountWei: String,
        expectedTokenAddress: String?,
        callback: (AssetTransfer) -> Unit
    ): Job = withContext(Dispatchers.IO) {
        val config = CHAIN_CONFIGS[chainId] ?: throw IllegalArgumentException("Unsupported chain: $chainId")
        Log.d(TAG, "🔍 Starting transfer monitoring on ${config.name} for amount: $expectedAmountWei wei")
        Log.d(TAG, "📡 Monitoring transfers to: $merchantAddress")
        Log.d(TAG, "💰 Expected token: ${expectedTokenAddress ?: "Native token"}")
        
        var lastCheckedBlock = getCurrentBlockNumber(chainId, config)
        Log.d(TAG, "📦 Starting from block: $lastCheckedBlock")
        
        launch {
            while (isActive) {
                try {
                    delay(3000) // Poll every 3 seconds
                    
                    val currentBlock = getCurrentBlockNumber(chainId, config)
                    val safeToBlock = maxOf(currentBlock - 1, lastCheckedBlock)
                    
                    if (safeToBlock > lastCheckedBlock) {
                        Log.d(TAG, "🔍 Checking blocks ${lastCheckedBlock} to $safeToBlock")
                        
                        val transfers = getAssetTransfers(
                            chainId = chainId,
                            config = config,
                            fromBlock = "0x${lastCheckedBlock.toString(16)}",
                            toBlock = "0x${safeToBlock.toString(16)}",
                            toAddress = merchantAddress
                        )
                        
                        for (transfer in transfers) {
                            Log.d(TAG, "📡 Transfer detected: ${transfer.value} ${transfer.asset ?: "ETH"} from ${transfer.from}")
                            
                            // Check if this is the expected payment
                            val isCorrectToken = if (expectedTokenAddress == null) {
                                // Native token transfer
                                transfer.category == "external" && transfer.tokenAddress == null
                            } else {
                                // ERC-20 token transfer
                                transfer.tokenAddress?.equals(expectedTokenAddress, ignoreCase = true) == true
                            }
                            
                            if (isCorrectToken) {
                                // Get the raw value in smallest units (wei/units)
                                val rawValue = when (transfer.rawValue) {
                                    is String -> {
                                        // Handle hex string values
                                        transfer.rawValue.removePrefix("0x").toBigIntegerOrNull(16) ?: BigInteger.ZERO
                                    }
                                    is Number -> {
                                        // Handle numeric values
                                        BigInteger(transfer.rawValue.toString())
                                    }
                                    else -> {
                                        // For native transfers, the value might be in decimal format
                                        if (transfer.category == "external") {
                                            // Native ETH - convert from ETH to wei
                                            transfer.value.multiply(BigDecimal("1000000000000000000")).toBigInteger()
                                        } else {
                                            BigInteger.ZERO
                                        }
                                    }
                                }
                                val expectedValueWei = BigInteger(expectedAmountWei)
                                
                                Log.d(TAG, "💵 Transfer raw value: $rawValue, Expected: $expectedValueWei")
                                Log.d(TAG, "📊 Raw value type: ${transfer.rawValue?.javaClass?.simpleName}, Category: ${transfer.category}")
                                
                                if (rawValue == expectedValueWei) {
                                    Log.d(TAG, "✅ Payment confirmed! Hash: ${transfer.hash}")
                                    callback(transfer)
                                    return@launch // Stop monitoring after finding the payment
                                } else {
                                    Log.d(TAG, "❌ Amount mismatch: $rawValue != $expectedValueWei")
                                }
                            }
                        }
                        
                        lastCheckedBlock = safeToBlock
                    }
                } catch (e: Exception) {
                    if (e.message?.contains("past head") == true) {
                        Log.d(TAG, "⏳ Blockchain sync delay, retrying...")
                    } else {
                        Log.e(TAG, "Error monitoring transfers", e)
                    }
                }
            }
        }
    }
    
    @Suppress("UNUSED_PARAMETER")
    private suspend fun getCurrentBlockNumber(chainId: Int, config: ChainConfig): Long = 
        withContext(Dispatchers.IO) {
        try {
            val url = "${config.rpcUrl}$alchemyApiKey"
            val json = """
                {
                    "jsonrpc": "2.0",
                    "method": "eth_blockNumber",
                    "params": [],
                    "id": 1
                }
            """.trimIndent()
            
            val request = Request.Builder()
                .url(url)
                .post(json.toRequestBody("application/json".toMediaType()))
                .build()
            
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: return@withContext 0L
            
            val result = gson.fromJson(responseBody, Map::class.java)["result"] as? String
            result?.let {
                it.removePrefix("0x").toLong(16)
            } ?: 0L
        } catch (e: Exception) {
            Log.e(TAG, "Error getting block number", e)
            0L
        }
    }
    
    @Suppress("UNUSED_PARAMETER")
    private suspend fun getAssetTransfers(
        chainId: Int,
        config: ChainConfig,
        fromBlock: String,
        toBlock: String,
        toAddress: String
    ): List<AssetTransfer> = withContext(Dispatchers.IO) {
        try {
            val url = "${config.rpcUrl}$alchemyApiKey"
            val json = """
                {
                    "jsonrpc": "2.0",
                    "method": "alchemy_getAssetTransfers",
                    "params": [{
                        "toAddress": "$toAddress",
                        "fromBlock": "$fromBlock",
                        "toBlock": "$toBlock",
                        "category": ["external", "erc20"],
                        "withMetadata": true,
                        "excludeZeroValue": true
                    }],
                    "id": 1
                }
            """.trimIndent()
            
            val request = Request.Builder()
                .url(url)
                .post(json.toRequestBody("application/json".toMediaType()))
                .build()
            
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: return@withContext emptyList()
            
            val result = gson.fromJson(responseBody, Map::class.java)["result"] as? Map<*, *>
            val transfers = result?.get("transfers") as? List<*> ?: return@withContext emptyList()
            
            transfers.mapNotNull { transferData ->
                val transfer = transferData as? Map<*, *> ?: return@mapNotNull null
                
                // Log raw transfer data for debugging
                Log.d(TAG, "📋 Raw transfer data: $transfer")
                
                val rawContract = transfer["rawContract"] as? Map<*, *>
                
                AssetTransfer(
                    hash = transfer["hash"] as? String ?: "",
                    from = transfer["from"] as? String ?: "",
                    to = transfer["to"] as? String ?: "",
                    value = BigDecimal(transfer["value"]?.toString() ?: "0"),
                    asset = transfer["asset"] as? String,
                    tokenAddress = rawContract?.get("address") as? String,
                    decimals = 18, // Not needed for raw value comparison
                    category = transfer["category"] as? String ?: "",
                    blockNum = transfer["blockNum"] as? String ?: "",
                    rawValue = rawContract?.get("value") ?: transfer["value"]
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting asset transfers", e)
            emptyList()
        }
    }
}