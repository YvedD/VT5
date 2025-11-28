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
     * @return true for ZW season, false for NO season
     */
    fun isZwSeizoen(): Boolean {
        val month = Calendar.getInstance().get(Calendar.MONTH) + 1 // 1-12
        return month in 7..12  // Jul-Dec = ZW seizoen
    }
}
