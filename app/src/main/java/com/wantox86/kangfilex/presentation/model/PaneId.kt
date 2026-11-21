package com.wantox86.kangfilex.presentation.model

enum class PaneId {
    LEFT,
    RIGHT;

    fun opposite(): PaneId = when (this) {
        LEFT -> RIGHT
        RIGHT -> LEFT
    }
}
