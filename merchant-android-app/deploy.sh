#!/bin/bash

# FreePay Android POS Deployment Script

echo "Deploying FreePay POS to Multzo device..."

# Check if device is connected
adb devices | grep -q "device$"
if [ $? -ne 0 ]; then
    echo "Error: No Android device connected"
    echo "Please connect your Multzo POS device and enable USB debugging"
    exit 1
fi

# Build the app first
./build.sh
if [ $? -ne 0 ]; then
    echo "Build failed, aborting deployment"
    exit 1
fi

# Uninstall previous version (if exists)
echo "Uninstalling previous version..."
adb uninstall com.freepay.pos 2>/dev/null

# Install new APK
echo "Installing new APK..."
adb install -r app/build/outputs/apk/debug/app-debug.apk

if [ $? -eq 0 ]; then
    echo "Installation successful!"
    
    # Launch the app
    echo "Launching FreePay POS..."
    adb shell am start -n com.freepay.pos/.MainActivity
    
    # Set as launcher (for kiosk mode)
    echo "Setting as default launcher..."
    adb shell cmd package set-home-activity com.freepay.pos/.MainActivity
    
    echo "Deployment complete!"
else
    echo "Installation failed!"
    exit 1
fi