package com.example.ui.wenku

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import kotlin.math.PI
import kotlin.math.sin

/**
 * Material 图标 → [WenkuSymbol] 的映射。
 *
 * 参考仓库里所有「有图标的按钮」都是手绘纸片图标 + 三拍手势（`archive_icons.dart` /
 * `archive_icon_motion.dart`）。本工程大量按钮仍用 Material `Icon`；这里按 `ImageVector.name`
 * （形如 `Filled.Bookmark` / `Outlined.Search`）做一次集中映射，命中就走 `WenkuIcon`，
 * 未命中保持原样 —— 这样调用点不需要知道映射表，也不会因为漏映射而丢图标。
 *
 * 名字匹配用 `endsWith` + 精确名混合：Material 的 `name` 在不同 family 下是
 * `Filled.X` / `Outlined.X` / `AutoMirrored.Filled.X`，只比较最后一段即可。
 */
fun wenkuSymbolForIconName(name: String): WenkuSymbol? {
    val leaf = name.substringAfterLast('.').substringAfterLast('/')
    return when (leaf) {
        // 导航
        "ArrowBack", "ArrowBackIos", "KeyboardArrowLeft", "ChevronLeft", "NavigateBefore" -> WenkuSymbol.Back
        "ArrowForward", "ArrowForwardIos", "KeyboardArrowRight", "ChevronRight", "NavigateNext" -> WenkuSymbol.Forward
        "ArrowUpward" -> WenkuSymbol.ArrowUp
        "KeyboardArrowUp", "ExpandLess" -> WenkuSymbol.Up
        "ArrowDownward" -> WenkuSymbol.ArrowDown
        "KeyboardArrowDown", "ExpandMore" -> WenkuSymbol.Down
        "Close", "Cancel", "Clear" -> WenkuSymbol.Close
        "Menu", "List", "Subject", "FormatListBulleted", "Toc" -> WenkuSymbol.Contents
        "MoreVert", "MoreHoriz", "More" -> WenkuSymbol.More
        "OpenInNew", "Launch", "ExternalLink" -> WenkuSymbol.External

        // 阅读
        "Bookmark", "BookmarkBorder", "BookmarkOutline", "BookmarkAdded" -> WenkuSymbol.Bookmark
        "Headphones", "Headset" -> WenkuSymbol.Headphones
        "VolumeUp", "VolumeOff", "VolumeMute", "VolumeDown" -> WenkuSymbol.Volume
        "PlayArrow", "PlayCircle" -> WenkuSymbol.Play
        "Pause", "PauseCircle" -> WenkuSymbol.Pause
        "Stop", "StopCircle" -> WenkuSymbol.Stop
        "SkipPrevious", "FastRewind" -> WenkuSymbol.Previous
        "SkipNext", "FastForward" -> WenkuSymbol.Next
        "Timer", "Alarm", "Schedule", "AccessTime", "History" -> WenkuSymbol.Timer
        "Speed" -> WenkuSymbol.Speed
        "TextFields", "TextFormat", "FontDownload" -> WenkuSymbol.Typography
        "Tune", "Sliders" -> WenkuSymbol.Sliders
        "Image", "Photo", "InsertPhoto" -> WenkuSymbol.Image
        "Brightness", "BrightnessHigh", "LightMode", "WbSunny" -> WenkuSymbol.Brightness
        "DarkMode", "Nightlight", "Bedtime" -> WenkuSymbol.Moon
        "Layers" -> WenkuSymbol.Layers
        "FormatQuote", "FormatQuoteOutlined" -> WenkuSymbol.Quotes
        "Translate", "Language" -> WenkuSymbol.Language
        "Portrait", "CropPortrait" -> WenkuSymbol.Portrait

        // 操作
        "Search" -> WenkuSymbol.Search
        "FilterAlt", "FilterList", "Sort", "Filter" -> WenkuSymbol.Filter
        "GridView", "ViewModule", "Dashboard" -> WenkuSymbol.Grid
        "Apps", "Widgets" -> WenkuSymbol.DotsGrid
        "Add" -> WenkuSymbol.Add
        "AddCircle", "AddCircleOutline" -> WenkuSymbol.AddCircle
        "Remove", "RemoveCircle", "RemoveCircleOutline", "Delete" -> WenkuSymbol.Delete
        "Check", "Done" -> WenkuSymbol.Check
        "CheckCircle", "TaskAlt" -> WenkuSymbol.CheckCircle
        "Edit", "Create", "DriveFileRenameOutline" -> WenkuSymbol.Edit
        "ContentCopy", "Copy" -> WenkuSymbol.Copy
        "Share" -> WenkuSymbol.Share
        "Link" -> WenkuSymbol.Link
        "Refresh" -> WenkuSymbol.Refresh
        "Sync", "SyncAlt" -> WenkuSymbol.Sync

        // 文件
        "Download", "FileDownload", "CloudDownload" -> WenkuSymbol.Download
        "Upload", "FileUpload", "CloudUpload" -> WenkuSymbol.Upload
        "Folder", "FolderOutlined", "Storage" -> WenkuSymbol.Folder
        "FolderOpen" -> WenkuSymbol.FolderOpen
        "CreateNewFolder", "FolderAdd" -> WenkuSymbol.FolderAdd
        "Description", "Article", "InsertDriveFile", "Document" -> WenkuSymbol.Document
        "AutoFixHigh", "AutoFixNormal", "Wand" -> WenkuSymbol.Wand

        // 系统
        "Settings" -> WenkuSymbol.Settings
        "Info", "InfoOutline" -> WenkuSymbol.Info
        "Warning", "ErrorOutline", "ReportProblem" -> WenkuSymbol.Warning
        "Wifi" -> WenkuSymbol.Wifi
        "WifiOff" -> WenkuSymbol.WifiOff
        "Cloud" -> WenkuSymbol.Cloud
        "Public", "Globe" -> WenkuSymbol.Globe
        "Phone", "Smartphone" -> WenkuSymbol.Phone
        "Computer", "Laptop" -> WenkuSymbol.Laptop
        "AccountTree", "CallSplit" -> WenkuSymbol.Branch
        "PersonAdd" -> WenkuSymbol.PersonAdd
        "Visibility" -> WenkuSymbol.Eye
        "VisibilityOff" -> WenkuSymbol.EyeOff
        "CloseCircle" -> WenkuSymbol.CloseCircle
        "BatteryFull", "BatteryStd" -> WenkuSymbol.Battery
        "Build", "Construction", "Handyman" -> WenkuSymbol.Tools
        "Shield", "Security", "Lock" -> WenkuSymbol.Shield
        "Help", "HelpOutline", "QuestionMark" -> WenkuSymbol.Help
        "TouchApp", "PanTool" -> WenkuSymbol.Touch
        "Person", "AccountCircle" -> WenkuSymbol.Profile
        "Book", "MenuBook", "AutoStories" -> WenkuSymbol.Book

        else -> null
    }
}

/** `ImageVector` 版本：未命中返回 null，调用点据此回退到 Material 图标。 */
fun ImageVector.wenkuSymbolOrNull(): WenkuSymbol? = wenkuSymbolForIconName(name)


/** Draws a button's icon using the parent control's actual press and confirmed click. */
@Composable
internal fun WenkuButtonGlyph(
    icon: ImageVector,
    size: Dp,
    tint: Color = LocalContentColor.current,
    active: Boolean = false,
    symbol: WenkuSymbol? = icon.wenkuSymbolOrNull(),
) {
    if (symbol != null) {
        WenkuIcon(symbol, size = size, tint = tint, active = active, entrance = false)
        return
    }
    val contact = LocalWenkuIconContact.current
    val reduceMotion = rememberWenkuAccessibility().reduceMotion
    val press = animateFloatAsState(
        if (contact.pressed && !reduceMotion) 1f else 0f,
        if (contact.pressed) WenkuMotion.contactSpec() else WenkuMotion.settleSpec(),
        label = "fallbackIconContact",
    )
    val click = remember { Animatable(1f) }
    LaunchedEffect(contact.activation, reduceMotion) {
        if (reduceMotion || contact.activation == 0) {
            click.snapTo(1f)
        } else {
            click.snapTo(0f)
            click.animateTo(1f, tween(640, easing = LinearEasing))
        }
    }
    Icon(
        icon, null, tint = tint,
        modifier = Modifier.size(size).graphicsLayer {
            val tap = sin(click.value * PI).toFloat()
            rotationZ = 10f * press.value
            scaleX = 1f - .08f * press.value + .07f * tap
            scaleY = scaleX
            translationY = -1.6f * tap * density
        },
    )
}
