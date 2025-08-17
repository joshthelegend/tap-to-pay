"""
Real Fern API Integration for EazyPay
Handles crypto-to-fiat conversion and bank settlement
"""
import os
import asyncio
import aiohttp
from typing import Dict, Any, Optional
from dataclasses import dataclass
import logging

logger = logging.getLogger(__name__)

@dataclass
class FernPaymentResult:
    success: bool
    transfer_id: Optional[str]
    amount_received: float
    error_message: Optional[str] = None
    settlement_status: str = "pending"

class FernAPIClient:
    def __init__(self):
        self.api_key = os.getenv("FERN_API_KEY")
        self.base_url = os.getenv("FERN_BASE_URL", "https://api.fernhq.com")
        self.headers = {
            "Authorization": f"Bearer {self.api_key}",
            "Content-Type": "application/json"
        }
        
        if not self.api_key:
            raise ValueError("FERN_API_KEY environment variable is required")
    
    async def get_customer_status(self, customer_id: str) -> Dict[str, Any]:
        """Check customer KYC and account status"""
        async with aiohttp.ClientSession() as session:
            url = f"{self.base_url}/customers/{customer_id}"
            async with session.get(url, headers=self.headers) as response:
                if response.status == 200:
                    return await response.json()
                else:
                    error_text = await response.text()
                    logger.error(f"Fern API error: {response.status} - {error_text}")
                    return {"error": f"HTTP {response.status}", "details": error_text}
    
    async def get_customer_wallets(self, customer_id: str) -> Dict[str, Any]:
        """Get customer's Fern wallet information"""
        async with aiohttp.ClientSession() as session:
            url = f"{self.base_url}/customers/{customer_id}/wallets"
            async with session.get(url, headers=self.headers) as response:
                if response.status == 200:
                    return await response.json()
                else:
                    error_text = await response.text()
                    logger.warning(f"Wallets endpoint: {response.status} - {error_text}")
                    return {"wallets": [], "error": f"HTTP {response.status}"}
    
    async def create_crypto_transfer(
        self, 
        customer_id: str,
        amount: float,
        currency: str = "USD",
        crypto_tx_hash: str = None,
        chain_id: int = None
    ) -> FernPaymentResult:
        """Process crypto payment and convert to fiat"""
        try:
            # First check customer status
            customer_status = await self.get_customer_status(customer_id)
            if "error" in customer_status:
                return FernPaymentResult(
                    success=False,
                    transfer_id=None,
                    amount_received=0.0,
                    error_message=f"Customer verification failed: {customer_status['error']}"
                )
            
            # Check if KYC is complete
            if customer_status.get("customerStatus") != "VERIFIED":
                kyc_link = customer_status.get("kycLink")
                return FernPaymentResult(
                    success=False,
                    transfer_id=None,
                    amount_received=0.0,
                    error_message=f"KYC required. Complete verification at: {kyc_link}"
                )
            
            # Try to create transfer (this endpoint needs to be discovered)
            async with aiohttp.ClientSession() as session:
                transfer_data = {
                    "customerId": customer_id,
                    "amount": amount,
                    "currency": currency,
                    "sourceType": "crypto",
                    "metadata": {
                        "crypto_tx_hash": crypto_tx_hash,
                        "chain_id": chain_id,
                        "payment_method": "EazyPay_NFC"
                    }
                }
                
                # Try different possible endpoints
                possible_endpoints = [
                    f"{self.base_url}/transfers",
                    f"{self.base_url}/payments",
                    f"{self.base_url}/customers/{customer_id}/transfers"
                ]
                
                for endpoint in possible_endpoints:
                    try:
                        async with session.post(endpoint, headers=self.headers, json=transfer_data) as response:
                            response_text = await response.text()
                            logger.info(f"Tried {endpoint}: {response.status} - {response_text}")
                            
                            if response.status in [200, 201]:
                                result = await response.json()
                                return FernPaymentResult(
                                    success=True,
                                    transfer_id=result.get("transferId", "fern_transfer_success"),
                                    amount_received=amount,
                                    settlement_status="completed"
                                )
                    except Exception as e:
                        logger.warning(f"Endpoint {endpoint} failed: {e}")
                        continue
                
                # If all endpoints fail, return error with details
                return FernPaymentResult(
                    success=False,
                    transfer_id=None,
                    amount_received=0.0,
                    error_message="No valid Fern transfer endpoint found. May need API access or different integration approach."
                )
                
        except Exception as e:
            logger.error(f"Fern transfer error: {e}")
            return FernPaymentResult(
                success=False,
                transfer_id=None,
                amount_received=0.0,
                error_message=f"Integration error: {str(e)}"
            )

# Test the integration
async def test_fern_integration():
    """Test real Fern API integration"""
    client = FernAPIClient()
    
    # Test customer status
    alice_id = "3ed93e27-3a1d-4be7-b139-7ee34578c873"
    bob_id = "49eed76a-008d-49e7-9118-b497d86bfc74"
    
    print("=== Testing Fern API Integration ===")
    
    # Check Alice's status
    alice_status = await client.get_customer_status(alice_id)
    print(f"Alice Status: {alice_status}")
    
    # Check Bob's status
    bob_status = await client.get_customer_status(bob_id)
    print(f"Bob Status: {bob_status}")
    
    # Try to get wallets
    alice_wallets = await client.get_customer_wallets(alice_id)
    print(f"Alice Wallets: {alice_wallets}")
    
    # Test payment processing
    payment_result = await client.create_crypto_transfer(
        customer_id=alice_id,
        amount=25.50,
        crypto_tx_hash="0xtest_fern_integration",
        chain_id=8453
    )
    print(f"Payment Result: {payment_result}")

if __name__ == "__main__":
    asyncio.run(test_fern_integration())