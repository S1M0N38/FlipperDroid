package com.example.flipperdroid.ui.theme

import androidx.compose.ui.graphics.Color

val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)

val Purple40 = Color(0xFF6650a4)
val PurpleGrey40 = Color(0xFF625b71)
val Pink40 = Color(0xFF7D5260)

// Flipper Zero primary colors
val FlipperOrange = Color(0xFFFF8C00)
val FlipperBlack = Color(0xFF000000)
val FlipperWhite = Color(0xFFFFFFFF)
val FlipperGray = Color(0xFF808080)

// Secondary colors
val FlipperDarkOrange = Color(0xFFCC7000)
val FlipperLightOrange = Color(0xFFFFAA44)
val FlipperDarkGray = Color(0xFF404040)
val FlipperLightGray = Color(0xFFE0E0E0)

// Functional colors
val FlipperSuccess = Color(0xFF4CAF50)
val FlipperError = Color(0xFFF44336)
val FlipperLightError = Color(0xFFFF8A80)
val FlipperWarning = Color(0xFFFFEB3B)
val FlipperInfo = Color(0xFF2196F3)

// Dark background colors — refined palette: a near-black base then
// increasingly lighter "elevated" surfaces (adds depth, avoids
// the washed-out 0x808080 grey of cards on a pure black background).
val FlipperNearBlack = Color(0xFF0D0D0F)      // app background
val FlipperSurfaceDark = Color(0xFF17171A)    // base surface (bars, sheets)
val FlipperCardDark = Color(0xFF212127)       // cards / tiles
val FlipperOutlineDark = Color(0xFF33333A)    // subtle borders
val FlipperOnSurfaceMuted = Color(0xFFB9B9C3) // readable secondary text

val FlipperBackground = FlipperNearBlack
val FlipperSurface = FlipperSurfaceDark
val FlipperCardBackground = FlipperCardDark

// Light background colors
val FlipperLightBackground = Color(0xFFF5F5F5)
val FlipperLightSurface = Color(0xFFFFFFFF)
val FlipperLightCardBackground = Color(0xFFE0E0E0)