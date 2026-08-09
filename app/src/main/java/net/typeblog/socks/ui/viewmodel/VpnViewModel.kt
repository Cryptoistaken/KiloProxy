package net.typeblog.socks.ui.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.typeblog.socks.IVpnService
import net.typeblog.socks.SocksVpnService
import net.typeblog.socks.util.ProfileManager
import net.typeblog.socks.util.Utility

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

    private var _pendingProfile = MutableStateFlow<String?>(null)

    private var vpnService: IVpnService? = null
    private var bound = false
    private var rebinding = false
    private var cleared = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            vpnService = IVpnService.Stub.asInterface(service)
            bound = true
            rebinding = false
            // Re-sync live state so the UI never keeps stale "disconnected"
            // after the VPN process restarts and reconnects.
            syncState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            vpnService = null
            bound = false
            scheduleRebind()
        }

        override fun onBindingDied(name: ComponentName?) {
            vpnService = null
            bound = false
            scheduleRebind()
        }
    }

    init {
        Log.d("KiloProxyVM", "VpnViewModel init - instance ${hashCode()}")
        val app = getApplication<Application>()
        bindToService(app)
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
                delay(200L)
            }
        }
    }

    // Reads live state from the bound service. Called from the polling loop and
    // immediately after a (re)connect so the UI re-syncs when the service process
    // comes back instead of staying on a stale disconnected state.
    private fun syncState() {
        if (vpnService == null) return
        try {
            val running = vpnService!!.isRunning
            _isRunning.value = running
            _proxyVerified.value = vpnService!!.isProxyVerified
            val serviceError = vpnService!!.getErrorMessage()
            if (serviceError.isNotEmpty()) {
                _errorMessage.value = serviceError
            }
            if (running) {
                _currentIp.value = vpnService!!.currentIp.ifEmpty { null }
                _countryCode.value = vpnService!!.countryCode.ifEmpty { null }
                _country.value = vpnService!!.country.ifEmpty { null }
                _region.value = vpnService!!.region.ifEmpty { null }
                _city.value = vpnService!!.city.ifEmpty { null }
                _isp.value = vpnService!!.isp.ifEmpty { null }
                _org.value = vpnService!!.org.ifEmpty { null }
                _asName.value = vpnService!!.asName.ifEmpty { null }
                _timezone.value = vpnService!!.timezone.ifEmpty { null }
                _receivedBytes.value = vpnService!!.receivedBytes
                _sentBytes.value = vpnService!!.sentBytes
                _connectedSince.value = vpnService!!.connectedSince
                // Always derive the live profile from the running service
                // (not just from VM-initiated starts), so the bubble /
                // notification / auto-start path keeps the correct card live.
                val runningProfile = vpnService!!.profileName
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
                _activeProfileName.value = null
                if (serviceError.isEmpty()) _errorMessage.value = null
            }
        } catch (e: Exception) {
            clearState()
            _errorMessage.value = e.message ?: "VPN service error"
        }
    }

    private fun clearState() {
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
        _activeProfileName.value = null
    }

    fun startVpn(context: Context, profileName: String) {
        Log.d("KiloProxyVM", "startVpn called for profile: $profileName")
        viewModelScope.launch {
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
        // Store pending even if permission needed
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
            getApplication<Application>().unbindService(serviceConnection)
        } catch (_: Exception) {
            // Ignore
        }
        bound = false
        vpnService = null
    }
}
