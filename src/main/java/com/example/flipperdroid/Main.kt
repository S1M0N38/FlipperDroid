package com.example.flipperdroid
import android.annotation.SuppressLint
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.activity.viewModels
import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import com.example.flipperdroid.viewmodel.*
import com.example.flipperdroid.view.*
import com.example.flipperdroid.ui.theme.FlipperDroidTheme
import android.nfc.tech.NfcA
import android.nfc.tech.NfcB
import android.nfc.tech.NfcF
import android.nfc.tech.NfcV
import com.example.flipperdroid.viewmodel.EmvCardEmulationViewModel
import com.example.flipperdroid.view.EmvCardEmulationScreen
import com.example.flipperdroid.viewmodel.EmvReaderViewModel
import com.example.flipperdroid.view.EmvReaderScreen
import com.example.flipperdroid.viewmodel.BleSpamViewModel
import com.example.flipperdroid.view.BleSpamScreen
import com.example.flipperdroid.viewmodel.BluetoothViewModel
import com.example.flipperdroid.view.LegalTextScreen
import com.example.flipperdroid.viewmodel.ThemeViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState

/**
 * Extracts an [android.os.Parcelable] from an Intent in an all-versions
 * compatible way: the typed getParcelableExtra(name, Class) overload only exists
 * from Android 13 (API 33) onwards. Since minSdk = 24, calling it directly would throw
 * a NoSuchMethodError on older devices (crash on NFC scan / on the
 * USB connection). We fall back to the deprecated API below API 33.
 */
@Suppress("DEPRECATION")
private inline fun <reified T : android.os.Parcelable> Intent.parcelableExtraCompat(name: String): T? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(name, T::class.java)
    } else {
        getParcelableExtra(name) as? T
    }

class MainActivity : ComponentActivity() {

    private val nfcViewModel: NfcViewModel by viewModels()
    private val badUsbViewModel: BadUsbViewModel by viewModels()
    private val bluetoothViewModel: BluetoothViewModel by viewModels()
    private val networkToolsViewModel: NetworkToolsViewModel by viewModels()
    private val wifiDeautherViewModel: WifiDeautherViewModel by viewModels()
    private val emvCardEmulationViewModel: EmvCardEmulationViewModel by viewModels()
    private val emvReaderViewModel: EmvReaderViewModel by viewModels()
    private val bleSpamViewModel: BleSpamViewModel by viewModels()
    private val themeViewModel: ThemeViewModel by viewModels()
    private var nfcAdapter: NfcAdapter? = null
    private lateinit var pendingIntent: PendingIntent
    private lateinit var intentFiltersArray: Array<IntentFilter>
    private lateinit var techListsArray: Array<Array<String>>

    /**
     * Initializes the main activity.
     *
     * - Configures the ViewModel for NFC handling.
     * - Initializes the NFC Adapter and the PendingIntent for foreground NFC reading.
     * - Sets the Compose content with the app navigation.
     *
     * @param savedInstanceState saved activity state (may be null).
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize NFC adapter
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        // Initialize NFC only if available
        if (nfcAdapter != null) {
            // Initialize PendingIntent for NFC
            val intent = Intent(this, javaClass).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            pendingIntent = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )

            // Setup intent filters for NFC
            intentFiltersArray = arrayOf(
                IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED),
                IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED),
                IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED)
            )

            // Each sub-array is a logical AND; the outer list is an OR.
            // One entry per technology = "any NFC-A/B/F/V tag."
            techListsArray = arrayOf(
                arrayOf(NfcA::class.java.name),
                arrayOf(NfcB::class.java.name),
                arrayOf(NfcF::class.java.name),
                arrayOf(NfcV::class.java.name)
            )
        }

        // Initialize BadUSB
        badUsbViewModel.initialize(this)

        // Initialize Bluetooth
        bluetoothViewModel.initialize(this)

        setContent {
            val isDarkMode by themeViewModel.isDarkMode.collectAsState()
            FlipperDroidTheme(darkTheme = isDarkMode) {
                AppNavigation(themeViewModel)
            }
        }
        // A recreation (rotation…) delivers the same launch Intent again: we only
        // handle it on the first onCreate, then consume it. Otherwise an armed clone
        // could trigger on a "ghost" tag without a new tap.
        if (savedInstanceState == null) {
            handleNfcIntent(intent)
            setIntent(Intent())
        }
    }

    /**
     * Method called when a new Intent is received by the activity.
     *
     * Intercepts the NFC intent, retrieves the detected tag, extracts its identifier,
     * then passes it to the ViewModel for processing.
     *
     * @param intent the Intent received by the activity.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.let {
            handleNfcIntent(intent)

            // Handle USB connection
            if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
                val device: UsbDevice? =
                    intent.parcelableExtraCompat(UsbManager.EXTRA_DEVICE)
                val usbManager = getSystemService(USB_SERVICE) as? UsbManager
                if (usbManager != null) device?.let { dev ->
                    if (usbManager.hasPermission(dev)) {
                        val connection = usbManager.openDevice(dev)
                        if (connection != null) {
                            badUsbViewModel.connectUsb(dev, connection)
                        }
                    }
                }
            }
        }
    }

    private fun handleNfcIntent(intent: Intent?) {
        if (intent?.action == NfcAdapter.ACTION_TAG_DISCOVERED ||
            intent?.action == NfcAdapter.ACTION_TECH_DISCOVERED ||
            intent?.action == NfcAdapter.ACTION_NDEF_DISCOVERED
        ) {
            val tag: Tag? = intent.parcelableExtraCompat(NfcAdapter.EXTRA_TAG)
            tag?.let {
                // A Mifare Classic tag will be read by nfcViewModel; an EMV (IsoDep) card
                // by emvReaderViewModel. BUT if an emulation capture or a clone is
                // armed, NfcViewModel connects to the tag itself: the EMV reader must not
                // open a second concurrent IsoDep connection (they would block each other and
                // the capture would fail). We read the "armed" state BEFORE onTagScanned, which
                // resets it to false.
                val nfcBusy = nfcViewModel.emuCaptureArmed.value || nfcViewModel.cloneArmed.value
                nfcViewModel.onTagScanned(it)
                if (!nfcBusy) emvReaderViewModel.readCard(it)
            }
        }
    }

    /**
     * Method called when the activity resumes to the foreground.
     *
     * Enables foreground NFC reading to capture NFC tags
     * while the app is in the foreground.
     */
    override fun onResume() {
        super.onResume()
        applyKeepScreenOn()
        // Enable foreground dispatch only if NFC is available and enabled
        nfcAdapter?.let { adapter ->
            if (adapter.isEnabled) {
                adapter.enableForegroundDispatch(
                    this,
                    pendingIntent,
                    intentFiltersArray,
                    techListsArray
                )
            }
        }
    }

    /** Applies the "keep the screen on" setting (SettingsScreen) to the whole activity. */
    private fun applyKeepScreenOn() {
        val keep = getSharedPreferences("app_settings", MODE_PRIVATE).getBoolean("keepScreenOn", false)
        if (keep) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /**
     * Method called when the activity is no longer in the foreground.
     *
     * Disables foreground NFC reading to avoid out-of-context
     * reads or conflicts with other applications.
     */
    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
        badUsbViewModel.disconnect()
    }

    /**
     * enables navigation
     *
     * The routes:
     * - "home" : home screen
     * - "nfc" : NFC feature screen
     * - "badusb" : BadUSB feature screen
     */
    @SuppressLint("ComposableDestinationInComposeScope")
    @Composable
    fun AppNavigation(themeViewModel: ThemeViewModel) {
        val navController = rememberNavController()

        NavHost(navController = navController, startDestination = "home") {
            composable("home") {
                HomeScreen(navController = navController, nfcViewModel = nfcViewModel)
            }
            composable("nfc") {
                NfcScreen(navController = navController, nfcViewModel = nfcViewModel)
            }
            composable("badusb") {
                BadUsbScreen(navController = navController, viewModel = badUsbViewModel)
            }
            composable("bluetooth") {
                BleSpamScreen(navController = navController, viewModel = bleSpamViewModel)
            }
            composable("network") {
                NetworkToolsScreen(navController = navController, viewModel = networkToolsViewModel)
            }
            composable("wifi_deauther") {
                WifiDeautherScreen(navController = navController, viewModel = wifiDeautherViewModel)
            }
            composable("ir") {
                InfraredScreen(navController = navController)
            }
            composable("ir_remotes") {
                IrRemoteScreen(navController = navController)
            }
            composable("ir_tools") {
                IrToolsScreen(navController = navController)
            }
            composable("ac_control") {
                AcControlScreen(navController = navController)
            }
            composable("password_generator") {
                PasswordGeneratorScreen(navController = navController)
            }
            composable("crypto_tools") {
                CryptoToolsScreen(navController = navController)
            }
            composable("wifi_qr") {
                WifiQrScreen(navController = navController)
            }
            composable("default_creds") {
                DefaultCredsScreen(navController = navController)
            }
            composable("qr") {
                QrScannerScreen(navController = navController)
            }
            composable("ble_scanner") {
                BleScannerScreen(navController = navController, viewModel = bluetoothViewModel)
            }
            composable("bt_classic") {
                BluetoothClassicScreen(navController = navController)
            }
            composable("tracker_detector") {
                TrackerDetectorScreen(navController = navController)
            }
            composable("lan_discovery") {
                LanDiscoveryScreen(navController = navController)
            }
            composable("netcat") {
                NetcatScreen(navController = navController)
            }
            composable("gps_spoof") {
                GpsSpoofScreen(navController = navController)
            }
            composable("ndef_emulator") {
                NdefEmulatorScreen(navController = navController)
            }
            composable("wardriving") {
                WardrivingScreen(navController = navController)
            }
            composable("skimmer") {
                SkimmerDetectorScreen(navController = navController)
            }
            composable("wifi_analyzer") {
                WifiAnalyzerScreen(navController = navController)
            }
            composable("badusb_root") {
                RootBadUsbScreen(navController = navController)
            }
            composable("ble_beacon") {
                BeaconScreen(navController = navController)
            }
            composable("ble_keyboard") {
                HidKeyboardScreen(navController = navController)
            }
            composable("evil_portal") {
                EvilPortalScreen(navController = navController)
            }
            composable("totp") {
                TotpScreen(navController = navController)
            }
            composable("flipper_files") {
                FlipperFilesScreen(navController = navController)
            }
            composable("logs") {
                LogsScreen(navController = navController)
            }
            composable("settings") {
                SettingsScreen(navController = navController, themeViewModel = themeViewModel)
            }
            composable("about") {
                AboutScreen(navController = navController)
            }
            composable("emv_emulation") {
                EmvCardEmulationScreen(
                    navController = navController,
                    viewModel = emvCardEmulationViewModel
                )
            }
            composable("emv_reader") {
                EmvReaderScreen(navController = navController, viewModel = emvReaderViewModel)
            }
            composable("legal_mit") {
                LegalTextScreen(
                    navController = navController,
                    assetPath = "legacy/mit.txt",
                    title = "MIT License"
                )
            }
            composable("legal_cgu") {
                LegalTextScreen(
                    navController = navController,
                    assetPath = "legacy/term_of_use.txt",
                    title = "Term Of Use"
                )
            }
            composable("legal_mentions") {
                LegalTextScreen(
                    navController = navController,
                    assetPath = "legacy/legacy_notice.txt",
                    title = "Legacy Notice"
                )
            }
            composable("ble_fuzzer") {
                BleGattFuzzerScreen(navController = navController)
            }
            composable("packet_sniffer") {
                PacketSnifferScreen(navController = navController)
            }
            composable("iot_scanner") {
                IotProtocolScannerScreen(navController = navController)
            }
            composable("ip_cameras") {
                IpCameraScreen(navController = navController)
            }
            composable("report_generator") {
                ReportGeneratorScreen(navController = navController)
            }
        }
    }
}
