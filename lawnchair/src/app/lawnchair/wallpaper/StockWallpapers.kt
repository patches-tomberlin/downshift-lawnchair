package app.lawnchair.wallpaper

import com.android.launcher3.R

data class StockWallpaper(val id: String, val label: String, val drawableRes: Int)

val STOCK_WALLPAPERS = listOf(
    StockWallpaper("blue", "Blue", R.drawable.wallpaper_blue),
    StockWallpaper("green", "Green", R.drawable.wallpaper_green),
    StockWallpaper("gray", "Gray", R.drawable.wallpaper_gray),
    StockWallpaper("purple", "Purple", R.drawable.wallpaper_purple),
    StockWallpaper("slate", "Slate", R.drawable.wallpaper_slate),
)
