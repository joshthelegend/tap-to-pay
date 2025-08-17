"""
Circle USDC Integration for EazyPay
Handles stablecoin payments and programmable wallets
"""
import os
import asyncio
import aiohttp
from typing import Dict, Any, Optional, List
from dataclasses import dataclass
import uuid
import logging

logger = logging.getLogger(__name__)

@dataclass
class CircleWallet:
    wallet_id: str
    address: str
    blockchain: str
    balance: float
    currency: str = "USD"

@dataclass
class CircleTransfer:
    transfer_id: str
    source_wallet: str
    destination_wallet: str
    amount: float
    status: str
    blockchain: str

class CircleAPIClient:
    def __init__(self):
        self.api_key = os.getenv("CIRCLE_API_KEY")
        self.base_url = "https://api.circle.com/v1"
        self.headers = {
            "Authorization": f"Bearer {self.api_key}",
            "Content-Type": "application/json"
        }
        
        # Supported blockchains for USDC
        self.supported_chains = {
            "ETH": "Ethereum",
            "MATIC": "Polygon", 
            "ARB": "Arbitrum",
            "OP": "Optimism",
            "BASE": "Base",
            "AVAX": "Avalanche"
        }
    
    async def create_wallet(
        self, 
        customer_id: str,
        blockchain: str = "BASE"
    ) -> CircleWallet:
        """Create a programmable wallet for customer"""
        try:
            async with aiohttp.ClientSession() as session:
                wallet_data = {
                    "idempotencyKey": str(uuid.uuid4()),
                    "description": f"EazyPay wallet for {customer_id}",
                    "walletSetId": os.getenv("CIRCLE_WALLET_SET_ID"),
                    "blockchain": blockchain
                }
                
                url = f"{self.base_url}/wallets"
                async with session.post(url, headers=self.headers, json=wallet_data) as response:
                    if response.status in [200, 201]:
                        data = await response.json()
                        wallet = data.get("data", {})
                        return CircleWallet(
                            wallet_id=wallet.get("walletId"),
                            address=wallet.get("address"),
                            blockchain=blockchain,
                            balance=0.0
                        )
                    else:
                        error = await response.text()
                        logger.error(f"Circle wallet creation failed: {error}")
                        raise Exception(f"Failed to create wallet: {error}")
                        
        except Exception as e:
            logger.error(f"Circle integration error: {e}")
            raise
    
    async def get_wallet_balance(self, wallet_id: str) -> Dict[str, float]:
        """Get USDC balance across all chains"""
        async with aiohttp.ClientSession() as session:
            url = f"{self.base_url}/wallets/{wallet_id}/balances"
            async with session.get(url, headers=self.headers) as response:
                if response.status == 200:
                    data = await response.json()
                    balances = {}
                    for balance in data.get("data", []):
                        if balance.get("currency") == "USD":
                            chain = balance.get("chain")
                            amount = float(balance.get("amount", 0))
                            balances[chain] = amount
                    return balances
                return {}
    
    async def create_transfer(
        self,
        source_wallet_id: str,
        destination_address: str,
        amount: float,
        blockchain: str = "BASE"
    ) -> CircleTransfer:
        """Transfer USDC between wallets"""
        try:
            async with aiohttp.ClientSession() as session:
                transfer_data = {
                    "idempotencyKey": str(uuid.uuid4()),
                    "source": {
                        "type": "wallet",
                        "id": source_wallet_id
                    },
                    "destination": {
                        "type": "blockchain",
                        "address": destination_address,
                        "chain": blockchain
                    },
                    "amount": {
                        "amount": str(amount),
                        "currency": "USD"
                    }
                }
                
                url = f"{self.base_url}/transfers"
                async with session.post(url, headers=self.headers, json=transfer_data) as response:
                    if response.status in [200, 201]:
                        data = await response.json()
                        transfer = data.get("data", {})
                        return CircleTransfer(
                            transfer_id=transfer.get("id"),
                            source_wallet=source_wallet_id,
                            destination_wallet=destination_address,
                            amount=amount,
                            status=transfer.get("status"),
                            blockchain=blockchain
                        )
                    else:
                        error = await response.text()
                        logger.error(f"Circle transfer failed: {error}")
                        raise Exception(f"Failed to create transfer: {error}")
                        
        except Exception as e:
            logger.error(f"Circle transfer error: {e}")
            raise
    
    async def get_exchange_rate(self, from_currency: str = "USD", to_currency: str = "EUR") -> float:
        """Get USDC exchange rates"""
        # Circle provides stable 1:1 for USDC to USD
        # This would integrate with their FX API for other currencies
        if from_currency == "USD" and to_currency == "USD":
            return 1.0
        
        # Placeholder for real FX rates
        rates = {
            "EUR": 0.92,
            "GBP": 0.79,
            "JPY": 149.50
        }
        return rates.get(to_currency, 1.0)

# Smart routing for optimal USDC chain selection
class USDCSmartRouter:
    def __init__(self):
        self.chain_fees = {
            "ETH": 15.00,      # Ethereum mainnet - high fees
            "MATIC": 0.01,     # Polygon - very low fees
            "ARB": 0.10,       # Arbitrum - low fees
            "OP": 0.10,        # Optimism - low fees
            "BASE": 0.05,      # Base - low fees
            "AVAX": 0.50       # Avalanche - medium fees
        }
        
        self.chain_speeds = {
            "ETH": 180,        # ~3 minutes
            "MATIC": 3,        # ~3 seconds
            "ARB": 2,          # ~2 seconds
            "OP": 2,           # ~2 seconds
            "BASE": 2,         # ~2 seconds
            "AVAX": 2          # ~2 seconds
        }
    
    async def select_optimal_chain(
        self,
        amount: float,
        available_chains: List[str],
        priority: str = "cost"  # "cost" or "speed"
    ) -> str:
        """Select the best blockchain for USDC transfer"""
        if not available_chains:
            return "BASE"  # Default to Base
        
        if priority == "cost":
            # Sort by fees (lowest first)
            sorted_chains = sorted(
                available_chains,
                key=lambda x: self.chain_fees.get(x, 999)
            )
        else:
            # Sort by speed (fastest first)
            sorted_chains = sorted(
                available_chains,
                key=lambda x: self.chain_speeds.get(x, 999)
            )
        
        return sorted_chains[0]

# Integration with EazyPay
async def process_circle_payment(
    amount: float,
    customer_wallet: str,
    merchant_wallet: str,
    preferred_chain: str = None
) -> Dict[str, Any]:
    """Process USDC payment through Circle"""
    try:
        client = CircleAPIClient()
        router = USDCSmartRouter()
        
        # Get available chains for customer
        balances = await client.get_wallet_balance(customer_wallet)
        available_chains = [chain for chain, balance in balances.items() if balance >= amount]
        
        # Select optimal chain
        if not preferred_chain:
            preferred_chain = await router.select_optimal_chain(
                amount=amount,
                available_chains=available_chains,
                priority="cost"
            )
        
        # Create transfer
        transfer = await client.create_transfer(
            source_wallet_id=customer_wallet,
            destination_address=merchant_wallet,
            amount=amount,
            blockchain=preferred_chain
        )
        
        return {
            "success": True,
            "provider": "Circle USDC",
            "transfer_id": transfer.transfer_id,
            "amount": transfer.amount,
            "chain": preferred_chain,
            "fee": router.chain_fees.get(preferred_chain, 0),
            "estimated_time": router.chain_speeds.get(preferred_chain, 10),
            "status": transfer.status,
            "message": f"USDC transfer initiated on {preferred_chain}"
        }
        
    except Exception as e:
        logger.error(f"Circle payment failed: {e}")
        return {
            "success": False,
            "provider": "Circle USDC",
            "error": str(e)
        }

# Demo test function
async def test_circle_integration():
    """Test Circle USDC integration"""
    # Set test API key (you'd need a real one)
    os.environ["CIRCLE_API_KEY"] = "your_circle_api_key"
    os.environ["CIRCLE_WALLET_SET_ID"] = "your_wallet_set_id"
    
    client = CircleAPIClient()
    router = USDCSmartRouter()
    
    # Test wallet creation
    print("Creating test wallet...")
    wallet = await client.create_wallet("jaison_test", "BASE")
    print(f"Wallet created: {wallet.address} on {wallet.blockchain}")
    
    # Test smart routing
    optimal_chain = await router.select_optimal_chain(
        amount=25.00,
        available_chains=["ETH", "BASE", "MATIC"],
        priority="cost"
    )
    print(f"Optimal chain for $25 transfer: {optimal_chain}")
    
    # Test payment simulation
    result = await process_circle_payment(
        amount=15.00,
        customer_wallet="test_customer_wallet",
        merchant_wallet="test_merchant_wallet"
    )
    print(f"Payment result: {result}")

if __name__ == "__main__":
    asyncio.run(test_circle_integration())