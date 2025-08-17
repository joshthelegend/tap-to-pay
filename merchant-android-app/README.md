# FreePay Android POS Terminal

This is an implementation of the [FreePay Merchant Terminal](https://github.com/FreePayPOS/merchant-app) for Android 10+. It should work on any NFC enabled Android phone or POS device. 

It allows you to accept crypto payments with a single tap from a customers phone. It automatically handles negotiating chain/tokens to reduce friction. 

Currently the customer must have the [FreePay Customer Android App](https://github.com/FreePayPOS/customer-android-app) installed for this to work.

## Configuration

### Required Setup

Before building and deploying the app, you must configure your Alchemy API key and merchant wallet address:

1. Copy the example configuration file:
   ```bash
   cp local.properties.example app/src/main/assets/local.properties
   ```

2. Edit `local.properties` and add your configuration:
   ```properties
   # Alchemy API Key - Get yours at https://www.alchemy.com/
   alchemy.api.key=YOUR_ALCHEMY_API_KEY_HERE
   
   # Merchant Wallet Address - Where payments will be sent
   merchant.address=0xYOUR_MERCHANT_WALLET_ADDRESS_HERE
   ```

3. **Important**: 
   - Never commit `local.properties` to version control. It's already in `.gitignore`.
   - The file in the project root is for reference only. The app reads from `app/src/main/assets/local.properties`.

### Getting an Alchemy API Key

1. Sign up for free at [https://www.alchemy.com/](https://www.alchemy.com/)
2. Create a new app and select the networks you want to support
3. Copy your API key from the dashboard

## Running

1. Load the app in Android Studio
2. Sync Gradle files (this may be done automatically)
3. Plug in your phone via USB Cable + Enable developer mode if not done already
4. Hit the "Run App" button


## Project Structure

```
androidpos/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/freepay/pos/
│   │   │   │   ├── MainActivity.kt
│   │   │   │   ├── nfc/
│   │   │   │   │   ├── NFCService.kt
│   │   │   │   │   └── NFCPaymentHandler.kt
│   │   │   │   ├── blockchain/
│   │   │   │   │   ├── BlockchainService.kt
│   │   │   │   │   └── TransactionMonitor.kt
│   │   │   │   └── utils/
│   │   │   ├── res/
│   │   │   └── AndroidManifest.xml
│   │   └── build.gradle.kts
│   └── build.gradle.kts
├── build.gradle.kts
└── settings.gradle.kts
```
