package com.yvesds.vt5.features.telling

import android.app.Activity
import android.content.Intent
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.flexbox.AlignItems
import com.google.android.flexbox.FlexDirection
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayoutManager
import com.google.android.flexbox.JustifyContent
import com.yvesds.vt5.databinding.SchermTellingBinding
import kotlinx.coroutines.launch

/**
 * TellingUiManager: Manages UI setup for TellingScherm.
 * 
 * Responsibilities:
 * - Setting up RecyclerViews (partials, finals, tiles)
 * - Configuring adapters and layout managers
 * - Setting up gesture detectors for tap handling
 * - Button click handling setup
 */
class TellingUiManager(
    private val activity: Activity,
    private val lifecycleOwner: LifecycleOwner,
    private val binding: SchermTellingBinding
) {
    companion object {
        private const val TAG = "TellingUiManager"
    }

    private lateinit var partialsAdapter: SpeechLogAdapter
    private lateinit var finalsAdapter: SpeechLogAdapter
    private lateinit var tilesAdapter: SpeciesTileAdapter

    // Callbacks
    var onPartialTapCallback: ((Int, TellingScherm.SpeechLogRow) -> Unit)? = null
    var onFinalTapCallback: ((Int, TellingScherm.SpeechLogRow) -> Unit)? = null
    var onTileTapCallback: ((Int) -> Unit)? = null
    var onAddSoortenCallback: (() -> Unit)? = null
    var onAfrondenCallback: (() -> Unit)? = null
    var onSaveCloseCallback: ((List<TellingScherm.SoortRow>) -> Unit)? = null

    /**
     * Setup partials RecyclerView with tap handling.
     */
    fun setupPartialsRecyclerView() {
        val layoutManager = LinearLayoutManager(activity)
        layoutManager.stackFromEnd = true
        binding.recyclerViewSpeechPartials.layoutManager = layoutManager
        binding.recyclerViewSpeechPartials.setHasFixedSize(true)
        
        partialsAdapter = SpeechLogAdapter()
        partialsAdapter.showPartialsInRow = true
        binding.recyclerViewSpeechPartials.adapter = partialsAdapter

        // Gesture handling for partials
        val gestureDetector = GestureDetector(activity, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val child = binding.recyclerViewSpeechPartials.findChildViewUnder(e.x, e.y)
                if (child != null) {
                    val pos = binding.recyclerViewSpeechPartials.getChildAdapterPosition(child)
                    if (pos != RecyclerView.NO_POSITION) {
                        val row = partialsAdapter.currentList.getOrNull(pos) ?: return true
                        onPartialTapCallback?.invoke(pos, row)
                    }
                }
                return true
            }
        })

        binding.recyclerViewSpeechPartials.addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                return gestureDetector.onTouchEvent(e)
            }
            override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {}
            override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}
        })
    }

    /**
     * Setup finals RecyclerView with tap handling.
     */
    fun setupFinalsRecyclerView() {
        val layoutManager = LinearLayoutManager(activity)
        layoutManager.stackFromEnd = true
        binding.recyclerViewSpeechFinals.layoutManager = layoutManager
        binding.recyclerViewSpeechFinals.setHasFixedSize(true)
        
        finalsAdapter = SpeechLogAdapter()
        finalsAdapter.showPartialsInRow = false
        binding.recyclerViewSpeechFinals.adapter = finalsAdapter

        // Gesture handling for finals
        val gestureDetector = GestureDetector(activity, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val child = binding.recyclerViewSpeechFinals.findChildViewUnder(e.x, e.y)
                if (child != null) {
                    val pos = binding.recyclerViewSpeechFinals.getChildAdapterPosition(child)
                    if (pos != RecyclerView.NO_POSITION) {
                        val row = finalsAdapter.currentList.getOrNull(pos) ?: return true
                        onFinalTapCallback?.invoke(pos, row)
                    }
                }
                return true
            }
        })

        binding.recyclerViewSpeechFinals.addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                return gestureDetector.onTouchEvent(e)
            }
            override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {}
            override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}
        })
    }

    /**
     * Setup species tiles RecyclerView with Flexbox layout.
     */
    fun setupSpeciesTilesRecyclerView() {
        val flexboxLayoutManager = FlexboxLayoutManager(activity).apply {
            flexDirection = FlexDirection.ROW
            flexWrap = FlexWrap.WRAP
            justifyContent = JustifyContent.FLEX_START
            alignItems = AlignItems.STRETCH
        }
        binding.recyclerViewSpeciesTiles.layoutManager = flexboxLayoutManager

        tilesAdapter = SpeciesTileAdapter { position ->
            onTileTapCallback?.invoke(position)
        }
        binding.recyclerViewSpeciesTiles.adapter = tilesAdapter
    }

    /**
     * Setup button click handlers.
     */
    fun setupButtons() {
        binding.btnAddSoorten.setOnClickListener {
            onAddSoortenCallback?.invoke()
        }

        binding.btnAfronden.setOnClickListener {
            onAfrondenCallback?.invoke()
        }

        binding.btnSaveClose.setOnClickListener {
            val current = tilesAdapter.currentList
            onSaveCloseCallback?.invoke(current)
        }
    }

    /**
     * Update partials adapter with new list.
     */
    fun updatePartials(list: List<TellingScherm.SpeechLogRow>) {
        partialsAdapter.submitList(list)
        // Auto-scroll to bottom
        if (list.isNotEmpty()) {
            binding.recyclerViewSpeechPartials.scrollToPosition(list.size - 1)
        }
    }

    /**
     * Update finals adapter with new list.
     */
    fun updateFinals(list: List<TellingScherm.SpeechLogRow>) {
        finalsAdapter.submitList(list)
        // Auto-scroll to bottom
        if (list.isNotEmpty()) {
            binding.recyclerViewSpeechFinals.scrollToPosition(list.size - 1)
        }
    }

    /**
     * Update tiles adapter with new list.
     */
    fun updateTiles(list: List<TellingScherm.SoortRow>) {
        tilesAdapter.submitList(list)
    }

    /**
     * Get current tiles list.
     */
    fun getCurrentTiles(): List<TellingScherm.SoortRow> {
        return tilesAdapter.currentList
    }

    /**
     * Get current partials list.
     */
    fun getCurrentPartials(): List<TellingScherm.SpeechLogRow> {
        return partialsAdapter.currentList
    }

    /**
     * Get current finals list.
     */
    fun getCurrentFinals(): List<TellingScherm.SpeechLogRow> {
        return finalsAdapter.currentList
    }
}
