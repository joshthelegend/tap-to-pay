from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
import os
import time
import random
from typing import Optional
from dotenv import load_dotenv
import logging

# Configure logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

load_dotenv()

app = FastAPI(
    title="EazyPay Demo Backend",
    description="Demo-ready crypto tap-to-pay backend for hackathon",
    version="1.0.0"
)

# Add CORS middleware
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],  
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Simulated customer data for demo
DEMO_CUSTOMERS = {
    "alice": {
        "id": "3ed93e27-3a1d-4be7-b139-7ee34578c873",
        "name": "Alice Johnson",
        "email": "alice.freepay.demo@example.com",
        "balance_usd": 1000.00,
        "transactions": []
    },
    "bob": {
        "id": "49eed76a-008d-49e7-9118-b497d86bfc74", 
        "name": "Bob's Coffee Shop",
        "email": "bob.freepay.demo@example.com",
        "balance_usd": 50.00,
        "transactions": []
    }
}

class PaymentRequest(BaseModel):
    amount: float
    currency: str = "USD"
    crypto_tx_hash: str
    chain_id: int
    customer_wallet_address: str

class PaymentResponse(BaseModel):
    success: bool
    merchant_received_amount: float
    fern_transfer_id: str
    message: str

@app.get("/")
async def root():
    return {
        "service": "EazyPay Demo Backend",
        "status": "running",
        "version": "1.0.0",
        "demo_mode": True
    }

@app.get("/health")
async def health_check():
    return {"status": "healthy", "service": "EazyPay Demo Backend"}

@app.get("/customers/alice/balance")
async def get_alice_balance():
    """Get Alice's balance for demo"""
    return {
        "customer_id": DEMO_CUSTOMERS["alice"]["id"],
        "balances": {
            "USD": DEMO_CUSTOMERS["alice"]["balance_usd"]
        },
        "status": "active"
    }

@app.get("/customers/bob/balance") 
async def get_bob_balance():
    """Get Bob's balance for demo"""
    return {
        "customer_id": DEMO_CUSTOMERS["bob"]["id"],
        "balances": {
            "USD": DEMO_CUSTOMERS["bob"]["balance_usd"]
        },
        "status": "active"
    }

@app.get("/merchant/balance")
async def get_merchant_balance():
    """Get merchant's current balance"""
    return {
        "merchant_id": DEMO_CUSTOMERS["bob"]["id"],
        "balances": {
            "USD": DEMO_CUSTOMERS["bob"]["balance_usd"]
        },
        "recent_settlements": DEMO_CUSTOMERS["bob"]["transactions"][-5:],
        "status": "active"
    }

@app.post("/process-payment", response_model=PaymentResponse)
async def process_payment(payment: PaymentRequest):
    """
    Demo payment processing endpoint
    Simulates: crypto receipt → fiat conversion → merchant settlement
    """
    try:
        logger.info(f"[DEMO] Processing ${payment.amount} payment")
        logger.info(f"[DEMO] Crypto TX: {payment.crypto_tx_hash} on chain {payment.chain_id}")
        logger.info(f"[DEMO] Customer wallet: {payment.customer_wallet_address}")
        
        # Simulate processing delay
        await simulate_processing_delay()
        
        # Simulate conversion (1:1 for demo)
        usd_amount = payment.amount
        
        # Check Alice has sufficient balance
        if DEMO_CUSTOMERS["alice"]["balance_usd"] < payment.amount:
            raise HTTPException(status_code=400, detail=f"Insufficient funds: Alice has ${DEMO_CUSTOMERS['alice']['balance_usd']}, needs ${payment.amount}")
        
        # Process the transfer
        DEMO_CUSTOMERS["alice"]["balance_usd"] -= payment.amount
        DEMO_CUSTOMERS["bob"]["balance_usd"] += payment.amount
        
        # Create transaction records
        tx_id = f"demo_tx_{int(time.time())}_{random.randint(1000, 9999)}"
        timestamp = time.strftime("%Y-%m-%d %H:%M:%S")
        
        alice_tx = {
            "id": tx_id,
            "type": "payment_sent", 
            "amount": -payment.amount,
            "to": "Bob's Coffee Shop",
            "timestamp": timestamp,
            "crypto_tx": payment.crypto_tx_hash,
            "chain": get_chain_name(payment.chain_id)
        }
        
        bob_tx = {
            "id": tx_id,
            "type": "payment_received",
            "amount": payment.amount, 
            "from": "Alice Johnson",
            "timestamp": timestamp,
            "settlement_status": "completed",
            "crypto_tx": payment.crypto_tx_hash,
            "chain": get_chain_name(payment.chain_id)
        }
        
        DEMO_CUSTOMERS["alice"]["transactions"].append(alice_tx)
        DEMO_CUSTOMERS["bob"]["transactions"].append(bob_tx)
        
        logger.info(f"[DEMO] ✅ Payment successful: ${usd_amount} transferred to merchant")
        logger.info(f"[DEMO] Alice balance: ${DEMO_CUSTOMERS['alice']['balance_usd']}")
        logger.info(f"[DEMO] Bob balance: ${DEMO_CUSTOMERS['bob']['balance_usd']}")
        
        return PaymentResponse(
            success=True,
            merchant_received_amount=usd_amount,
            fern_transfer_id=tx_id,
            message=f"Successfully processed ${usd_amount} crypto payment with instant fiat settlement"
        )
        
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"[DEMO] Payment processing error: {e}")
        raise HTTPException(status_code=500, detail=str(e))

@app.post("/demo/reset")
async def reset_demo():
    """Reset demo balances for multiple demonstrations"""
    DEMO_CUSTOMERS["alice"]["balance_usd"] = 1000.00
    DEMO_CUSTOMERS["bob"]["balance_usd"] = 50.00
    DEMO_CUSTOMERS["alice"]["transactions"].clear()
    DEMO_CUSTOMERS["bob"]["transactions"].clear()
    
    return {
        "message": "Demo reset successful",
        "alice_balance": DEMO_CUSTOMERS["alice"]["balance_usd"],
        "bob_balance": DEMO_CUSTOMERS["bob"]["balance_usd"]
    }

@app.get("/demo/status")
async def demo_status():
    """Get current demo status for dashboard"""
    return {
        "customers": {
            "alice": {
                "name": DEMO_CUSTOMERS["alice"]["name"],
                "balance": DEMO_CUSTOMERS["alice"]["balance_usd"],
                "transactions": len(DEMO_CUSTOMERS["alice"]["transactions"])
            },
            "bob": {
                "name": DEMO_CUSTOMERS["bob"]["name"],
                "balance": DEMO_CUSTOMERS["bob"]["balance_usd"],
                "transactions": len(DEMO_CUSTOMERS["bob"]["transactions"])
            }
        },
        "total_volume": sum(abs(tx["amount"]) for customer in DEMO_CUSTOMERS.values() for tx in customer["transactions"]),
        "demo_ready": True
    }

async def simulate_processing_delay():
    """Simulate realistic payment processing time"""
    import asyncio
    # Simulate 0.5-1.5 second processing time
    delay = 0.5 + random.random()
    await asyncio.sleep(delay)

def get_chain_name(chain_id: int) -> str:
    """Get human-readable chain name"""
    chain_names = {
        1: "Ethereum",
        10: "Optimism", 
        137: "Polygon",
        42161: "Arbitrum",
        8453: "Base",
        84532: "Base Sepolia",
        11155111: "Ethereum Sepolia"
    }
    return chain_names.get(chain_id, f"Chain {chain_id}")

if __name__ == "__main__":
    import uvicorn
    port = int(os.getenv("PORT", 8000))
    uvicorn.run(app, host="0.0.0.0", port=port)