package com.yvesds.vt5.utils

import java.util.Calendar

/**
 * Utility functions for determining the current migration season.
 * 
 * Bird migration seasons:
 * - ZW seizoen (Jul-Dec): Birds migrate southwest (zuidwest)
 * - NO seizoen (Jan-Jun): Birds migrate northeast (noordoost)
 */
object SeizoenUtils {
    
    /**
     * Determine if we're in ZW season (Jul-Dec) or NO season (Jan-Jun).
     * Uses current system date.
     * @return true for ZW season, false for NO season
     */
    fun isZwSeizoen(): Boolean {
        val month = Calendar.getInstance().get(Calendar.MONTH) + 1 // 1-12
        return month in 7..12  // Jul-Dec = ZW seizoen
    }
    
    /**
     * Determine if the given epoch timestamp falls in ZW season (Jul-Dec) or NO season (Jan-Jun).
     * 
     * Season rules based on the telling date:
     * - januari -> juni: aantal = 'NO', aantalterug = 'ZW'
     * - juli -> december: aantal = 'ZW', aantalterug = 'NO'
     * 
     * @param epochSeconds The epoch timestamp in seconds (like begintijd from ServerTellingEnvelope)
     * @return true for ZW season (Jul-Dec), false for NO season (Jan-Jun)
     */
    fun isZwSeizoen(epochSeconds: Long): Boolean {
        val cal = Calendar.getInstance()
        cal.timeInMillis = epochSeconds * 1000L
        val month = cal.get(Calendar.MONTH) + 1 // 1-12
        return month in 7..12  // Jul-Dec = ZW seizoen
    }
}
