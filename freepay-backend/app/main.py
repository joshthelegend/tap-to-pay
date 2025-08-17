from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
from .fern_client import FernClient
import os
from typing import Optional
from dotenv import load_dotenv
import logging

# Configure logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

load_dotenv()

app = FastAPI(
    title="FreePay Backend",
    description="Crypto tap-to-pay with instant fiat settlement",
    version="1.0.0"
)

# Add CORS middleware
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],  # Configure appropriately for production
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Initialize Fern client
fern = FernClient()

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

class CustomerCreateRequest(BaseModel):
    name: str
    email: str

@app.get("/")
async def root():
    return {
        "service": "FreePay Backend",
        "status": "running",
        "version": "1.0.0"
    }

@app.get("/health")
async def health_check():
    return {"status": "healthy", "service": "FreePay Backend"}

@app.post("/customers")
async def create_customer(request: CustomerCreateRequest):
    """Create a new Fern customer for testing"""
    try:
        result = await fern.create_customer(request.name, request.email)
        return result
    except Exception as e:
        logger.error(f"Error creating customer: {e}")
        raise HTTPException(status_code=500, detail=str(e))

@app.get("/customers/{customer_id}")
async def get_customer(customer_id: str):
    """Get customer details"""
    try:
        result = await fern.get_customer(customer_id)
        return result
    except Exception as e:
        logger.error(f"Error getting customer: {e}")
        raise HTTPException(status_code=500, detail=str(e))

@app.get("/customers/{customer_id}/balance")
async def get_customer_balance(customer_id: str):
    """Get customer balance"""
    try:
        result = await fern.get_customer_balance(customer_id)
        return result
    except Exception as e:
        logger.error(f"Error getting balance: {e}")
        raise HTTPException(status_code=500, detail=str(e))

@app.get("/merchant/balance")
async def get_merchant_balance():
    """Get merchant's current balance"""
    try:
        customer_b_id = os.getenv("CUSTOMER_B_ID")
        if not customer_b_id:
            raise HTTPException(status_code=500, detail="Merchant customer ID not configured")
        
        balance = await fern.get_customer_balance(customer_b_id)
        return balance
    except Exception as e:
        logger.error(f"Error getting merchant balance: {e}")
        raise HTTPException(status_code=500, detail=str(e))

@app.post("/process-payment", response_model=PaymentResponse)
async def process_payment(payment: PaymentRequest):
    """
    Main payment processing endpoint
    1. Verify crypto transaction (simplified for demo)
    2. Convert crypto to USD via Fern
    3. Transfer USD to merchant customer
    """
    try:
        customer_a_id = os.getenv("CUSTOMER_A_ID")  # Payer
        customer_b_id = os.getenv("CUSTOMER_B_ID")  # Merchant
        
        if not customer_a_id or not customer_b_id:
            raise HTTPException(status_code=500, detail="Customer IDs not configured")
        
        logger.info(f"Processing payment: {payment.amount} {payment.currency}")
        logger.info(f"Crypto TX: {payment.crypto_tx_hash} on chain {payment.chain_id}")
        
        # Step 1: Verify crypto transaction received
        # (In production, verify on-chain transaction)
        # For demo, we'll simulate this
        
        # Step 2: Convert crypto to USD (simulate receiving crypto)
        # Note: In real implementation, you'd first receive the crypto, then convert
        conversion_result = await fern.customer_convert(
            customer_id=customer_a_id,
            from_currency="USDC",  # Assume USDC for demo
            to_currency="USD",
            amount=payment.amount
        )
        
        # Check if conversion was successful
        if not conversion_result.get("success", True):  # Adjust based on Fern API response format
            error_msg = conversion_result.get("error", "Currency conversion failed")
            raise HTTPException(status_code=400, detail=f"Currency conversion failed: {error_msg}")
        
        # Extract converted amount (adjust based on actual Fern API response format)
        usd_amount = payment.amount  # For demo, assume 1:1 conversion
        if "amount" in conversion_result:
            usd_amount = float(conversion_result["amount"])
        
        # Step 3: Transfer USD to merchant
        transfer_result = await fern.customer_transfer(
            from_customer=customer_a_id,
            to_customer=customer_b_id,
            amount=usd_amount,
            currency="USD"
        )
        
        # Check if transfer was successful
        if not transfer_result.get("success", True):  # Adjust based on Fern API response format
            error_msg = transfer_result.get("error", "Transfer to merchant failed")
            raise HTTPException(status_code=400, detail=f"Transfer to merchant failed: {error_msg}")
        
        transfer_id = transfer_result.get("id", "unknown")
        
        logger.info(f"✅ Payment successful: ${usd_amount} transferred to merchant")
        
        return PaymentResponse(
            success=True,
            merchant_received_amount=usd_amount,
            fern_transfer_id=transfer_id,
            message=f"Successfully transferred ${usd_amount} to merchant"
        )
        
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Payment processing error: {e}")
        raise HTTPException(status_code=500, detail=str(e))

@app.post("/test-transfer")
async def test_transfer(amount: float = 10.0):
    """Test transfer between customers"""
    try:
        customer_a_id = os.getenv("CUSTOMER_A_ID")
        customer_b_id = os.getenv("CUSTOMER_B_ID")
        
        if not customer_a_id or not customer_b_id:
            raise HTTPException(status_code=500, detail="Customer IDs not configured")
        
        result = await fern.customer_transfer(
            from_customer=customer_a_id,
            to_customer=customer_b_id,
            amount=amount,
            currency="USD"
        )
        
        return {"message": f"Test transfer of ${amount} completed", "result": result}
        
    except Exception as e:
        logger.error(f"Test transfer error: {e}")
        raise HTTPException(status_code=500, detail=str(e))

if __name__ == "__main__":
    import uvicorn
    port = int(os.getenv("PORT", 8000))
    uvicorn.run(app, host="0.0.0.0", port=port)