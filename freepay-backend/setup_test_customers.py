#!/usr/bin/env python3
"""
Setup script to create test customers for FreePay demo
Run this once to create Alice (payer) and Bob (merchant) customers
"""

import asyncio
import os
from dotenv import load_dotenv
from app.fern_client import FernClient

load_dotenv()

async def setup_customers():
    """Create test customers for the demo"""
    try:
        fern = FernClient()
        
        print("[SETUP] Setting up FreePay demo customers...")
        
        # Create Customer A (Alice - the payer)
        print("\n[ALICE] Creating Alice (Customer/Payer)...")
        alice_result = await fern.create_customer(
            name="Alice Johnson",
            email="alice.freepay.demo@example.com"
        )
        
        alice_id = None
        if alice_result:
            alice_id = alice_result.get("customerId") or alice_result.get("id") or alice_result.get("customer_id")
            print(f"[SUCCESS] Alice created with ID: {alice_id}")
        
        # Create Customer B (Bob - the merchant)
        print("\n[BOB] Creating Bob (Merchant/Receiver)...")
        bob_result = await fern.create_customer(
            name="Bob's Coffee Shop",
            email="bob.freepay.demo@example.com"
        )
        
        bob_id = None
        if bob_result:
            bob_id = bob_result.get("customerId") or bob_result.get("id") or bob_result.get("customer_id")
            print(f"[SUCCESS] Bob created with ID: {bob_id}")
        
        # Update .env file with customer IDs
        if alice_id and bob_id:
            print(f"\n[CONFIG] Update your .env file with these customer IDs:")
            print(f"CUSTOMER_A_ID={alice_id}")
            print(f"CUSTOMER_B_ID={bob_id}")
            
            # Try to update .env automatically
            try:
                env_file = ".env"
                with open(env_file, "r") as f:
                    content = f.read()
                
                # Replace or add customer IDs
                if "CUSTOMER_A_ID=" in content:
                    content = content.replace(f"CUSTOMER_A_ID=customer_alice_id_here", f"CUSTOMER_A_ID={alice_id}")
                else:
                    content += f"\nCUSTOMER_A_ID={alice_id}"
                
                if "CUSTOMER_B_ID=" in content:
                    content = content.replace(f"CUSTOMER_B_ID=customer_bob_id_here", f"CUSTOMER_B_ID={bob_id}")
                else:
                    content += f"\nCUSTOMER_B_ID={bob_id}"
                
                with open(env_file, "w") as f:
                    f.write(content)
                
                print(f"[SUCCESS] .env file updated automatically!")
                
            except Exception as e:
                print(f"[WARNING] Could not update .env automatically: {e}")
                print("Please update manually.")
        
        print("\n[COMPLETE] Customer setup complete!")
        print("\nNext steps:")
        print("1. Restart your backend server")
        print("2. Test the /health endpoint")
        print("3. Test a payment flow")
        
    except Exception as e:
        print(f"[ERROR] Error setting up customers: {e}")
        print("\nTroubleshooting:")
        print("1. Check your Fern API key is correct")
        print("2. Verify you have internet connection")
        print("3. Check if you need to verify your Fern account")

if __name__ == "__main__":
    asyncio.run(setup_customers())