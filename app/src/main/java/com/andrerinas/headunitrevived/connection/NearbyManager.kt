package com.andrerinas.headunitrevived.connection

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.andrerinas.headunitrevived.utils.AppLog
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.Socket

/**
 * Manages Google Nearby Connections on the Headunit (Tablet).
 * The Tablet acts as a DISCOVERER only.
 */
class NearbyManager(private val context: Context, private val onSocketReady: (Socket) -> Unit) {

    data class DiscoveredEndpoint(val id: String, val name: String)

    companion object {
        private val _discoveredEndpoints = MutableStateFlow<List<DiscoveredEndpoint>>(emptyList())
        val discoveredEndpoints: StateFlow<List<DiscoveredEndpoint>> = _discoveredEndpoints
    }

    private val connectionsClient = Nearby.getConnectionsClient(context)
    private val SERVICE_ID = "com.andrerinas.headunitrevived.NEARBY"
    private var isRunning = false
    private var activeNearbySocket: NearbySocket? = null

    fun start() {
        if (!hasRequiredPermissions()) {
            AppLog.w("NearbyManager: Missing required location/bluetooth permissions. Skipping start.")
            return
        }
        if (isRunning) {
            AppLog.i("NearbyManager: Already running discovery.")
            return
        }
        AppLog.i("NearbyManager: Starting Nearby (Discoverer only)...")
        isRunning = true
        _discoveredEndpoints.value = emptyList()
        startDiscovery()
    }

    private fun hasRequiredPermissions(): Boolean {
        val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasCoarse && !hasFine) return false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val hasAdvertise = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
            val hasScan = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
            val hasConnect = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            if (!hasAdvertise || !hasScan || !hasConnect) return false
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasNearby = ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
            if (!hasNearby) return false
        }

        return true
    }

    fun stop() {
        if (!isRunning) return
        AppLog.i("NearbyManager: Stopping discovery...")
        isRunning = false
        connectionsClient.stopDiscovery()
        connectionsClient.stopAllEndpoints()
        _discoveredEndpoints.value = emptyList()
    }

    /**
     * Manually initiate a connection to a specific discovered endpoint.
     * Called from HomeFragment when user taps a device in the list.
     */
    fun connectToEndpoint(endpointId: String) {
        AppLog.i("NearbyManager: Requesting connection to $endpointId...")
        connectionsClient.requestConnection(android.os.Build.MODEL, endpointId, connectionLifecycleCallback)
            .addOnFailureListener { e -> AppLog.e("NearbyManager: Failed to request connection: ${e.message}") }
    }

    private fun startDiscovery() {
        val discoveryOptions = DiscoveryOptions.Builder()
            .setStrategy(Strategy.P2P_POINT_TO_POINT)
            .build()

        AppLog.i("NearbyManager: Requesting Discovery with SERVICE_ID: $SERVICE_ID")
        connectionsClient.startDiscovery(SERVICE_ID, endpointDiscoveryCallback, discoveryOptions)
            .addOnSuccessListener { AppLog.d("NearbyManager: [OK] Discovery started.") }
            .addOnFailureListener { e -> 
                AppLog.e("NearbyManager: [ERROR] Discovery failed: ${e.message}") 
                isRunning = false
            }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            AppLog.i("NearbyManager: Endpoint FOUND: ${info.endpointName} ($endpointId)")
            val current = _discoveredEndpoints.value.toMutableList()
            if (current.none { it.id == endpointId }) {
                current.add(DiscoveredEndpoint(endpointId, info.endpointName))
                _discoveredEndpoints.value = current
            }
        }

        override fun onEndpointLost(endpointId: String) {
            AppLog.i("NearbyManager: Endpoint LOST: $endpointId")
            val current = _discoveredEndpoints.value.toMutableList()
            current.removeAll { it.id == endpointId }
            _discoveredEndpoints.value = current
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            AppLog.i("NearbyManager: Connection INITIATED with $endpointId (${info.endpointName}). Token: ${info.authenticationToken}")
            AppLog.i("NearbyManager: Automatically ACCEPTING connection...")
            connectionsClient.acceptConnection(endpointId, payloadCallback)
                .addOnFailureListener { e -> AppLog.e("NearbyManager: Failed to accept connection: ${e.message}") }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            val status = result.status
            AppLog.i("NearbyManager: Connection RESULT for $endpointId: StatusCode=${status.statusCode} (${status.statusMessage})")
            
            when (status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    AppLog.i("NearbyManager: Successfully CONNECTED to $endpointId. Establishing Stream Tunnel...")
                    val socket = NearbySocket()
                    activeNearbySocket = socket
                    
                    val pipes = android.os.ParcelFileDescriptor.createPipe()
                    socket.outputStreamWrapper = android.os.ParcelFileDescriptor.AutoCloseOutputStream(pipes[1])
                    val streamPayload = Payload.fromStream(android.os.ParcelFileDescriptor.AutoCloseInputStream(pipes[0]))
                    connectionsClient.sendPayload(endpointId, streamPayload)
                    
                    onSocketReady(socket)
                }
                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> AppLog.w("NearbyManager: Connection REJECTED by $endpointId")
                ConnectionsStatusCodes.STATUS_ERROR -> AppLog.e("NearbyManager: Connection ERROR with $endpointId")
                else -> AppLog.w("NearbyManager: Unknown connection result code: ${status.statusCode}")
            }
        }

        override fun onDisconnected(endpointId: String) {
            AppLog.i("NearbyManager: DISCONNECTED from $endpointId")
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.STREAM) {
                AppLog.i("NearbyManager: Received STREAM payload from $endpointId")
                activeNearbySocket?.inputStreamWrapper = payload.asStream()?.asInputStream()
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }
}
