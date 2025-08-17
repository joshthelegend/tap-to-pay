"""
Coinbase Commerce Integration for EazyPay
Handles crypto payment processing via Coinbase
"""
import os
import asyncio
import aiohttp
from typing import Dict, Any, Optional
from dataclasses import dataclass
import hashlib
import hmac
import logging

logger = logging.getLogger(__name__)

@dataclass
class CoinbasePayment:
    charge_id: str
    amount: float
    currency: str
    status: str
    payment_url: Optional[str] = None
    crypto_received: Optional[Dict] = None

class CoinbaseCommerceClient:
    def __init__(self):
        self.api_key = os.getenv("COINBASE_COMMERCE_API_KEY")
        self.webhook_secret = os.getenv("COINBASE_WEBHOOK_SECRET")
        self.base_url = "https://api.commerce.coinbase.com"
        self.headers = {
            "X-CC-Api-Key": self.api_key,
            "X-CC-Version": "2018-03-22",
            "Content-Type": "application/json"
        }
    
    async def create_charge(
        self,
        amount: float,
        currency: str = "USD",
        customer_name: str = None,
        description: str = "EazyPay Payment"
    ) -> CoinbasePayment:
        """Create a payment charge for customer"""
        try:
            async with aiohttp.ClientSession() as session:
                charge_data = {
                    "name": description,
                    "description": f"Payment from {customer_name or 'Customer'}",
                    "pricing_type": "fixed_price",
                    "local_price": {
                        "amount": str(amount),
                        "currency": currency
                    },
                    "metadata": {
                        "customer": customer_name,
                        "payment_method": "EazyPay_NFC",
                        "integration": "tap_to_pay"
                    }
                }
                
                url = f"{self.base_url}/charges"
                async with session.post(url, headers=self.headers, json=charge_data) as response:
                    if response.status in [200, 201]:
                        data = await response.json()
                        charge = data.get("data", {})
                        return CoinbasePayment(
                            charge_id=charge.get("id"),
                            amount=amount,
                            currency=currency,
                            status="pending",
                            payment_url=charge.get("hosted_url")
                        )
                    else:
                        error = await response.text()
                        logger.error(f"Coinbase charge creation failed: {error}")
                        raise Exception(f"Failed to create charge: {error}")
                        
        except Exception as e:
            logger.error(f"Coinbase integration error: {e}")
            raise
    
    async def get_charge_status(self, charge_id: str) -> Dict[str, Any]:
        """Check payment status"""
        async with aiohttp.ClientSession() as session:
            url = f"{self.base_url}/charges/{charge_id}"
            async with session.get(url, headers=self.headers) as response:
                if response.status == 200:
                    data = await response.json()
                    charge = data.get("data", {})
                    return {
                        "status": charge.get("timeline", [{}])[-1].get("status", "pending"),
                        "payments": charge.get("payments", []),
                        "amount_received": self._calculate_received_amount(charge)
                    }
                return {"status": "unknown", "error": await response.text()}
    
    def _calculate_received_amount(self, charge: Dict) -> float:
        """Calculate total amount received in USD equivalent"""
        total_usd = 0.0
        for payment in charge.get("payments", []):
            # This would need real exchange rate conversion
            # For demo, using the payment value directly
            total_usd += float(payment.get("value", {}).get("local", {}).get("amount", 0))
        return total_usd
    
    def verify_webhook(self, payload: bytes, signature: str) -> bool:
        """Verify webhook signature from Coinbase"""
        expected_signature = hmac.new(
            self.webhook_secret.encode(),
            payload,
            hashlib.sha256
        ).hexdigest()
        return hmac.compare_digest(expected_signature, signature)

# Integration with EazyPay backend
async def process_coinbase_payment(
    amount: float,
    customer_name: str = "Jaison Jayaraj"
) -> Dict[str, Any]:
    """Process payment through Coinbase Commerce"""
    try:
        client = CoinbaseCommerceClient()
        
        # Create charge
        payment = await client.create_charge(
            amount=amount,
            customer_name=customer_name,
            description=f"EazyPay - ${amount} payment"
        )
        
        # In production, you'd wait for webhook confirmation
        # For demo, return the payment details
        return {
            "success": True,
            "provider": "Coinbase Commerce",
            "charge_id": payment.charge_id,
            "payment_url": payment.payment_url,
            "amount": payment.amount,
            "status": payment.status,
            "message": "Payment charge created. Customer can pay with any cryptocurrency."
        }
        
    except Exception as e:
        logger.error(f"Coinbase payment failed: {e}")
        return {
            "success": False,
            "provider": "Coinbase Commerce",
            "error": str(e)
        }

# Demo/test function
async def test_coinbase_integration():
    """Test Coinbase Commerce integration"""
    # Set test API key (you'd need a real one)
    os.environ["COINBASE_COMMERCE_API_KEY"] = "your_api_key_here"
    
    result = await process_coinbase_payment(
        amount=15.00,
        customer_name="Jaison Jayaraj"
    )
    
    print(f"Coinbase Payment Result: {result}")

if __name__ == "__main__":
    asyncio.run(test_coinbase_integration())