package com.example.personal_studio.feature.chat.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.personal_studio.ui.theme.Tg
import java.io.File
import java.io.FileOutputStream

/**
 * Copies the picked content Uri into a file under `filesDir/chat-attachments/` so the LLM
 * providers (which read raw bytes) can access it. Returns the absolute path or null on failure.
 */
private fun Context.copyUriToFile(uri: Uri): String? {
    val dir = File(filesDir, "chat-attachments").apply { mkdirs() }
    val dest = File(dir, "img_${System.currentTimeMillis()}.jpg")
    return try {
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(dest).use { out -> input.copyTo(out) }
        }
        dest.absolutePath
    } catch (t: Throwable) { null }
}

/**
 * The system photo picker, copying the chosen image into app storage. Registered by the page,
 * not by the menu, so the menu can close while the picker is open and the result still arrives.
 */
@Composable
fun rememberGalleryPicker(onPicked: (path: String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        uri?.let(context::copyUriToFile)?.let(onPicked)
    }
    return { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
}

/**
 * The ＋ menu: a small card that floats above the composer's left end (the page places it and
 * dismisses it). It lives in the page's own window, so opening it leaves the text field focused
 * and the keyboard up.
 */
@Composable
fun AttachmentMenu(
    onGallery: () -> Unit,
    onCamera: () -> Unit,
    onScans: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Tg.colors
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier
            .width(212.dp)
            .shadow(12.dp, shape, ambientColor = c.scrim, spotColor = c.scrim)
            .clip(shape)
            .background(c.surfaceRaised)
            .border(1.dp, c.line, shape)
            .padding(6.dp),
    ) {
        MenuRow(Icons.Outlined.PhotoLibrary, "从相册选取", onGallery)
        MenuRow(Icons.Outlined.PhotoCamera, "拍一张", onCamera)
        MenuRow(Icons.Outlined.DocumentScanner, "从扫描文档选取", onScans)
    }
}

@Composable
private fun MenuRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    val c = Tg.colors
    Row(
        Modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(32.dp).background(c.accentSoft, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = c.accent, modifier = Modifier.size(17.dp))
        }
        Text(label, style = MaterialTheme.typography.bodyLarge, color = c.text, modifier = Modifier.weight(1f))
    }
}
