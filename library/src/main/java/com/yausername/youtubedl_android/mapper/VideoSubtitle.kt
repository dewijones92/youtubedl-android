package com.yausername.youtubedl_android.mapper

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
class VideoSubtitle {
    val ext: String? = null
    val url: String? = null
    val name: String? = null
} 