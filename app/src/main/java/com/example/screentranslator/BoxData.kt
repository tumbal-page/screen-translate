package com.example.screentranslator

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class BoxData(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val text: String
) : Parcelable
