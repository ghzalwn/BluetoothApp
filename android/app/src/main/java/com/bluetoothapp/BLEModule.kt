package com.bluetoothapp

import android.Manifest.*
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.WritableMap
import com.facebook.react.modules.core.DeviceEventManagerModule

class BLEModule(reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext),
    ActivityCompat.OnRequestPermissionsResultCallback {

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var scanner: BluetoothLeScanner? = null
    private val peripherals = mutableMapOf<String, WritableMap>()
    private var permissionPromise: Promise? = null
    private val REQUEST_CODE_PERMISSIONS = 1
    private val REQUEST_ENABLE_BT = 1
    private val REQUEST_ENABLE_GPS = 1

    init {
        val bluetoothManager =
            reactContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter
        scanner = bluetoothAdapter?.bluetoothLeScanner

        if (bluetoothAdapter == null) {
            Log.e("BLEModule", "Bluetooth is not available on this device.")
        }
    }

    override fun getName(): String = "BLEModule"

    private fun hasPermissions(permissions: Array<String>): Boolean {
        return permissions.all {
            ContextCompat.checkSelfPermission(
                reactApplicationContext,
                it
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions(permissions: Array<String>, promise: Promise) {
        val activity: Activity = currentActivity ?: run {
            promise.reject("BLE_ERROR", "Current Activity is null")
            return
        }

        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(
                reactApplicationContext,
                it
            ) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (permissionsToRequest.isEmpty()) {
            promise.resolve("All permissions already granted")
            return
        }

        permissionPromise = promise
        ActivityCompat.requestPermissions(activity, permissionsToRequest, REQUEST_CODE_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }

            if (allGranted) {
                Log.d("BLEModule", "All permissions granted")
                permissionPromise?.resolve("All permissions granted")
            } else {
                val deniedPermissions = permissions.filterIndexed { index, _ ->
                    grantResults[index] != PackageManager.PERMISSION_GRANTED
                }
                Log.e("BLEModule", "Permissions denied: $deniedPermissions")
                permissionPromise?.reject(
                    "PERMISSION_DENIED",
                    "Permissions denied: $deniedPermissions"
                )
            }

            permissionPromise = null
        }
    }

    @ReactMethod
    fun startScan(promise: Promise) {
        Log.d("BLEModule", "startScan called")
        val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12 (API 31) and above: BLUETOOTH_SCAN permission
            arrayOf(
                permission.BLUETOOTH,
                permission.BLUETOOTH_ADMIN,
                permission.BLUETOOTH_SCAN
            )
        } else {
            // Android 11 (API 30) and below: ACCESS_FINE_LOCATION permission
            arrayOf(
                permission.BLUETOOTH,
                permission.BLUETOOTH_ADMIN,
                permission.ACCESS_FINE_LOCATION
            )
        }

        if (!hasPermissions(requiredPermissions)) {
            Log.d("BLEModule", "Permissions not granted. Requesting permissions.")
            requestPermissions(requiredPermissions, promise)
            return
        }

        try {

            val locationManager = currentActivity?.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                Log.d("BLEModule", "GPS is not enabled, requesting to turn it on.")
                val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                currentActivity?.startActivityForResult(intent, REQUEST_ENABLE_GPS)

                promise.reject("GPS_NOT_ENABLED", "GPS is not enabled. Please enable GPS.")
                return
            }
            // Check Bluetooth is enabled and request to enable it if not
            if (bluetoothAdapter?.isEnabled == false) {
                // Bluetooth is off, prompt the user to turn it on
                val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                currentActivity?.startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
                return
            }

            if (bluetoothAdapter == null || scanner == null) {
                promise.reject("BLE_ERROR", "Bluetooth is not available on this device.")
                return
            }

            peripherals.clear()
            sendEvent("onScanStart", null)

            val scanSettings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY) // Faster scan mode
                .setReportDelay(0) // Immediate results
                .build()


            Log.d("BLEModule", "${scanner}")
            scanner?.startScan(null, scanSettings, scanCallback)
            promise.resolve("Scanning started")

        } catch (e: SecurityException) {
            promise.reject("BLE_SECURITY_EXCEPTION", "Permission denied: ${e.message}")
        } catch (e: Exception) {
            promise.reject("BLE_UNKNOWN_ERROR", "An error occurred: ${e.message}")
        }
    }

    @ReactMethod
    fun stopScan(promise: Promise) {
        try {
            if (scanner == null) {
                promise.reject(
                    "BLE_ERROR",
                    "Scanner is not available. Ensure Bluetooth is enabled."
                )
                return
            }

            scanner?.stopScan(scanCallback)
            sendEvent("onScanStop", null)
            promise.resolve("Scanning stopped")
        } catch (e: SecurityException) {
            promise.reject("BLE_SECURITY_EXCEPTION", "Permission denied: ${e.message}")
        } catch (e: Exception) {
            promise.reject(
                "BLE_UNKNOWN_ERROR",
                "An error occurred while stopping the scan: ${e.message}"
            )
        }
    }

    @ReactMethod
    fun getDiscoveredPeripherals(promise: Promise) {
        val peripheralsArray = Arguments.createArray()
        peripherals.values.forEach { peripheralsArray.pushMap(it) }
        promise.resolve(peripheralsArray)
    }

    private val scanCallback = object : ScanCallback() {

        override fun onScanResult(callbackType: Int, result: ScanResult) {
            Log.d("BLEModule", "Scan result onScanResult : $result")

            try {

                val device = result.device
                val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // Android 12 (API 31) and above: BLUETOOTH_SCAN permission
                    arrayOf(
                        permission.BLUETOOTH,
                        permission.BLUETOOTH_ADMIN,
                        permission.BLUETOOTH_SCAN,
                        permission.ACCESS_FINE_LOCATION
                    )
                } else {
                    // Android 11 (API 30) and below: ACCESS_FINE_LOCATION permission
                    arrayOf(
                        permission.BLUETOOTH,
                        permission.BLUETOOTH_ADMIN,
                        permission.ACCESS_FINE_LOCATION
                    )
                }
                if (!hasPermissions(requiredPermissions)) {
                    Log.e("BLEModule", "Permissions not granted for Bluetooth access")
                    return
                }

                Log.d("BLEModule", "Discovered peripheral: $device")
                if (device.address.isNullOrEmpty()) return

                val peripheral = Arguments.createMap().apply {
                    putString("id", device.address)
                    putString("name", device.name ?: "Unknown")
                    putInt("rssi", result.rssi)
                    putString(
                        "advertisementData",
                        result.scanRecord?.bytes?.joinToString(",") { "%02x".format(it) }
                    )
                }

                peripherals[device.address] = peripheral
                Log.d("BLEModule", "Discovered peripheral: $peripheral")
                sendEvent("onPeripheralDiscovered", peripheral)
            } catch (e: SecurityException) {
                Log.e("BLEModule", "Permission denied: ${e.message}", e)
            } catch (e: Exception) {
                Log.e("BLEModule", "Error processing scan result: ${e.message}", e)
            }
        }

        override fun onBatchScanResults(results: List<ScanResult>) {
            Log.d("BLEModule", "Scan result onBatchScanResults : $results")

            for (result in results) {
                onScanResult(ScanSettings.CALLBACK_TYPE_FIRST_MATCH, result)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            val errorEvent = Arguments.createMap().apply {
                putInt("errorCode", errorCode)
                putString("message", getScanErrorMessage(errorCode))
            }

            Log.d("BLEModule", "Scan failed with error: $errorEvent")
            sendEvent("onScanFailed", errorEvent)
        }

        private fun getScanErrorMessage(errorCode: Int): String {
            return when (errorCode) {
                ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "Scan already started."
                ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "Application registration failed."
                ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "Internal error occurred."
                ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "Feature unsupported."
                else -> "Unknown scan failure."
            }
        }
    }

    private fun sendEvent(eventName: String, params: WritableMap?) {
        reactApplicationContext
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            .emit(eventName, params)
    }
}
