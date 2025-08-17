package com.freepay.pos.utils

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.util.Properties

object ConfigManager {
    private const val TAG = "ConfigManager"
    private const val LOCAL_PROPERTIES_FILE = "local.properties"
    private var properties: Properties? = null
    
    fun initialize(context: Context) {
        if (properties == null) {
            properties = loadProperties(context)
        }
    }
    
    private fun loadProperties(context: Context): Properties {
        val props = Properties()
        
        try {
            // Try to load from assets (this is where config files should be for APK deployment)
            try {
                context.assets.open(LOCAL_PROPERTIES_FILE).use { 
                    props.load(it)
                    Log.d(TAG, "Loading config from assets")
                    return props
                }
            } catch (e: Exception) {
                Log.e(TAG, "No local.properties found in assets")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error loading properties", e)
        }
        
        // Provide clear error message about missing configuration
        val errorMessage = """
            
            ========================================
            CONFIGURATION ERROR
            ========================================
            
            The app requires a local.properties file with:
            - alchemy.api.key=YOUR_API_KEY
            - merchant.address=YOUR_WALLET_ADDRESS
            
            For development:
            1. Copy local.properties.example to local.properties
            2. Fill in your values
            3. Place the file in app/src/main/assets/
            4. Rebuild and redeploy the app
            
            The local.properties file in the project root is NOT included in the APK.
            You must place it in app/src/main/assets/ for it to be packaged.
            
            ========================================
        """.trimIndent()
        
        Log.e(TAG, errorMessage)
        throw IllegalStateException(errorMessage)
    }
    
    fun getAlchemyApiKey(): String {
        val key = properties?.getProperty("alchemy.api.key")
            ?: throw IllegalStateException("alchemy.api.key not found in local.properties")
        
        if (key == "YOUR_ALCHEMY_API_KEY_HERE" || key.isBlank()) {
            throw IllegalStateException("Please set your Alchemy API key in local.properties")
        }
        
        return key
    }
    
    fun getMerchantAddress(): String {
        val address = properties?.getProperty("merchant.address")
            ?: throw IllegalStateException("merchant.address not found in local.properties")
        
        if (address == "0xYOUR_MERCHANT_WALLET_ADDRESS_HERE" || address.isBlank() || !address.startsWith("0x")) {
            throw IllegalStateException("Please set a valid merchant address in local.properties")
        }
        
        return address
    }
}