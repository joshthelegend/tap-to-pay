# FreePay Backend

Crypto tap-to-pay backend with instant fiat settlement via Fern API.

## Quick Start

1. **Run the backend:**
   ```bash
   run.bat
   ```

2. **Set up test customers:**
   ```bash
   python setup_test_customers.py
   ```

3. **Test the API:**
   - Health check: http://localhost:8000/health
   - API docs: http://localhost:8000/docs

## Configuration

Update `.env` file with your API keys:
- ✅ Fern API key (already set)
- ⏳ Alchemy API key (get from alchemy.com)
- ⏳ Customer IDs (created by setup script)

## API Endpoints

- `POST /process-payment` - Main payment processing
- `GET /merchant/balance` - Get merchant balance  
- `POST /customers` - Create test customers
- `GET /health` - Health check

## Demo Flow

1. Customer taps merchant phone (NFC)
2. Android app calls `/process-payment` 
3. Backend converts crypto → USD via Fern
4. Merchant receives instant USD settlement
5. Dashboard shows updated balance

## Next Steps

1. ✅ Backend running
2. ⏳ Test Fern API connection
3. ⏳ Create test customers  
4. ⏳ Integrate with Android apps
5. ⏳ Deploy for demo