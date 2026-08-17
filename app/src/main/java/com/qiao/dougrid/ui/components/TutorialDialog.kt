package com.qiao.dougrid.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private data class TutorialPage(
    val icon: ImageVector,
    val title: String,
    val body: String,
    val tips: List<String>,
)

private val tutorialPages = listOf(
    TutorialPage(
        icon = Icons.Default.PhotoLibrary,
        title = "导入一张图片",
        body = "从首页点“导入图片”选择本机文件，或直接拍照。乔格会读取原图方向并生成实时预览。",
        tips = listOf("支持常见照片格式", "图片只在本机处理"),
    ),
    TutorialPage(
        icon = Icons.Default.CropFree,
        title = "框选需要的画面",
        body = "进入“裁剪”，在框内拖动可移动范围，拖动四角可缩放；在框外拖动可以重新画一个取景框。",
        tips = listOf("先裁掉桌面和多余背景", "尺寸变化后可重新框选"),
    ),
    TutorialPage(
        icon = Icons.Default.Edit,
        title = "调整图纸",
        body = "选择品牌色卡和网格尺寸，再用画笔、橡皮、填充、吸色与同色替换修整格子。",
        tips = listOf("画笔和橡皮会实时刷新", "撤销和重做保留整次笔画"),
    ),
    TutorialPage(
        icon = Icons.Default.Inventory2,
        title = "备料并开始拼",
        body = "生成图纸后会自动列出豆子型号、颗数、库存缺口和购买袋数，再按项目设置的实体板尺寸跟做。",
        tips = listOf("先在豆仓填写现有数量", "右上角问号可再次打开教程"),
    ),
)

@Composable
fun TutorialDialog(onFinish: () -> Unit) {
    var pageIndex by rememberSaveable { mutableIntStateOf(0) }
    val page = tutorialPages[pageIndex]
    val scrollState = rememberScrollState()

    LaunchedEffect(pageIndex) {
        scrollState.scrollTo(0)
    }

    Dialog(
        onDismissRequest = onFinish,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth().fillMaxHeight(0.9f),
                shape = MaterialTheme.shapes.small,
                tonalElevation = 6.dp,
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("乔格使用教程", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        IconButton(onClick = onFinish) { Icon(Icons.Default.Close, contentDescription = "关闭教程") }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        tutorialPages.indices.forEach { index ->
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(4.dp)
                                    .background(
                                        if (index <= pageIndex) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.surfaceVariant,
                                        MaterialTheme.shapes.extraSmall,
                                    ),
                            )
                        }
                    }
                    Column(
                        modifier = Modifier.weight(1f).verticalScroll(scrollState),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Surface(
                            modifier = Modifier.size(64.dp),
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.primaryContainer,
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    page.icon,
                                    contentDescription = null,
                                    modifier = Modifier.size(32.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            Text(page.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text(page.body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            page.tips.forEach { tip ->
                                Row(verticalAlignment = Alignment.Top) {
                                    Text("•", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.size(8.dp))
                                    Text(tip, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        if (pageIndex > 0) {
                            TextButton(onClick = { pageIndex-- }) { Text("上一步") }
                        }
                        Spacer(Modifier.weight(1f))
                        Button(
                            onClick = {
                                if (pageIndex == tutorialPages.lastIndex) onFinish() else pageIndex++
                            },
                        ) {
                            Text(if (pageIndex == tutorialPages.lastIndex) "开始使用" else "下一步")
                        }
                    }
                }
            }
        }
    }
}
