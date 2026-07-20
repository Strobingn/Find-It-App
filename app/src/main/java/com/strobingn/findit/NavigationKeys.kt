package com.strobingn.findit

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable data object Home : NavKey

@Serializable data object Finds : NavKey

@Serializable data class FindDetail(val findId: String) : NavKey

@Serializable data object LogFind : NavKey

@Serializable data object GridPlanner : NavKey

@Serializable data object TerrainTools : NavKey

@Serializable data object HistoricalMaps : NavKey

@Serializable data object TeamVisibility : NavKey

@Serializable data object ArOverlay : NavKey

@Serializable data object Export : NavKey

@Serializable data object Settings : NavKey
