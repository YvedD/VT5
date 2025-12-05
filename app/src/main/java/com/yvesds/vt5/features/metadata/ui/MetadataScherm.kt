@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.yvesds.vt5.features.metadata.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.yvesds.vt5.R
import com.yvesds.vt5.core.opslag.SaFStorageHelper
import com.yvesds.vt5.core.ui.ProgressDialogHelper
import com.yvesds.vt5.databinding.SchermMetadataBinding
import com.yvesds.vt5.features.alias.AliasManager
import com.yvesds.vt5.features.metadata.helpers.MetadataFormManager
import com.yvesds.vt5.features.metadata.helpers.TellingStarter
import com.yvesds.vt5.features.metadata.helpers.WeatherDataFetcher
import com.yvesds.vt5.features.opstart.usecases.TrektellenAuth
import com.yvesds.vt5.features.serverdata.model.DataSnapshot
import com.yvesds.vt5.features.serverdata.model.ServerDataCache
import com.yvesds.vt5.features.serverdata.model.ServerDataRepository
import com.yvesds.vt5.features.soort.ui.SoortSelectieScherm
import com.yvesds.vt5.features.telling.TellingSessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Metadata invoerscherm - REFACTORED with helper classes.
 * 
 * This screen now delegates to specialized helpers:
 * - MetadataFormManager: Form field management and validation
 * - WeatherDataFetcher: Weather API integration
 * - TellingStarter: Start telling API logic
 * 
 * Maintains all original functionality with cleaner architecture.
 */
class MetadataScherm : AppCompatActivity() {
    companion object {
        private const val TAG = "MetadataScherm"
        
        /**
         * Extra key for vervolgtelling: contains the eindtijd of the previous telling
         * as epoch seconds string. When present, this will be used as the begintijd
         * for the new telling, and the weather "Auto" button will be enabled.
         */
        const val EXTRA_VERVOLG_BEGINTIJD_EPOCH = "EXTRA_VERVOLG_BEGINTIJD_EPOCH"
    }

    private lateinit var binding: SchermMetadataBinding
    private var snapshot: DataSnapshot = DataSnapshot()
    private var dataLoaded = false

    // Scope voor achtergrondtaken
    private val uiScope = CoroutineScope(Job() + Dispatchers.Main)
    private var backgroundLoadJob: Job? = null
    
    // Track if weather fetch is in progress to prevent duplicate calls
    private var isWeatherFetching = false

    // SAF helper for alias manager access
    private lateinit var saf: SaFStorageHelper
    
    // Helper managers
    private lateinit var formManager: MetadataFormManager
    private lateinit var weatherFetcher: WeatherDataFetcher
    private lateinit var tellingStarter: TellingStarter

    private val requestLocationPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) onWeerAutoClicked()
        else Toast.makeText(this, getString(R.string.metadata_error_location_denied), Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = SchermMetadataBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize SAF helper
        saf = SaFStorageHelper(this)
        
        // Initialize helper managers
        formManager = MetadataFormManager(this, binding)
        weatherFetcher = WeatherDataFetcher(this, binding, formManager)
        val prefs = getSharedPreferences("vt5_prefs", MODE_PRIVATE)
        tellingStarter = TellingStarter(this, binding, formManager, prefs)

        // geen keyboard voor datum/tijd
        binding.etDatum.inputType = InputType.TYPE_NULL
        binding.etTijd.inputType = InputType.TYPE_NULL

        // pickers + defaults
        formManager.initDateTimePickers()
        formManager.prefillCurrentDateTime()
        
        // Check for vervolgtelling: if begintijd is passed from previous telling,
        // use it to preset the time field and ensure weather button is enabled
        handleVervolgtellingIntent()

        // Acties
        binding.btnVerder.setOnClickListener { onVerderClicked() }
        binding.btnAnnuleer.setOnClickListener { finish() }
        binding.btnWeerAuto.setOnClickListener { ensureLocationPermissionThenFetch() }

        // Start het laden in stappen:
        // 1. Eerst de essentiële codes (snel)
        // 2. Later, terwijl de gebruiker bezig is, de rest van de data
        loadEssentialData()
    }
    
    /**
     * Handle vervolgtelling intent: if this activity was started from TellingScherm
     * after a successful upload with "Vervolgtelling aanmaken", the begintijd
     * of the previous telling's eindtijd will be preset.
     */
    private fun handleVervolgtellingIntent() {
        val vervolgBegintijdEpoch = intent.getStringExtra(EXTRA_VERVOLG_BEGINTIJD_EPOCH)
        if (!vervolgBegintijdEpoch.isNullOrBlank()) {
            try {
                val epochSeconds = vervolgBegintijdEpoch.toLongOrNull()
                if (epochSeconds != null && epochSeconds > 0) {
                    // Set the time field to match the eindtijd of the previous telling
                    val date = java.util.Date(epochSeconds * 1000L)
                    val timeFmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                    binding.etTijd.setText(timeFmt.format(date))
                    
                    // Update formManager's startEpochSec to reflect this
                    formManager.startEpochSec = epochSeconds
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse vervolgtelling begintijd: ${e.message}")
            }
        }
    }

    /**
     * Eerste fase: laad alleen de noodzakelijke data voor het vullen van de dropdown menus
     * Dit zorgt voor een veel snellere initiële lading
     *
     * OPTIMIZATION: Added progress feedback during minimal load
     */
    private fun loadEssentialData() {
        uiScope.launch {
            try {
                // Check eerst of we al volledige data in cache hebben
                val cachedData = ServerDataCache.getCachedOrNull()
                if (cachedData != null) {
                    snapshot = cachedData
                    initializeDropdowns()
                    
                    // Prefill Tellers field with user's fullname from checkuser.json
                    formManager.prefillTellersFromSnapshot(cachedData)

                    // Start het laden van de volledige data in de achtergrond
                    scheduleBackgroundLoading()
                    return@launch
                }

                // OPTIMIZATION: Detailed progress feedback during load
                val progressDialog = ProgressDialogHelper.show(this@MetadataScherm, "Codes laden...")
                try {
                    // Laad de minimale data
                    val repo = ServerDataRepository(this@MetadataScherm)

                    // Update progress: loading codes
                    withContext(Dispatchers.Main) {
                        ProgressDialogHelper.updateMessage(progressDialog, "Telposten laden...")
                    }

                    val minimal = repo.loadMinimalData()
                    snapshot = minimal

                    // Update progress: initializing UI
                    withContext(Dispatchers.Main) {
                        ProgressDialogHelper.updateMessage(progressDialog, "Interface voorbereiden...")
                        initializeDropdowns()
                    }

                    // Start het laden van de volledige data in de achtergrond
                    scheduleBackgroundLoading()
                } finally {
                    progressDialog.dismiss()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading essential data: ${e.message}")
                Toast.makeText(this@MetadataScherm, getString(R.string.metadata_error_loading_data), Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Plant het laden van de volledige data in de achtergrond
     * terwijl de gebruiker bezig is met het formulier in te vullen
     *
     * Performance: Gebruikt low-priority dispatcher en delay om UI responsiveness te behouden
     *
     * UPDATE: Laadt nu ook de alias index voor SoortSelectieScherm, zodat de complete
     * soortenlijst (alle species uit site_species.json) beschikbaar is voor snelle toegang.
     */
    private fun scheduleBackgroundLoading() {
        // Cancel bestaande job als die er is
        backgroundLoadJob?.cancel()

        // Start nieuwe job met vertraging om eerst de UI te laten renderen
        backgroundLoadJob = uiScope.launch {
            try {
                // Korte vertraging zodat de UI eerst kan renderen
                delay(50)

                // Haal volledige data als die er nog niet is
                // Performance: gebruik IO dispatcher voor file I/O operaties
                withContext(Dispatchers.IO) {
                    if (isActive) {
                        val fullData = ServerDataCache.getOrLoad(this@MetadataScherm)
                        if (isActive) {
                            withContext(Dispatchers.Main) {
                                snapshot = fullData
                                
                                // Prefill Tellers field with user's fullname from checkuser.json
                                formManager.prefillTellersFromSnapshot(fullData)
                            }
                        }

                        // Also preload alias index for SoortSelectieScherm
                        // This ensures the complete species list is ready
                        if (isActive) {
                            try {
                                AliasManager.ensureIndexLoadedSuspend(this@MetadataScherm, saf)
                            } catch (ex: Exception) {
                                Log.w(TAG, "Alias index preload failed (non-critical): ${ex.message}")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Background data loading failed: ${e.message}")
                // Geen toast hier, het is een achtergrondtaak die de gebruiker niet hoeft te storen
            }
        }
    }

    /**
     * Initialiseert de dropdowns met de beschikbare gegevens
     */
    private fun initializeDropdowns() {
        formManager.bindTelpostDropdown(snapshot)
        formManager.bindWeatherDropdowns(snapshot)
        dataLoaded = true
    }

    /* ---------- Permissie → weer auto ---------- */

    private fun ensureLocationPermissionThenFetch() {
        if (weatherFetcher.hasLocationPermission()) {
            onWeerAutoClicked()
        } else {
            requestLocationPerms.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        }
    }

    private fun onWeerAutoClicked() {
        // Prevent duplicate weather fetch calls
        if (isWeatherFetching) return
        isWeatherFetching = true
        
        uiScope.launch {
            try {
                val success = weatherFetcher.fetchAndApplyWeather(snapshot)
                if (!success) {
                    Toast.makeText(
                        this@MetadataScherm,
                        getString(R.string.metadata_error_weather_fetch),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching weather: ${e.message}")
                Toast.makeText(
                    this@MetadataScherm,
                    getString(R.string.metadata_error_weather_fetch_short),
                    Toast.LENGTH_SHORT
                ).show()
            } finally {
                isWeatherFetching = false
            }
        }
    }

    /* Note: Date/time picker and dropdown methods moved to MetadataFormManager */

    /* ---------------- Verder → counts_save (header) → SoortSelectieScherm ---------------- */

    private fun onVerderClicked() {
        val telpostId = formManager.gekozenTelpostId
        if (telpostId.isNullOrBlank()) {
            Toast.makeText(this, getString(R.string.metadata_error_no_telpost), Toast.LENGTH_SHORT).show()
            return
        }

        // Credentials ophalen
        val creds = TrektellenAuth.getSavedCredentials(this)
        val username = creds?.first
        val password = creds?.second
        if (username.isNullOrBlank() || password.isNullOrBlank()) {
            Toast.makeText(this, getString(R.string.metadata_error_no_credentials), Toast.LENGTH_SHORT).show()
            return
        }

        // Start directly in live mode (no dialog)
        startTellingAndOpenSoortSelectie(telpostId, username = username, password = password)
    }

    // Launcher voor SoortSelectieScherm (multi-select)
    private val soortSelectieLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val ids = res.data?.getStringArrayListExtra(SoortSelectieScherm.EXTRA_SELECTED_SOORT_IDS).orEmpty()
            formManager.gekozenTelpostId?.let { TellingSessionManager.setTelpost(it) }
            TellingSessionManager.setPreselectedSoorten(ids)
            val intent = Intent(this, com.yvesds.vt5.features.telling.TellingScherm::class.java)
            startActivity(intent)
        }
    }

    /**
     * Start telling - REFACTORED to use TellingStarter helper.
     * This now delegates the complex logic to TellingStarter.
     */
    private fun startTellingAndOpenSoortSelectie(telpostId: String, username: String, password: String) {
        uiScope.launch {
            try {
                ProgressDialogHelper.withProgress(this@MetadataScherm, getString(R.string.metadata_preparing)) {
                    // Ensure full snapshot is available
                    val fullSnapshot = ServerDataCache.getOrLoad(this@MetadataScherm)
                    
                    // Start the telling via TellingStarter helper
                    val result = tellingStarter.startTelling(telpostId, username, password, fullSnapshot)
                    
                    if (!result.success) {
                        // Show error dialog
                        withContext(Dispatchers.Main) {
                            AlertDialog.Builder(this@MetadataScherm)
                                .setTitle(getString(R.string.metadata_error_start_failed_title))
                                .setMessage(getString(R.string.metadata_error_start_failed_msg, result.errorMessage ?: "Unknown error"))
                                .setPositiveButton(getString(R.string.dlg_ok), null)
                                .show()
                        }
                        return@withProgress
                    }
                    
                    // Success! Setup session and open SoortSelectieScherm
                    val speciesForTelpost = fullSnapshot.siteSpeciesBySite[telpostId]?.mapNotNull { it.soortid } ?: emptyList()
                    TellingSessionManager.setTelpost(telpostId)
                    TellingSessionManager.setPreselectedSoorten(speciesForTelpost)
                    
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@MetadataScherm,
                            getString(R.string.metadata_success_started, result.onlineId),
                            Toast.LENGTH_SHORT
                        ).show()
                        
                        val intent = Intent(this@MetadataScherm, SoortSelectieScherm::class.java)
                        intent.putExtra(SoortSelectieScherm.EXTRA_TELPOST_ID, telpostId)
                        intent.putExtra("EXTRA_LIVE_MODE", true)
                        intent.putExtra("EXTRA_USERNAME", username)
                        intent.putExtra("EXTRA_PASSWORD", password)
                        soortSelectieLauncher.launch(intent)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting telling: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MetadataScherm,
                        getString(R.string.metadata_error_preparing, e.message ?: ""),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    /* Note: computeBeginEpochSec and parseOnlineIdFromResponse moved to TellingStarter helper */



    override fun onDestroy() {
        super.onDestroy()
        // Annuleer alle achtergrondtaken
        backgroundLoadJob?.cancel()
    }
}