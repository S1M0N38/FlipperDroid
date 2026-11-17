package com.example.flipperdroid.viewmodel

import android.content.Context
import android.hardware.usb.*
import android.util.Log
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

/**
 * ViewModel handling the connection and interaction with a USB device in keyboard mode
 *
 * This class detects whether USB Host mode is available and handles the connection to a USB HID keyboard device
 * It lets the phone act as a keyboard over USB to run a script
 *
 */
class BadUsbViewModel : ViewModel() {

    private var hasHostFeature = false
    private val _isUsbHostAvailable = mutableStateOf(false)

    /*
    *  Lets you see whether usb is available
     */
    val isUsbHostAvailable: State<Boolean> = _isUsbHostAvailable

    private val _isConnected = mutableStateOf(false)
    // test
    val isConnected: State<Boolean> = _isConnected

    private val _currentScript = MutableStateFlow<String>("")
    val currentScript: StateFlow<String> = _currentScript

    private val _status = MutableStateFlow<String>("")
    val status: StateFlow<String> = _status

    private var usbManager: UsbManager? = null
    private var usbDevice: UsbDevice? = null
    private var usbInterface: UsbInterface? = null
    private var usbEndpoint: UsbEndpoint? = null
    private var usbConnection: UsbDeviceConnection? = null

    // HID Keyboard modifiers
    private val modifierNone = 0x00
    private val modfiferLeftShift = 0x02
    /**
     * Initializes the viewmodel with the context and prepares the USB manager
     *
     * @param context application context needed to access the USB service
     */
    fun initialize(context: Context) {
        usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
        // The USB service also exists on devices without host mode: we test the feature.
        hasHostFeature = context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_USB_HOST)
        checkUsbHostSupport()
    }
    /**
     * Checks whether USB Host mode is available on the device
     *
     * Updates the isUsbHostAvailable state and the associated status
     */
    private fun checkUsbHostSupport() {
        _isUsbHostAvailable.value = usbManager != null && hasHostFeature
        _status.value = if (!_isUsbHostAvailable.value) "USB Host not supported on this device"
            else "Experimental USB-host mode: the phone is the USB host here, so it cannot type on a PC. Use BadUSB (root) for real keystroke injection."
    }
    /**
     * Connects the provided USB device and prepares the HID keyboard interface
     *
     * Looks for the HID interface and the write endpoint to send the data
     * Updates the isConnected state and the status depending on the connection outcome
     *
     * @param device detected USB device
     * @param connection USB connection established with the device
     */
    fun connectUsb(device: UsbDevice, connection: UsbDeviceConnection) {
        usbDevice = device
        usbConnection = connection

        // Find HID interface
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass == UsbConstants.USB_CLASS_HID) {
                usbInterface = intf
                break
            }
        }

        // Find endpoint
        usbInterface?.let { intf ->
            for (i in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(i)
                if (ep.direction == UsbConstants.USB_DIR_OUT) {
                    usbEndpoint = ep
                    break
                }
            }
        }

        if (usbInterface != null && usbEndpoint != null) {
            if (connection.claimInterface(usbInterface, true)) {
                _isConnected.value = true
                _status.value = "Claimed HID interface of the attached device (host mode — not a keyboard emulation)"
            } else {
                _status.value = "Could not claim the USB interface"
            }
        } else {
            _status.value = "Failed to initialize USB HID interface"
        }
    }
    /**
     * Disconnects the USB device and releases the resources
     *
     * Updates the isConnected state and the status
     */
    fun disconnect() {
        usbConnection?.let { connection ->
            usbInterface?.let { intf ->
                connection.releaseInterface(intf)
            }
            connection.close()
        }
        _isConnected.value = false
        usbConnection = null
        usbInterface = null
        usbEndpoint = null
        _status.value = "Disconnected"
    }
    /**
     * Updates the script to execute
     *
     * @param script string representing the script
     */
    fun updateScript(script: String) {
        _currentScript.value = script
    }

    /**
     * Executes the script by sending the characters one by one over USB
     *
     * This method only works if the device is connected in USB keyboard mode
     * It sends each character with a 50 ms delay between keystrokes
     * Updates the status with the result of the execution
     */
    fun executeScript() {
        // USB transfers are blocking: run on an IO thread
        // and use delay() (non-blocking) instead of Thread.sleep().
        viewModelScope.launch(Dispatchers.IO) {
            if (!_isConnected.value) {
                _status.value = "Not connected as USB keyboard"
                return@launch
            }

            try {
                val script = _currentScript.value
                _status.value = "Executing script..."

                script.forEach { char ->
                    sendKey(char)
                    // Small delay between keystrokes
                    delay(50)
                }

                _status.value = "Script sent to the attached USB device"
            } catch (e: Exception) {
                _status.value = "Error executing script: ${e.message}"
                Log.e("BadUsbViewModel", "Error executing script", e)
            }
        }
    }

    /**
     * Sends a USB keyboard key matching the given character
     *
     * Computes the modifier and the keycode from the HID table
     *
     * @param char character to send
     */
    private fun sendKey(char: Char) {
        val (modifier, keycode) = when (char) {
            in 'A'..'Z' -> Pair(modfiferLeftShift, char.lowercaseChar().toKeycode())
            in 'a'..'z' -> Pair(modifierNone, char.toKeycode())
            in '0'..'9' -> Pair(modifierNone, char.toKeycode())
            ' ' -> Pair(modifierNone, 0x2C) // Spacebar
            '\n' -> Pair(modifierNone, 0x28) // Enter
            '\t' -> Pair(modifierNone, 0x2B) // Tab
            '.' -> Pair(modifierNone, 0x37)
            ',' -> Pair(modifierNone, 0x36)
            '-' -> Pair(modifierNone, 0x2D)
            '_' -> Pair(modfiferLeftShift, 0x2D)
            '=' -> Pair(modifierNone, 0x2E)
            '+' -> Pair(modfiferLeftShift, 0x2E)
            ';' -> Pair(modifierNone, 0x33)
            ':' -> Pair(modfiferLeftShift, 0x33)
            '\'' -> Pair(modifierNone, 0x34)
            '"' -> Pair(modfiferLeftShift, 0x34)
            '/' -> Pair(modifierNone, 0x38)
            '?' -> Pair(modfiferLeftShift, 0x38)
            '!' -> Pair(modfiferLeftShift, 0x1E)
            '@' -> Pair(modfiferLeftShift, 0x1F)
            '#' -> Pair(modfiferLeftShift, 0x20)
            '$' -> Pair(modfiferLeftShift, 0x21)
            '%' -> Pair(modfiferLeftShift, 0x22)
            '^' -> Pair(modfiferLeftShift, 0x23)
            '&' -> Pair(modfiferLeftShift, 0x24)
            '*' -> Pair(modfiferLeftShift, 0x25)
            '(' -> Pair(modfiferLeftShift, 0x26)
            ')' -> Pair(modfiferLeftShift, 0x27)
            else -> Pair(modifierNone, 0x00)
        }

        val buffer = ByteBuffer.allocate(8)
        buffer.put(modifier.toByte()) // Modifier
        buffer.put(0.toByte())        // Reserved
        buffer.put(keycode.toByte())  // Keycode
        buffer.put(ByteArray(5))      // Padding

        val sent = usbConnection?.bulkTransfer(
            usbEndpoint,
            buffer.array(),
            buffer.capacity(),
            1000
        ) ?: -1
        // Verified result: previously a failure was announced as "Script executed successfully".
        if (sent < 0) throw java.io.IOException("USB transfer failed")

        // Send key release
        val releaseBuffer = ByteBuffer.allocate(8)
        releaseBuffer.put(ByteArray(8))
        usbConnection?.bulkTransfer(
            usbEndpoint,
            releaseBuffer.array(),
            releaseBuffer.capacity(),
            1000
        )
    }

    /**
     * Converts a character into a USB HID keycode
     *
     * @return USB HID code matching the character
     */
    private fun Char.toKeycode(): Int {
        return when (this) {
            in 'a'..'z' -> this.code - 'a'.code + 4
            in '0'..'9' -> when (this) {
                '0' -> 0x27
                else -> this.code - '1'.code + 0x1E
            }
            else -> 0x00
        }
    }
} 