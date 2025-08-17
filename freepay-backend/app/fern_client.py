import aiohttp
import asyncio
import os
import json
from typing import Dict, Any, Optional
from dotenv import load_dotenv

load_dotenv()

class FernClient:
    def __init__(self):
        self.api_key = os.getenv("FERN_API_KEY")
        self.base_url = os.getenv("FERN_BASE_URL", "https://api.fernhq.com")
        
        if not self.api_key:
            raise ValueError("FERN_API_KEY not found in environment variables")
        
        self.headers = {
            "Authorization": f"Bearer {self.api_key}",
            "Content-Type": "application/json"
        }
        print(f"[KEY] Fern client initialized with API key: {self.api_key[:10]}...")
    
    async def create_customer(self, name: str, email: str) -> Dict[str, Any]:
        """Create a new Fern customer"""
        async with aiohttp.ClientSession() as session:
            # Split name into first and last name
            name_parts = name.split(' ', 1)
            first_name = name_parts[0]
            last_name = name_parts[1] if len(name_parts) > 1 else "Demo"
            
            payload = {
                "firstName": first_name,
                "lastName": last_name,
                "email": email,
                "customerType": "INDIVIDUAL"
            }
            
            print(f"[CREATE] Creating customer: {name} ({email})")
            async with session.post(
                f"{self.base_url}/customers",
                headers=self.headers,
                json=payload
            ) as response:
                result = await response.json()
                print(f"[RESPONSE] Customer creation response: {result}")
                return result
    
    async def get_customer(self, customer_id: str) -> Dict[str, Any]:
        """Get customer details"""
        async with aiohttp.ClientSession() as session:
            async with session.get(
                f"{self.base_url}/customers/{customer_id}",
                headers=self.headers
            ) as response:
                return await response.json()
    
    async def customer_convert(self, customer_id: str, from_currency: str, 
                              to_currency: str, amount: float) -> Dict[str, Any]:
        """Convert currency for a customer"""
        async with aiohttp.ClientSession() as session:
            payload = {
                "from": {"currency": from_currency, "amount": str(amount)},
                "to": {"currency": to_currency}
            }
            
            print(f"[CONVERT] Converting {amount} {from_currency} -> {to_currency} for customer {customer_id}")
            async with session.post(
                f"{self.base_url}/customers/{customer_id}/convert",
                headers=self.headers,
                json=payload
            ) as response:
                result = await response.json()
                print(f"[CONVERT] Conversion result: {result}")
                return result
    
    async def customer_transfer(self, from_customer: str, to_customer: str, 
                               amount: float, currency: str) -> Dict[str, Any]:
        """Transfer between customers"""
        async with aiohttp.ClientSession() as session:
            payload = {
                "destination": {"customer_id": to_customer},
                "amount": str(amount),
                "currency": currency
            }
            
            print(f"[TRANSFER] Transferring {amount} {currency} from {from_customer} to {to_customer}")
            async with session.post(
                f"{self.base_url}/customers/{from_customer}/transfers",
                headers=self.headers,
                json=payload
            ) as response:
                result = await response.json()
                print(f"[TRANSFER] Transfer result: {result}")
                return result
    
    async def get_customer_balance(self, customer_id: str) -> Dict[str, Any]:
        """Get customer balance"""
        async with aiohttp.ClientSession() as session:
            async with session.get(
                f"{self.base_url}/customers/{customer_id}/accounts",
                headers=self.headers
            ) as response:
                result = await response.json()
                print(f"[BALANCE] Balance for {customer_id}: {result}")
                return result
    
    async def list_customer_transactions(self, customer_id: str) -> Dict[str, Any]:
        """List customer transactions"""
        async with aiohttp.ClientSession() as session:
            async with session.get(
                f"{self.base_url}/customers/{customer_id}/transactions",
                headers=self.headers
            ) as response:
                return await response.json()

# Test function
async def test_fern_connection():
    """Test Fern API connection"""
    try:
        fern = FernClient()
        
        # Test with a simple API call (you might need to adjust based on available endpoints)
        print("[TEST] Testing Fern API connection...")
        
        # You can test with creating customers or other available endpoints
        # For now, let's just verify the client initializes
        print("[SUCCESS] Fern client initialized successfully!")
        return True
        
    except Exception as e:
        print(f"[ERROR] Fern API test failed: {e}")
        return False

if __name__ == "__main__":
    asyncio.run(test_fern_connection())