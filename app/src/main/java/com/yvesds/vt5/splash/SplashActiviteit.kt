package com.yvesds.vt5.splash

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.yvesds.vt5.R
import com.yvesds.vt5.hoofd.HoofdActiviteit
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * SplashActiviteit - Toont het VT5 logo tijdens het opstarten
 * 
 * Deze activity toont het vt5.png logo groot en gecentreerd op een donkere achtergrond.
 * Na een korte vertraging navigeert het automatisch naar HoofdActiviteit.
 * 
 * Het logo wordt als een echte afbeelding getoond (geen afgeronde hoeken zoals bij 
 * de standaard Android 12+ splash screen API).
 */
@SuppressLint("CustomSplashScreen")
class SplashActiviteit : AppCompatActivity() {
    
    companion object {
        /**
         * Duur van de splash screen in milliseconden.
         * Dit geeft VT5App.preloadDataAsync() de tijd om data in de achtergrond te laden.
         */
        private const val SPLASH_DURATION_MS = 1500L
    }
    
    private var splashJob: Job? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.scherm_splash)
        
        // Voorkom dat de gebruiker terug kan naar de splash screen
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Negeer back button tijdens splash screen
            }
        })
        
        // Navigeer naar HoofdActiviteit na de splash duur
        splashJob = lifecycleScope.launch {
            delay(SPLASH_DURATION_MS)
            navigateToMain()
        }
    }
    
    override fun onDestroy() {
        // Cancel de splash job om memory leaks te voorkomen
        splashJob?.cancel()
        super.onDestroy()
    }
    
    /**
     * Navigeer naar HoofdActiviteit en sluit de splash screen
     */
    private fun navigateToMain() {
        if (!isFinishing && !isDestroyed) {
            startActivity(Intent(this, HoofdActiviteit::class.java))
            finish()
            // Fade animatie voor een vloeiende overgang
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
    }
}
