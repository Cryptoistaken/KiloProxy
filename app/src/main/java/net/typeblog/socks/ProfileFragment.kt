package net.typeblog.socks

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.preference.CheckBoxPreference
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceManager
import android.text.TextUtils
import android.util.Log
import android.view.View
import kotlin.concurrent.thread
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.preference.PreferenceFragmentCompat

import com.google.android.material.materialswitch.MaterialSwitch

import net.typeblog.socks.util.Profile
import net.typeblog.socks.util.ProfileManager
import net.typeblog.socks.util.IpInfo
import net.typeblog.socks.util.Utility
import net.typeblog.socks.util.Constants.PREF_ADV_AUTO_CONNECT
import net.typeblog.socks.util.Constants.PREF_ADV_APP_BYPASS
import net.typeblog.socks.util.Constants.PREF_ADV_APP_LIST
import net.typeblog.socks.util.Constants.PREF_ADV_DNS
import net.typeblog.socks.util.Constants.PREF_ADV_DNS_PORT
import net.typeblog.socks.util.Constants.PREF_ADV_PER_APP
import net.typeblog.socks.util.Constants.PREF_ADV_ROUTE
import net.typeblog.socks.util.Constants.PREF_AUTH_PASSWORD
import net.typeblog.socks.util.Constants.PREF_AUTH_USERNAME
import net.typeblog.socks.util.Constants.PREF_AUTH_USERPW
import net.typeblog.socks.util.Constants.PREF_AUTO_STOP
import net.typeblog.socks.util.Constants.PREF_CONNECTIVITY_CHECK
import net.typeblog.socks.util.Constants.PREF_DYNAMIC_COLORS
import net.typeblog.socks.util.Constants.PREF_IPV6_PROXY
import net.typeblog.socks.util.Constants.PREF_PROFILE
import net.typeblog.socks.util.Constants.PREF_SERVER_IP
import net.typeblog.socks.util.Constants.PREF_SERVER_PORT
import net.typeblog.socks.util.Constants.PREF_THEME_MODE
import net.typeblog.socks.util.Constants.PREF_UDP_GW
import net.typeblog.socks.util.Constants.PREF_UDP_PROXY

class ProfileFragment : PreferenceFragmentCompat(),
    Preference.OnPreferenceClickListener,
    Preference.OnPreferenceChangeListener,
    CompoundButton.OnCheckedChangeListener {

    private lateinit var mManager: ProfileManager
    private var mProfile: Profile? = null

    private var mSwitch: MaterialSwitch? = null
    private var mRunning = false
    private var mStarting = false
    private var mStopping = false
    private var mBinder: IVpnService? = null

    private val mConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            mBinder = IVpnService.Stub.asInterface(service)

            try {
                mRunning = mBinder!!.isRunning
            } catch (e: Exception) {
                Log.e(TAG, "Failed to check running state", e)
            }

            if (mRunning) {
                updateState()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            mBinder = null
        }
    }

    private val mStateRunnable = object : Runnable {
        override fun run() {
            updateState()
            updateIpDisplay()
            mSwitch?.postDelayed(this, 1000)
        }
    }

    private val vpnPrepareLauncher: ActivityResultLauncher<Intent> = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Utility.startVpn(requireActivity(), mProfile!!)
            checkState()
        }
    }

    // ---- Preference fields ----
    private lateinit var mPrefProfile: ListPreference
    private lateinit var mPrefRoutes: ListPreference
    private lateinit var mPrefDnsPresets: ListPreference
    private lateinit var mPrefServer: EditTextPreference
    private lateinit var mPrefPort: EditTextPreference
    private lateinit var mPrefUsername: EditTextPreference
    private lateinit var mPrefPassword: EditTextPreference
    private lateinit var mPrefDns: EditTextPreference
    private lateinit var mPrefDnsPort: EditTextPreference
    private lateinit var mPrefAppList: EditTextPreference
    private lateinit var mPrefUDPGW: EditTextPreference
    private lateinit var mPrefUserpw: CheckBoxPreference
    private lateinit var mPrefPerApp: CheckBoxPreference
    private lateinit var mPrefAppBypass: CheckBoxPreference
    private lateinit var mPrefIPv6: CheckBoxPreference
    private lateinit var mPrefUDP: CheckBoxPreference
    private lateinit var mPrefAuto: CheckBoxPreference
    private var mPrefDynamic: CheckBoxPreference? = null
    private lateinit var mPrefThemeMode: ListPreference
    private lateinit var mPrefAutoStop: CheckBoxPreference
    private lateinit var mPrefConnectivityCheck: CheckBoxPreference
    private lateinit var mPrefAdd: Preference
    private lateinit var mPrefDel: Preference
    private lateinit var mPrefAppSelector: Preference

    // ---- Lifecycle ----

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mManager = ProfileManager.getInstance(requireActivity().applicationContext)
        initPreferences()
        reload()
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings, null)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        mSwitch = requireActivity().findViewById(R.id.switch_action_button)
        mSwitch?.setOnCheckedChangeListener(this)
        mSwitch?.postDelayed(mStateRunnable, 1000)
        checkState()
    }

    override fun onResume() {
        super.onResume()
        mSwitch?.postDelayed(mStateRunnable, 1000)
    }

    override fun onPause() {
        super.onPause()
        mSwitch?.removeCallbacks(mStateRunnable)
    }

    // ---- Listeners ----

    override fun onPreferenceClick(preference: Preference): Boolean {
        return when (preference) {
            mPrefAdd -> {
                addProfile()
                true
            }
            mPrefDel -> {
                removeProfile()
                true
            }
            mPrefAppSelector -> {
                showAppSelector()
                true
            }
            else -> false
        }
    }

    override fun onPreferenceChange(preference: Preference, newValue: Any?): Boolean {
        return when (preference) {
            mPrefProfile -> {
                val name = newValue.toString()
                mProfile = mManager.getProfile(name)
                mManager.switchDefault(name)
                reload()
                true
            }
            mPrefServer -> {
                mProfile!!.setServer(newValue.toString())
                resetTextN(mPrefServer, newValue)
                true
            }
            mPrefPort -> {
                if (TextUtils.isEmpty(newValue.toString())) return false
                mProfile!!.setPort(Integer.parseInt(newValue.toString()))
                resetTextN(mPrefPort, newValue)
                true
            }
            mPrefUserpw -> {
                mProfile!!.setIsUserpw(java.lang.Boolean.parseBoolean(newValue.toString()))
                true
            }
            mPrefUsername -> {
                mProfile!!.setUsername(newValue.toString())
                resetTextN(mPrefUsername, newValue)
                true
            }
            mPrefPassword -> {
                mProfile!!.setPassword(newValue.toString())
                resetTextN(mPrefPassword, newValue)
                true
            }
            mPrefRoutes -> {
                mProfile!!.setRoute(newValue.toString())
                resetListN(mPrefRoutes, newValue)
                true
            }
            mPrefDnsPresets -> {
                val preset = newValue.toString()
                if (!TextUtils.isEmpty(preset)) {
                    mProfile!!.setDns(preset)
                    resetTextN(mPrefDns, preset)
                    mPrefDns.text = preset
                }
                true
            }
            mPrefDns -> {
                mProfile!!.setDns(newValue.toString())
                resetTextN(mPrefDns, newValue)
                true
            }
            mPrefDnsPort -> {
                if (TextUtils.isEmpty(newValue.toString())) return false
                mProfile!!.setDnsPort(Integer.valueOf(newValue.toString()))
                resetTextN(mPrefDnsPort, newValue)
                true
            }
            mPrefPerApp -> {
                mProfile!!.setIsPerApp(java.lang.Boolean.parseBoolean(newValue.toString()))
                true
            }
            mPrefAppBypass -> {
                mProfile!!.setIsBypassApp(java.lang.Boolean.parseBoolean(newValue.toString()))
                true
            }
            mPrefAppList -> {
                mProfile!!.setAppList(newValue.toString())
                true
            }
            mPrefIPv6 -> {
                mProfile!!.setHasIPv6(java.lang.Boolean.parseBoolean(newValue.toString()))
                true
            }
            mPrefUDP -> {
                mProfile!!.setHasUDP(java.lang.Boolean.parseBoolean(newValue.toString()))
                true
            }
            mPrefUDPGW -> {
                mProfile!!.setUDPGW(newValue.toString())
                resetTextN(mPrefUDPGW, newValue)
                true
            }
            mPrefAuto -> {
                mProfile!!.setAutoConnect(java.lang.Boolean.parseBoolean(newValue.toString()))
                true
            }
            mPrefDynamic -> {
                Toast.makeText(requireActivity(), "Restart app to apply theme changes", Toast.LENGTH_SHORT).show()
                true
            }
            mPrefThemeMode -> {
                Toast.makeText(requireActivity(), "Restart app to apply theme changes", Toast.LENGTH_SHORT).show()
                true
            }
            mPrefAutoStop -> {
                PreferenceManager.getDefaultSharedPreferences(requireActivity())
                    .edit().putBoolean(PREF_AUTO_STOP, java.lang.Boolean.parseBoolean(newValue.toString())).apply()
                true
            }
            mPrefConnectivityCheck -> {
                PreferenceManager.getDefaultSharedPreferences(requireActivity())
                    .edit().putBoolean(PREF_CONNECTIVITY_CHECK, java.lang.Boolean.parseBoolean(newValue.toString())).apply()
                true
            }
            else -> false
        }
    }

    override fun onCheckedChanged(buttonView: CompoundButton?, isChecked: Boolean) {
        if (isChecked) {
            startVpn()
        } else {
            stopVpn()
        }
    }

    // ---- Preference wiring ----

    private fun initPreferences() {
        mPrefProfile = findPreference(PREF_PROFILE) as ListPreference?
            ?: throw IllegalStateException("Missing preference: $PREF_PROFILE")
        mPrefServer = findPreference(PREF_SERVER_IP) as EditTextPreference?
            ?: throw IllegalStateException("Missing preference: $PREF_SERVER_IP")
        mPrefPort = findPreference(PREF_SERVER_PORT) as EditTextPreference?
            ?: throw IllegalStateException("Missing preference: $PREF_SERVER_PORT")
        mPrefUserpw = findPreference(PREF_AUTH_USERPW) as CheckBoxPreference?
            ?: throw IllegalStateException("Missing preference: $PREF_AUTH_USERPW")
        mPrefUsername = findPreference(PREF_AUTH_USERNAME) as EditTextPreference?
            ?: throw IllegalStateException("Missing preference: $PREF_AUTH_USERNAME")
        mPrefPassword = findPreference(PREF_AUTH_PASSWORD) as EditTextPreference?
            ?: throw IllegalStateException("Missing preference: $PREF_AUTH_PASSWORD")
        mPrefRoutes = findPreference(PREF_ADV_ROUTE) as ListPreference?
            ?: throw IllegalStateException("Missing preference: $PREF_ADV_ROUTE")
        mPrefDnsPresets = findPreference("adv_dns_presets") as ListPreference?
            ?: throw IllegalStateException("Missing preference: adv_dns_presets")
        mPrefDns = findPreference(PREF_ADV_DNS) as EditTextPreference?
            ?: throw IllegalStateException("Missing preference: $PREF_ADV_DNS")
        mPrefDnsPort = findPreference(PREF_ADV_DNS_PORT) as EditTextPreference?
            ?: throw IllegalStateException("Missing preference: $PREF_ADV_DNS_PORT")
        mPrefPerApp = findPreference(PREF_ADV_PER_APP) as CheckBoxPreference?
            ?: throw IllegalStateException("Missing preference: $PREF_ADV_PER_APP")
        mPrefAppBypass = findPreference(PREF_ADV_APP_BYPASS) as CheckBoxPreference?
            ?: throw IllegalStateException("Missing preference: $PREF_ADV_APP_BYPASS")
        mPrefAppList = findPreference(PREF_ADV_APP_LIST) as EditTextPreference?
            ?: throw IllegalStateException("Missing preference: $PREF_ADV_APP_LIST")
        mPrefIPv6 = findPreference(PREF_IPV6_PROXY) as CheckBoxPreference?
            ?: throw IllegalStateException("Missing preference: $PREF_IPV6_PROXY")
        mPrefUDP = findPreference(PREF_UDP_PROXY) as CheckBoxPreference?
            ?: throw IllegalStateException("Missing preference: $PREF_UDP_PROXY")
        mPrefUDPGW = findPreference(PREF_UDP_GW) as EditTextPreference?
            ?: throw IllegalStateException("Missing preference: $PREF_UDP_GW")
        mPrefAuto = findPreference(PREF_ADV_AUTO_CONNECT) as CheckBoxPreference?
            ?: throw IllegalStateException("Missing preference: $PREF_ADV_AUTO_CONNECT")
        mPrefDynamic = findPreference(PREF_DYNAMIC_COLORS) as? CheckBoxPreference
        mPrefThemeMode = findPreference(PREF_THEME_MODE) as ListPreference?
            ?: throw IllegalStateException("Missing preference: $PREF_THEME_MODE")
        mPrefAutoStop = findPreference(PREF_AUTO_STOP) as CheckBoxPreference?
            ?: throw IllegalStateException("Missing preference: $PREF_AUTO_STOP")
        mPrefConnectivityCheck = findPreference(PREF_CONNECTIVITY_CHECK) as CheckBoxPreference?
            ?: throw IllegalStateException("Missing preference: $PREF_CONNECTIVITY_CHECK")
        mPrefAdd = findPreference("prof_add_btn") as Preference?
            ?: throw IllegalStateException("Missing preference: prof_add_btn")
        mPrefDel = findPreference("prof_del_btn") as Preference?
            ?: throw IllegalStateException("Missing preference: prof_del_btn")
        mPrefAppSelector = findPreference("adv_app_selector") as Preference?
            ?: throw IllegalStateException("Missing preference: adv_app_selector")

        mPrefProfile.onPreferenceChangeListener = this
        mPrefServer.onPreferenceChangeListener = this
        mPrefPort.onPreferenceChangeListener = this
        mPrefUserpw.onPreferenceChangeListener = this
        mPrefUsername.onPreferenceChangeListener = this
        mPrefPassword.onPreferenceChangeListener = this
        mPrefRoutes.onPreferenceChangeListener = this
        mPrefDnsPresets.onPreferenceChangeListener = this
        mPrefDns.onPreferenceChangeListener = this
        mPrefDnsPort.onPreferenceChangeListener = this
        mPrefPerApp.onPreferenceChangeListener = this
        mPrefAppBypass.onPreferenceChangeListener = this
        mPrefAppList.onPreferenceChangeListener = this
        mPrefIPv6.onPreferenceChangeListener = this
        mPrefUDP.onPreferenceChangeListener = this
        mPrefUDPGW.onPreferenceChangeListener = this
        mPrefAuto.onPreferenceChangeListener = this
        mPrefDynamic?.onPreferenceChangeListener = this
        mPrefThemeMode.onPreferenceChangeListener = this
        mPrefAutoStop.onPreferenceChangeListener = this
        mPrefConnectivityCheck.onPreferenceChangeListener = this
        mPrefAdd.onPreferenceClickListener = this
        mPrefDel.onPreferenceClickListener = this
        mPrefAppSelector.onPreferenceClickListener = this
    }

    // ---- State helpers ----

    private fun reload() {
        if (mProfile == null) {
            mProfile = mManager.getDefault()
        }

        mPrefProfile.entries = mManager.getProfiles()
        mPrefProfile.entryValues = mManager.getProfiles()
        mPrefProfile.value = mProfile!!.getName()
        mPrefRoutes.value = mProfile!!.getRoute()
        resetList(mPrefProfile, mPrefRoutes)

        mPrefUserpw.isChecked = mProfile!!.isUserPw()
        mPrefPerApp.isChecked = mProfile!!.isPerApp()
        mPrefAppBypass.isChecked = mProfile!!.isBypassApp()
        mPrefIPv6.isChecked = mProfile!!.hasIPv6()
        mPrefUDP.isChecked = mProfile!!.hasUDP()
        mPrefAuto.isChecked = mProfile!!.autoConnect()

        mPrefDynamic?.isChecked = PreferenceManager.getDefaultSharedPreferences(requireActivity())
            .getBoolean(PREF_DYNAMIC_COLORS, true)

        val defPrefs = PreferenceManager.getDefaultSharedPreferences(requireActivity())
        mPrefThemeMode.value = defPrefs.getString(PREF_THEME_MODE, "light")
        mPrefAutoStop.isChecked = defPrefs.getBoolean(PREF_AUTO_STOP, false)
        mPrefConnectivityCheck.isChecked = defPrefs.getBoolean(PREF_CONNECTIVITY_CHECK, true)

        mPrefServer.text = mProfile!!.getServer()
        mPrefPort.text = mProfile!!.getPort().toString()
        mPrefUsername.text = mProfile!!.getUsername()
        mPrefPassword.text = mProfile!!.getPassword()
        mPrefDns.text = mProfile!!.getDns()
        mPrefDnsPort.text = mProfile!!.getDnsPort().toString()
        mPrefUDPGW.text = mProfile!!.getUDPGW()
        resetText(mPrefServer, mPrefPort, mPrefUsername, mPrefPassword, mPrefDns, mPrefDnsPort, mPrefUDPGW)

        mPrefAppList.text = mProfile!!.getAppList()
    }

    private fun resetList(vararg pref: ListPreference) {
        for (p in pref) {
            p.summary = p.entry
        }
    }

    private fun resetListN(pref: ListPreference, newValue: Any?) {
        pref.summary = newValue.toString()
    }

    private fun resetText(vararg pref: EditTextPreference) {
        for (p in pref) {
            p.summary = p.text ?: ""
        }
    }

    private fun resetTextN(pref: EditTextPreference, newValue: Any?) {
        pref.summary = newValue.toString()
    }

    // ---- Profile management ----

    private fun addProfile() {
        val e = EditText(requireActivity())
        e.isSingleLine = true

        AlertDialog.Builder(requireActivity())
            .setTitle(R.string.prof_add)
            .setView(e)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = e.text.toString().trim()
                val p = if (!TextUtils.isEmpty(name)) mManager.addProfile(name) else null
                if (p != null) {
                    mProfile = p
                    reload()
                } else {
                    Toast.makeText(
                        requireActivity(),
                        getString(R.string.err_add_prof, name),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .show()
    }

    private fun removeProfile() {
        AlertDialog.Builder(requireActivity())
            .setTitle(R.string.prof_del)
            .setMessage(getString(R.string.prof_del_confirm, mProfile!!.getName()))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (!mManager.removeProfile(mProfile!!.getName())) {
                    Toast.makeText(
                        requireActivity(),
                        getString(R.string.err_del_prof, mProfile!!.getName()),
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    mProfile = mManager.getDefault()
                    reload()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .show()
    }

    // ---- VPN state management ----

    private fun checkState() {
        mRunning = false
        mSwitch?.isEnabled = false
        mSwitch?.setOnCheckedChangeListener(null)

        if (mBinder == null) {
            requireActivity().bindService(
                Intent(requireActivity(), SocksVpnService::class.java),
                mConnection,
                0
            )
        }
    }

    private fun showAppSelector() {
        AppSelector.show(requireActivity(), mProfile!!.getAppList()) { appList ->
            mProfile!!.setAppList(appList)
            mPrefAppList.text = appList
        }
    }

    private fun updateState() {
        if (mSwitch == null) return

        mRunning = if (mBinder == null) {
            false
        } else {
            try {
                mBinder!!.isRunning
            } catch (e: Exception) {
                false
            }
        }

        mSwitch?.isChecked = mRunning

        if ((!mStarting && !mStopping) || (mStarting && mRunning) || (mStopping && !mRunning)) {
            mSwitch?.isEnabled = true
        }

        if (mStarting && mRunning) {
            mStarting = false
        }

        if (mStopping && !mRunning) {
            mStopping = false
        }

        mSwitch?.setOnCheckedChangeListener(this)
    }

    private fun updateIpDisplay() {
        val ipStatus = activity?.findViewById<TextView>(R.id.ip_status) ?: return

        if (mRunning && mBinder != null) {
            try {
                val proxyIp = mBinder!!.currentIp
                val proxyCode = mBinder!!.countryCode
                if (proxyIp.isNotEmpty() && proxyCode.isNotEmpty()) {
                    val flag = Utility.countryCodeToFlag(proxyCode)
                    ipStatus.text = "Proxy IP: $flag $proxyCode $proxyIp"
                    ipStatus.visibility = View.VISIBLE
                } else {
                    ipStatus.text = "Connecting..."
                    ipStatus.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get IP info", e)
            }
        } else if (!mRunning) {
            // When disconnected, re-check real IP if we were showing proxy info
            val currentText = ipStatus.text?.toString() ?: ""
            if (currentText.startsWith("Proxy IP:") || currentText == "Connecting...") {
                ipStatus.visibility = View.GONE
                thread {
                    val realIpInfo = Utility.checkPublicIp()
                    activity?.runOnUiThread {
                        if (realIpInfo != null && !mRunning) {
                            val flag = Utility.countryCodeToFlag(realIpInfo.countryCode)
                            ipStatus.text = "Your IP: $flag ${realIpInfo.countryCode} ${realIpInfo.ip}"
                            ipStatus.visibility = View.VISIBLE
                        }
                    }
                }
            }
        }
    }

    private fun startVpn() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (requireActivity().checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requireActivity().requestPermissions(
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    101
                )
                return
            }
        }
        mStarting = true
        val i = VpnService.prepare(requireActivity())

        if (i != null) {
            vpnPrepareLauncher.launch(i)
        } else {
            Utility.startVpn(requireActivity(), mProfile!!)
            checkState()
        }
    }

    private fun stopVpn() {
        if (mBinder == null) return

        mStopping = true

        try {
            mBinder!!.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop VPN", e)
        }

        mBinder = null

        requireActivity().unbindService(mConnection)
        checkState()
    }

    companion object {
        private const val TAG = "ProfileFragment"
    }
}
