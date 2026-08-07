package net.typeblog.socks.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import net.typeblog.socks.R

// Vercel Geist — the app's primary UI sans.
object GeistFonts {
    val Family = FontFamily(
        Font(R.font.geist_regular, FontWeight.Normal),
        Font(R.font.geist_medium, FontWeight.Medium),
        Font(R.font.geist_semibold, FontWeight.SemiBold),
        Font(R.font.geist_bold, FontWeight.Bold)
    )
}

// Vercel Geist Mono — terminals/addresses (server:port).
object GeistMonoFonts {
    val Family = FontFamily(
        Font(R.font.geist_mono_regular, FontWeight.Normal),
        Font(R.font.geist_mono_medium, FontWeight.Medium),
        Font(R.font.geist_mono_bold, FontWeight.Bold)
    )
}

// Vercel Geist Pixel (Square) — brand/logo accents only.
object GeistPixelFonts {
    val Family = FontFamily(
        Font(R.font.geist_pixel_square, FontWeight.Normal)
    )
}