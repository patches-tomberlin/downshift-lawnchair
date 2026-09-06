package app.lawnchair.wallpaper

import com.android.launcher3.R

data class StockWallpaper(val id: String, val label: String, val drawableRes: Int)

val STOCK_WALLPAPERS = listOf(
    StockWallpaper("blu", "Blue", R.drawable.wallpaper_blu),
    StockWallpaper("grn", "Green", R.drawable.wallpaper_grn),
    StockWallpaper("gry", "Gray", R.drawable.wallpaper_gry),
    StockWallpaper("purp", "Purple", R.drawable.wallpaper_purp),
)
