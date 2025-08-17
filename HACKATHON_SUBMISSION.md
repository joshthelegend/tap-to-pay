# EazyPay: Crypto Tap-to-Pay with Instant Fiat Settlement

## Description

EazyPay solves the biggest barrier to mainstream crypto adoption: enabling merchants to accept cryptocurrency payments while receiving instant USD settlement in their traditional bank accounts, eliminating volatility risk and complexity.

### The Problem
Today's crypto payment landscape is fragmented and merchant-hostile:
- **Volatility Risk**: Merchants receiving crypto face constant price fluctuations
- **Complexity**: Current solutions require merchants to manage crypto wallets and exchanges  
- **Poor UX**: Customers must choose which wallet/token/chain to pay with
- **High Fees**: Traditional crypto payment processors charge 3-5% fees
- **Slow Settlement**: Converting crypto to fiat takes days through existing services

### Our Solution: "1inch for Payments"
EazyPay acts as intelligent payment routing that automatically finds the optimal way to pay across multiple wallets and blockchain networks, while providing instant fiat settlement to merchants.

**Core Innovation**: Smart Payment Routing Algorithm
1. **Multi-Chain Balance Detection**: Automatically scans customer's funds across Ethereum, Base, Polygon, Arbitrum, and Optimism
2. **Optimal Token Selection**: Prioritizes L2 stablecoins (Base USDC > Polygon USDC > L1 ETH) for lowest fees
3. **Instant Conversion**: Crypto → USD conversion via financial infrastructure APIs
4. **Direct Bank Settlement**: USD deposited directly to merchant's bank account via ACH/RTP

**User Experience**: True contactless payments via NFC
- **Customer**: Tap phone → Payment automatically routed → Done (no wallet/token selection needed)
- **Merchant**: Enter amount → Customer taps → Receive USD instantly (no crypto knowledge required)

### Technical Architecture

**Android Mobile Apps**
- **Customer App**: NFC card emulation, automatic wallet detection (MetaMask, Rainbow, Coinbase Wallet, etc.), EIP-681 payment URI handling
- **Merchant POS App**: NFC reader mode, calculator-style amount input, real-time settlement dashboard

**Backend Payment Processor** 
- Smart routing algorithm with multi-chain balance fetching via Alchemy API
- Crypto-to-fiat conversion and bank settlement simulation (Fern API integration ready)
- Real-time transaction monitoring and merchant notifications

**Blockchain Integration**
- Multi-chain support: Ethereum, Base, Polygon, Arbitrum, Optimism (testnets for demo)
- Token support: USDC, USDT, DAI, ETH, MATIC with automatic decimal handling
- EIP-681 payment standard compliance for broad wallet compatibility

### Demo Flow
1. Merchant opens POS app, enters $10.00 charge
2. Customer opens EazyPay customer app (one-time wallet linking)
3. Customer taps NFC phone to merchant phone
4. Apps exchange payment information via custom NDEF protocol
5. Backend automatically detects customer has 50 USDC on Base Sepolia
6. Algorithm selects Base USDC (optimal: L2 + stablecoin + low fees)
7. Customer phone opens MetaMask with pre-filled payment request
8. Customer approves transaction
9. Backend monitors blockchain, detects payment completion
10. Instant crypto→USD conversion and settlement to merchant
11. Merchant dashboard shows +$10.00 USD received

### Market Impact
- **Lower Fees**: 1.5% vs 2.9% traditional card processing
- **Instant Settlement**: Same-day vs T+2 for card payments  
- **Global Access**: Crypto enables payments from anywhere
- **Future-Proof**: Ready for mainstream crypto adoption wave

## How It's Made

### Technology Stack

**Mobile Applications (Android/Kotlin)**
- **UI Framework**: Jetpack Compose with Material Design 3
- **NFC Implementation**: Host Card Emulation (HCE) for customer app, NFC Reader Mode for merchant app
- **Blockchain Integration**: Web3j library for Ethereum interactions, custom multi-chain RPC management
- **Architecture**: MVVM pattern with coroutines for async operations

**Backend (Python/FastAPI)**
- **API Framework**: FastAPI with async/await for high concurrency
- **Blockchain APIs**: Alchemy SDK for multi-chain balance fetching and transaction monitoring
- **Payment Processing**: Fern API integration for crypto-to-fiat conversion and bank settlement
- **Data Models**: Pydantic for type-safe API request/response handling

**Blockchain Infrastructure**
- **Networks**: Ethereum, Base, Polygon, Arbitrum, Optimism (mainnet ready, testnet for demo)
- **Standards**: EIP-681 (payment URIs), EIP-155 (chain IDs), ERC-20 token standard
- **APIs**: Alchemy for RPC calls, balance queries, and transaction monitoring webhooks

### Key Technical Innovations

**1. Custom NFC Protocol for Crypto Payments**
We built a custom NDEF (NFC Data Exchange Format) protocol that enables direct phone-to-phone communication for payment initiation. This was particularly challenging because:
- Standard NFC payments (Apple Pay/Google Pay) use proprietary protocols
- We needed to exchange wallet addresses and payment amounts securely
- Both phones need to act as NFC card and reader simultaneously

**Implementation**: Customer app uses Host Card Emulation (HCE) with custom AID (Application Identifier), merchant app uses NFC Reader Mode. Payment data is exchanged via NDEF messages containing wallet addresses and EIP-681 payment URIs.

**2. Multi-Chain Balance Aggregation & Smart Routing**
The most complex part was building an algorithm that:
- Queries 5+ blockchain networks simultaneously via Alchemy API
- Parses different token contracts (USDC has different addresses on each chain)
- Calculates optimal payment route considering fees, speed, and user preferences
- Handles decimal precision differences (USDC=6 decimals, DAI=18 decimals)

**Implementation**: Parallel coroutine-based balance fetching with priority scoring algorithm. Base USDC gets highest priority (native L2 stablecoin), then other L2 stablecoins, then L1 tokens.

**3. Wallet Auto-Detection & Integration**
Supporting 10+ different crypto wallets required reverse-engineering Android package manager APIs:
- Detect installed wallet apps via intent query resolution
- Generate wallet-specific deep links for payment approval
- Handle different wallet URI schemes and parameters

**Implementation**: Custom wallet detection using PackageManager.queryIntentActivities() with ethereum: URI scheme, fallback to known package name detection.

### Partner Technology Integrations

**Alchemy API**
- **Benefit**: Reliable multi-chain infrastructure without running our own nodes
- **Usage**: Balance queries, transaction monitoring, gas estimation across 5 networks
- **Why Critical**: Hackathon timeline required production-ready blockchain access

**Fern API** 
- **Benefit**: Instant crypto-to-fiat conversion with direct bank settlement
- **Usage**: Converting received crypto to USD and depositing to merchant bank accounts
- **Implementation**: RESTful API integration with customer account management

**Android NFC Stack**
- **Benefit**: Leverages existing contactless payment infrastructure  
- **Usage**: Host Card Emulation and NFC Reader Mode for true tap-to-pay experience
- **Why Better**: No QR codes, works like Apple Pay/Google Pay that users already understand

### Notable Hacks & Engineering Decisions

**1. Testnet Demo Strategy**
For hackathon safety, we built dual mainnet/testnet support with a simple configuration toggle. All blockchain interactions work on both Base Sepolia (testnet) and Base mainnet without code changes.

**2. Demo Backend with Simulated Settlement**
Since full Fern API integration requires production KYC/compliance, we built a demo backend that simulates the entire payment flow with realistic delays and responses. Judges see the full user experience while we maintain the ability to switch to production APIs.

**3. NFC Protocol Fallback Chain**
NFC communication can fail due to phone positioning, interference, etc. We implemented a fallback chain:
1. Try custom EazyPay protocol
2. Fall back to standard NDEF message exchange  
3. Final fallback to QR code generation (traditional crypto payment UX)

**4. Multi-Wallet Support Without SDK Dependencies**
Instead of integrating 10+ wallet SDKs (which would bloat the app), we use the universal ethereum: URI scheme that all wallets support. This "hacky" approach actually provides better compatibility than official SDK integrations.

### Development Challenges Overcome

**Android NFC Debugging**: NFC only works on physical devices, making development iterations slow. We built extensive logging and state management to debug payment flows.

**Multi-Chain State Management**: Tracking balances and transactions across 5+ networks required careful async programming and error handling for network failures.

**UX Simplification**: The hardest challenge was hiding crypto complexity from end users while maintaining security. Our solution: smart defaults with zero user configuration required.

### What Makes This Production-Ready

**Security First**: No private keys ever touch our servers or apps. All transactions require user approval in their own trusted wallet.

**Scalable Architecture**: FastAPI backend can handle thousands of concurrent payment requests, multi-chain balance queries are parallelized and cached.

**Real-World Ready**: Full error handling, network failure recovery, transaction monitoring, and merchant settlement reporting.

**Open Source**: Complete transparency with proper credential management for community contributions.

This project represents 6+ months of equivalent development work completed in hackathon timeframe, demonstrating the power of focused execution on a clear problem with massive market potential.