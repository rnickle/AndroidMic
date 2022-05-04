package com.example.microphone.streaming

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.microphone.audio.AudioBuffer
import com.example.microphone.ignore
import kotlinx.coroutines.*
import java.io.*
import java.lang.Exception
import java.net.InetSocketAddress
import java.util.*

class BluetoothStreamer(private val ctx : Context) : Streamer {
    private val TAG : String = "MicStreamBTH"

    private val myUUID : UUID = UUID.fromString("34335e34-bccf-11eb-8529-0242ac130003")
    private val MAX_WAIT_TIME = 1500L // timeout

    private val adapter : BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var target : BluetoothDevice? = null
    private var socket : BluetoothSocket? = null

    private val receiver = object : BroadcastReceiver()
    {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            // check if server side is disconnected
            if(BluetoothAdapter.ACTION_STATE_CHANGED == action)
            {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                if(state == BluetoothAdapter.STATE_TURNING_OFF)
                    disconnect()
            }
            else if(BluetoothDevice.ACTION_ACL_DISCONNECTED == action)
                disconnect()
            else if(BluetoothDevice.ACTION_ACL_DISCONNECT_REQUESTED == action)
                disconnect()
        }
    }

    // init everything
    init
    {
        // check bluetooth adapter
        require(adapter != null) {"Bluetooth adapter is not found"}
        // check permission
        require(ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED){
            "Bluetooth is not permitted"
        }
        require(adapter.isEnabled){"Bluetooth adapter is not enabled"}
        // set target device
        selectTargetDevice()
        require(target != null) {"Cannot find target PC in paired device list"}
        // set up filters
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECT_REQUESTED)
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        ctx.registerReceiver(receiver, filter)
    }

    // connect to target device
    override fun connect() : Boolean
    {
        // create socket
        socket = try {
            target?.createInsecureRfcommSocketToServiceRecord(myUUID)
        } catch (e : IOException) {
            Log.d(TAG, "connect [createInsecureRfcommSocketToServiceRecord]: ${e.message}")
            null
        } ?: return false
        // connect to server
        try {
            socket?.connect()
        } catch (e : IOException){
            Log.d(TAG, "connect [connect]: ${e.message}")
            return false
        }
        Log.d(TAG, "connect: connected")
        return true
    }

    // stream data through socket
    override suspend fun stream(audioBuffer : AudioBuffer) = withContext(Dispatchers.IO)
    {
        if(socket == null || socket?.isConnected != true) return@withContext
        val data = audioBuffer.poll() ?: return@withContext
        try {
            val streamOut = socket!!.outputStream
            streamOut.write(data)
            streamOut.flush()
        } catch (e : IOException)
        {
            Log.d(TAG, "stream: ${e.message}")
            delay(5)
            disconnect()
        } catch (e : Exception)
        {
            Log.d(TAG, "stream: ${e.message}")
        }
    }

    // disconnect from target device
    override fun disconnect() : Boolean
    {
        if(socket == null) return false
        try {
            socket?.close()
        } catch(e : IOException) {
            Log.d(TAG, "disconnect [close]: ${e.message}")
            socket = null
            return false
        }
        socket = null
        Log.d(TAG, "disconnect: complete")
        return true
    }

    // shutdown streamer
    override fun shutdown()
    {
        disconnect()
        ignore { ctx.unregisterReceiver(receiver) }
    }

    // auto select target PC device from a bounded devices list
    private fun selectTargetDevice()
    {
        target = null
        val pairedDevices = adapter?.bondedDevices ?: return
        for(device in pairedDevices)
        {
            if(device.bluetoothClass.majorDeviceClass == BluetoothClass.Device.Major.COMPUTER)
            {
                Log.d(TAG, "selectTargetDevice: testing ${device.name}")
                if(testConnection(device))
                {
                    target = device
                    Log.d(TAG, "selectTargetDevice: ${device.name} is valid")
                    break
                }
                else
                    Log.d(TAG, "selectTargetDevice: ${device.name} is invalid")
            }
        }
    }

    // test connection with a device
    // return true if valid device
    // return false if invalid device
    private fun testConnection(device : BluetoothDevice) : Boolean
    {
        // get socket from device
        val socket : BluetoothSocket = try {
            device.createInsecureRfcommSocketToServiceRecord(myUUID)
        } catch (e : IOException) {
            Log.d(TAG, "testConnection [createInsecureRfcommSocketToServiceRecord]: ${e.message}")
            null
        } ?: return false
        // try to connect
        try {
            socket.connect()
        } catch (e : IOException){
            Log.d(TAG, "testConnection [connect]: ${e.message}")
            return false
        }
        var isValid = false
        runBlocking(Dispatchers.IO) {
            val job = launch {
                ignore {
                    val streamOut = DataOutputStream(socket.outputStream)
                    streamOut.write(Streamer.DEVICE_CHECK.toByteArray(Charsets.UTF_8))
                    streamOut.flush()
                    val streamIn = DataInputStream(socket.inputStream)
                    val buffer = ByteArray(100)
                    val size = streamIn.read(buffer, 0, 100)
                    val received = String(buffer, 0, size, Charsets.UTF_8)
                    Log.d(TAG, "testConnection: received $received")
                    if(received == Streamer.DEVICE_CHECK_EXPECT)
                    {
                        isValid = true
                        Log.d(TAG, "testConnection: device matched!")
                    }
                    else
                        Log.d(TAG, "testConnection: device mismatch with $received!")
                }
            }
            var time = 5
            while(!job.isCompleted && time < MAX_WAIT_TIME)
            {
                delay(5)
                time += 5
            }
            if(!job.isCompleted)
            {
                ignore { socket.close() }
                job.cancelAndJoin()
                Log.d(TAG, "testConnection: timeout!")
            }
        }
        // close socket
        ignore { socket.close() }
        return isValid
    }

    // get connected device information
    override fun getInfo() : String
    {
        if(adapter == null || target == null || socket == null) return ""
        return "[Device Name] ${target?.name}\n[Device Address] ${target?.address}"
    }

    // return true if is connected for streaming
    override fun isAlive() : Boolean
    {
        return (socket != null && socket?.isConnected == true)
    }

    override fun updateAddress(address: InetSocketAddress) {
        // ignore
    }
}