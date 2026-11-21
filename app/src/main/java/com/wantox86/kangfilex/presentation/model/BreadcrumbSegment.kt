package com.wantox86.kangfilex.presentation.model

data class BreadcrumbSegment(
    val label: String,
    val path: String,
    val isRoot: Boolean = false
)
