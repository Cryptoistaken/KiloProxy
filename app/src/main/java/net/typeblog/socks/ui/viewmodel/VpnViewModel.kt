package net.typeblog.socks.ui.viewmodel

import android.app.Application
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.typeblog.socks.IVpnService
import net.typeblog.socks.SocksVpnService
import net.typeblog.socks.util.ProfileManager
import net.typeblog.socks.util.Utility
import net.typeblog.socks.util.Constants.ACTION_VPN_STATE_CHANGED
import net.typeblog.socks.util.Constants.VPN_STATE_AS_NAME
import net.typeblog.socks.util.Constants.VPN_STATE_CITY
import net.typeblog.socks.util.Constants.VPN_STATE_CONNECTED
import net.typeblog.socks.util.Constants.VPN_STATE_CONNECTED_SINCE
import net.typeblog.socks.util.Constants.VPN_STATE_COUNTRY
import net.typeblog.socks.util.Constants.VPN_STATE_COUNTRY_CODE
import net.typeblog.socks.util.Constants.VPN_STATE_ERROR
import net.typeblog.socks.util.Constants.VPN_STATE_IP
import net.typeblog.socks.util.Constants.VPN_STATE_ISP
import net.typeblog.socks.util.Constants.VPN_STATE_ORG
import net.typeblog.socks.util.Constants.VPN_STATE_PROFILE
import net.typeblog.socks.util.Constants.VPN_STATE_RECEIVED
import net.typeblog.socks.util.Constants.VPN_STATE_REGION
import net.typeblog.socks.util.Constants.VPN_STATE_RUNNING
import net.typeblog.socks.util.Constants.VPN_STATE_SENT
import net.typeblog.socks.util.Constants.VPN_STATE_TIMEZONE
import net.typeblog.socks.util.Constants.VPN_STATE_VERIFIED

class VpnViewModel(application: Application) : AndroidViewModel(application) {

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _currentIp = MutableStateFlow<String?>(null)
    val currentIp: StateFlow<String?> = _currentIp.asStateFlow()

    private val _countryCode = MutableStateFlow<String?>(null)
    val countryCode: StateFlow<String?> = _countryCode.asStateFlow()

    private val _country = MutableStateFlow<String?>(null)
    val country: StateFlow<String?> = _country.asStateFlow()

    private val _region = MutableStateFlow<String?>(null)
    val region: StateFlow<String?> = _region.asStateFlow()

    private val _city = MutableStateFlow<String?>(null)
    val city: StateFlow<String?> = _city.asStateFlow()

    private val _isp = MutableStateFlow<String?>(null)
    val isp: StateFlow<String?> = _isp.asStateFlow()

    private val _org = MutableStateFlow<String?>(null)
    val org: StateFlow<String?> = _org.asStateFlow()

    private val _asName = MutableStateFlow<String?>(null)
    val asName: StateFlow<String?> = _asName.asStateFlow()

    private val _timezone = MutableStateFlow<String?>(null)
    val timezone: StateFlow<String?> = _timezone.asStateFlow()

    private val _receivedBytes = MutableStateFlow(0L)
    val receivedBytes: StateFlow<Long> = _receivedBytes.asStateFlow()

    private val _sentBytes = MutableStateFlow(0L)
    val sentBytes: StateFlow<Long> = _sentBytes.asStateFlow()

    private val _connectedSince = MutableStateFlow(0L)
    val connectedSince: StateFlow<Long> = _connectedSince.asStateFlow()

    private val _proxyVerified = MutableStateFlow(false)
    val proxyVerified: StateFlow<Boolean> = _proxyVerified.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _profiles = MutableStateFlow<List<String>>(emptyList())
    val profiles: StateFlow<List<String>> = _profiles.asStateFlow()

    private val _profileVersion = MutableStateFlow(0L)
    val profileVersion: StateFlow<Long> = _profileVersion.asStateFlow()

    private val _activeProfileName = MutableStateFlow<String?>(null)
    val activeProfileName: StateFlow<String?> = _activeProfileName.asStateFlow()

    // Profile whose session totals _receivedBytes/_sentBytes currently belong
    // to. Unlike activeProfileName it survives a disconnect so the profiles
    // cards can keep showing the last session's usage instead of a stale prefs
    // read (prefs are written by the :vpn process, so the UI process's in-memory
    // copy is stale after a disconnect).
    private val _lastProfileName = MutableStateFlow<String?>(null)
    val lastProfileName: StateFlow<String?> = _lastProfileName.asStateFlow()

    // Set while a connect was requested by the user but the tunnel is not up
    // yet (VPN-permission dialog / service bring-up). Only covers the window
    // where the service is not running; once it runs, the derived state below
    // is authoritative. Cleared on connect, disconnect, error, or timeout so a
    // stale request can never strand the UI on a disabled "Connecting…" button.
    private val _connectRequested = MutableStateFlow(false)

    /**
     * Connecting state derived from the live service, mirroring the floating
     * bubble's CONNECTING logic (`running && !verified`). Both surfaces read
     * the same remote state, so the app's connect button and the bubble always
     * agree regardless of which one initiated the connect.
     */
    val isConnecting: StateFlow<Boolean> = combine(
        _isRunning, _isConnected, _connectRequested
    ) { running, connected, requested ->
        (running && !connected) || (requested && !running)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private var _pendingProfile = MutableStateFlow<String?>(null)

    private var vpnService: IVpnService? = null
    private var bound = false
    private var rebinding = false
    private var cleared = false
    private val rebindHandler = Handler(Looper.getMainLooper())
    private var rebindRunnable: Runnable? = null
    private var rebindAttempts = 0

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_VPN_STATE_CHANGED) return
            viewModelScope.launch {
                syncState()
                intent.getStringExtra(VPN_STATE_ERROR)?.takeIf { it.isNotEmpty() }?.let {
                    _errorMessage.value = it
                }
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            vpnService = IVpnService.Stub.asInterface(service)
            bound = true
            rebinding = false
            rebindAttempts = 0
            rebindRunnable?.let { rebindHandler.removeCallbacks(it); rebindRunnable = null }
            // Re-sync live state so the UI never keeps stale "disconnected"
            // after the VPN process restarts and reconnects.
            viewModelScope.launch { syncState() }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            vpnService = null
            bound = false
            rebinding = false
            scheduleRebind()
        }

        override fun onBindingDied(name: ComponentName?) {
            vpnService = null
            bound = false
            rebinding = false
            scheduleRebind()
        }
    }

    init {
        Log.d("KiloProxyVM", "VpnViewModel init - instance ${hashCode()}")
        val app = getApplication<Application>()
        bindToService(app)
        val filter = IntentFilter(ACTION_VPN_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            app.registerReceiver(stateReceiver, filter)
        }
        loadProfiles(app)
        startPolling()
    }

    private fun bindToService(context: Context): Boolean {
        val intent = Intent(context, SocksVpnService::class.java)
        return try {
            val flags = Context.BIND_AUTO_CREATE or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Context.BIND_ABOVE_CLIENT else 0
            context.bindService(intent, serviceConnection, flags)
        } catch (_: Exception) {
            // Service not available or binding failed
            false
        }
    }

    // The :vpn process can be killed (OOM, crash) while our Activity is alive.
    // Rather than leaving the UI bound=false forever (which the poller reports
    // as disconnected), re-establish the connection so we can re-sync real state.
    private fun scheduleRebind() {
        if (cleared || rebinding) return
        rebinding = true
        Log.d("KiloProxyVM", "VPN service connection lost; rebinding")
        if (!bindToService(getApplication())) {
            rebinding = false
            rebindAttempts++
            val delayMs = when {
                rebindAttempts <= 3 -> 200L
                rebindAttempts <= 10 -> 1000L
                else -> 3000L
            }
            rebindRunnable?.let { rebindHandler.removeCallbacks(it) }
            val r = Runnable { if (!cleared) scheduleRebind() }
            rebindRunnable = r
            rebindHandler.postDelayed(r, delayMs)
        } else {
            // Bind initiated; arm watchdog in case onServiceConnected never arrives
            rebindRunnable?.let { rebindHandler.removeCallbacks(it) }
            val r = Runnable {
                if (!bound && !cleared) {
                    rebinding = false
                    scheduleRebind()
                }
            }
            rebindRunnable = r
            rebindHandler.postDelayed(r, 2000L)
        }
    }

    private fun loadProfiles(context: Context) {
        try {
            val pm = ProfileManager.getInstance(context)
            pm.reload()
            val allProfiles = pm.getProfiles().toList()
            // Skip first element (default placeholder), return only user-created profiles
            _profiles.value = allProfiles.drop(1)
            // Force consumers to re-read profile details even when the name list is unchanged
            _profileVersion.value += 1
            Log.d("KiloProxyVM", "loadProfiles: ${allProfiles.size} total, ${allProfiles.drop(1).size} user profiles")
        } catch (e: Exception) {
            Log.e("KiloProxyVM", "loadProfiles failed: ${e.message}")
            _profiles.value = emptyList()
        }
    }

    fun reloadProfiles(context: Context) {
        loadProfiles(context)
    }

    fun updateActiveProfileName(oldName: String, newName: String) {
        if (_activeProfileName.value == oldName) {
            _activeProfileName.value = newName
        }
    }

    private fun startPolling() {
        viewModelScope.launch {
            while (isActive) {
                if (bound && vpnService != null) {
                    syncState()
                } else {
                    clearState()
                }
                delay(if (_isRunning.value && !_proxyVerified.value) 200L else 1000L)
            }
        }
    }

    // Reads live state from the bound service. Called from the polling loop and
    // immediately after a (re)connect so the UI re-syncs when the service process
    // comes back instead of staying on a stale disconnected state.
    private suspend fun syncState() = withContext(Dispatchers.IO) {
        if (vpnService == null) return@withContext
        try {
            val state = vpnService!!.state
            val running = state.getBoolean(VPN_STATE_RUNNING)
            _isRunning.value = running
            _proxyVerified.value = state.getBoolean(VPN_STATE_VERIFIED)
            _isConnected.value = state.getBoolean(VPN_STATE_CONNECTED)
            val serviceError = state.getString(VPN_STATE_ERROR).orEmpty()
            if (serviceError.isNotEmpty()) {
                _errorMessage.value = serviceError
                // A service-reported failure ends any in-flight connect request.
                _connectRequested.value = false
            }
            if (running) {
                // Tunnel is up — the derived CONNECTING state takes over.
                _connectRequested.value = false
                _currentIp.value = state.getString(VPN_STATE_IP).orEmpty().ifEmpty { null }
                _countryCode.value = state.getString(VPN_STATE_COUNTRY_CODE).orEmpty().ifEmpty { null }
                _country.value = state.getString(VPN_STATE_COUNTRY).orEmpty().ifEmpty { null }
                _region.value = state.getString(VPN_STATE_REGION).orEmpty().ifEmpty { null }
                _city.value = state.getString(VPN_STATE_CITY).orEmpty().ifEmpty { null }
                _isp.value = state.getString(VPN_STATE_ISP).orEmpty().ifEmpty { null }
                _org.value = state.getString(VPN_STATE_ORG).orEmpty().ifEmpty { null }
                _asName.value = state.getString(VPN_STATE_AS_NAME).orEmpty().ifEmpty { null }
                _timezone.value = state.getString(VPN_STATE_TIMEZONE).orEmpty().ifEmpty { null }
                _receivedBytes.value = state.getLong(VPN_STATE_RECEIVED)
                _sentBytes.value = state.getLong(VPN_STATE_SENT)
                _connectedSince.value = state.getLong(VPN_STATE_CONNECTED_SINCE)
                // Always derive the live profile from the running service
                // (not just from VM-initiated starts), so the bubble /
                // notification / auto-start path keeps the correct card live.
                val runningProfile = state.getString(VPN_STATE_PROFILE).orEmpty()
                if (runningProfile.isNotEmpty()) {
                    _activeProfileName.value = runningProfile
                    _lastProfileName.value = runningProfile
                }
                if (serviceError.isEmpty()) _errorMessage.value = null
            } else {
                _currentIp.value = null
                _countryCode.value = null
                _country.value = null
                _region.value = null
                _city.value = null
                _isp.value = null
                _org.value = null
                _asName.value = null
                _timezone.value = null
                // Keep the last-known transfer totals so the usage card
                // does not drop to zero the moment the VPN disconnects.
                _connectedSince.value = 0L
                _proxyVerified.value = false
                _isConnected.value = false
                _activeProfileName.value = null
                if (serviceError.isEmpty()) _errorMessage.value = null
            }
        } catch (e: Exception) {
            clearState()
            _errorMessage.value = e.message ?: "VPN service error"
        }
    }

    private fun clearState() {
        _connectRequested.value = false
        _isRunning.value = false
        _currentIp.value = null
        _countryCode.value = null
        _country.value = null
        _region.value = null
        _city.value = null
        _isp.value = null
        _org.value = null
        _asName.value = null
        _timezone.value = null
        _connectedSince.value = 0L
        _proxyVerified.value = false
        _isConnected.value = false
        _activeProfileName.value = null
    }

    fun startVpn(context: Context, profileName: String) {
        Log.d("KiloProxyVM", "startVpn called for profile: $profileName")
        viewModelScope.launch {
            _connectRequested.value = true
            _errorMessage.value = null
            // Reset session totals so a different profile's connection does not
            // inherit the previous profile's last-known transfer counts.
            _receivedBytes.value = 0L
            _sentBytes.value = 0L
            try {
                val pm = ProfileManager.getInstance(context)
                val profile = pm.getProfile(profileName) ?: run {
                    Log.e("KiloProxyVM", "startVpn: profile not found: $profileName")
                    _errorMessage.value = "Profile not found: $profileName"
                    return@launch
                }
                Utility.startVpn(context, profile)
                _activeProfileName.value = profileName
                _lastProfileName.value = profileName
                pm.switchDefault(profileName)
                Log.d("KiloProxyVM", "startVpn succeeded for: $profileName")
            } catch (e: Exception) {
                Log.e("KiloProxyVM", "startVpn failed: ${e.message}")
                _errorMessage.value = "Failed to start VPN: ${e.message ?: "unknown error"}"
            }
        }
    }

    fun stopVpn(context: Context) {
        Log.d("KiloProxyVM", "stopVpn called")
        viewModelScope.launch {
            _connectRequested.value = false
            if (bound && vpnService != null) {
                try {
                    vpnService!!.stop()
                    _errorMessage.value = null
                    Log.d("KiloProxyVM", "stopVpn succeeded")
                } catch (e: Exception) {
                    Log.e("KiloProxyVM", "stopVpn failed: ${e.message}")
                    _errorMessage.value = "Failed to stop VPN: ${e.message ?: "unknown error"}"
                }
            } else {
                Log.w("KiloProxyVM", "stopVpn: service not bound")
            }
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    /** Cancel a connect request that has not produced a running tunnel yet. */
    fun cancelConnect() {
        _connectRequested.value = false
    }

    fun onConnectTimeout() {
        // Report a failure unless we've actually verified connectivity through the
        // proxy (currentIp populated by the service's proxy-routed IP check). Don't
        // clobber a more specific error the service already surfaced.
        if (_currentIp.value == null && _errorMessage.value == null) {
            _errorMessage.value = "Connection failed: proxy did not verify within 20 seconds. Check the proxy server and try again."
        }
    }

    fun restartVpn(context: Context) {
        val profileName = _activeProfileName.value ?: return
        if (!_isRunning.value) return
        Log.d("KiloProxyVM", "restartVpn called for profile: $profileName")
        viewModelScope.launch {
            try {
                if (bound && vpnService != null) vpnService!!.stop()
            } catch (_: Exception) {
            }
            val deadline = System.currentTimeMillis() + 5000
            while (_isRunning.value && System.currentTimeMillis() < deadline) {
                delay(150)
            }
            startVpn(context, profileName)
        }
    }

    /**
     * Prepare VPN connection: if VPN permission is already granted, start directly;
     * otherwise store the pending profile and return the permission intent for the caller to launch.
     */
    fun prepareAndStartVpn(context: Context, profileName: String): Intent? {
        val intent = VpnService.prepare(context)
        if (intent == null) {
            // Optimistically set active profile before VPN starts
            _activeProfileName.value = profileName
            _lastProfileName.value = profileName
            startVpn(context, profileName)
            return null
        }
        // Permission flow: keep the button on "Connecting…" while the system
        // dialog is up; startVpn() fires once the user grants it.
        _connectRequested.value = true
        _pendingProfile.value = profileName
        return intent
    }

    /**
     * Called after the user grants (or dismisses) the VPN permission dialog.
     * If permission was granted (RESULT_OK already checked by caller), starts the pending profile.
     */
    fun onVpnPermissionResult(context: Context) {
        val profile = _pendingProfile.value ?: return
        _pendingProfile.value = null
        _activeProfileName.value = profile // immediate feedback
        _lastProfileName.value = profile
        startVpn(context, profile)
    }

    fun getProfileIpInfo(profileName: String): String {
        val app = getApplication<Application>()
        return try {
            val pm = ProfileManager.getInstance(app)
            val profile = pm.getProfile(profileName) ?: return ""
            "${profile.getServer()}:${profile.getPort()}"
        } catch (_: Exception) {
            ""
        }
    }

    override fun onCleared() {
        super.onCleared()
        cleared = true
        try {
            getApplication<Application>().unregisterReceiver(stateReceiver)
        } catch (_: Exception) {
        }
        rebindRunnable?.let { rebindHandler.removeCallbacks(it); rebindRunnable = null }
        rebinding = false
        try {
            getApplication<Application>().unbindService(serviceConnection)
        } catch (_: Exception) {
            // Ignore
        }
        bound = false
        vpnService = null
    }
}
