package dev.sonypods.ui.pages

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mercury.sonypods.BuildConfig
import com.mercury.sonypods.R
import dev.sonypods.ui.components.SectionTitle
import dev.sonypods.ui.components.effect.BgEffectBackground
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurBlendMode
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

// ---------------------------------------------------------------------------
// Structure copied from the miuix example app's AboutPage (example/shared/src/
// commonMain/kotlin/AboutPage.kt): transparent collapsing top bar, fixed hero
// (icon / wordmark / version) that fades out element by element, OS3 flowing
// background, and a first list item anchored to the screen bottom (Spacer +
// fillParentMaxHeight) so the cards sit at the bottom of the first screen and
// scroll up as one. Only the artwork (our glass logo mark + wordmark) and the
// card rows differ from the original.
// ---------------------------------------------------------------------------

@Composable
fun AboutPage(
    padding: PaddingValues,
    isActive: Boolean,
    onOpenReferences: () -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val topAppBarScrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val lazyListState = rememberLazyListState()

    // 0 = resting, 1 = hero scrolled away; normalized over the hero spacer (the
    // example page's driver).
    val scrollProgress by remember {
        derivedStateOf {
            when {
                lazyListState.firstVisibleItemIndex > 0 -> 1f

                else -> {
                    val spacer = lazyListState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == LOGO_SPACER_KEY }
                    if (spacer != null && spacer.size > 0) {
                        (lazyListState.firstVisibleItemScrollOffset.toFloat() / spacer.size).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                }
            }
        }
    }
    val collapsed by remember { derivedStateOf { scrollProgress == 1f } }

    val darkMode = isSystemInDarkTheme()
    val surface = MiuixTheme.colorScheme.surface
    val backdrop = if (isRuntimeShaderSupported()) {
        rememberLayerBackdrop {
            drawRect(surface)
            drawContent()
        }
    } else {
        null
    }

    var heroHeightDp by remember { mutableStateOf(280.dp) }

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = stringResource(R.string.about_tab),
                scrollBehavior = topAppBarScrollBehavior,
                color = if (collapsed) surface else Color.Transparent,
                titleColor = MiuixTheme.colorScheme.onSurface.copy(
                    alpha = ((scrollProgress - 0.35f) / 0.65f).coerceIn(0f, 1f),
                ),
            )
        },
    ) { innerPadding ->
        val contentTopPadding = innerPadding.calculateTopPadding()

        BgEffectBackground(
            dynamicBackground = isActive,
            isFullSize = true,
            isDarkTheme = darkMode,
            modifier = Modifier.fillMaxSize(),
            bgModifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier,
            alpha = { 1f - scrollProgress },
        ) {
            // Fixed hero. Each element fades out in sequence — version first, then
            // the wordmark, then the icon — exactly like the example page.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = contentTopPadding + HERO_EXTRA_TOP + 52.dp)
                    .onSizeChanged { size ->
                        with(density) { heroHeightDp = size.height.toDp() }
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                GlassArtwork(
                    resourceId = R.drawable.about_logo_mark,
                    backdrop = backdrop,
                    darkMode = darkMode,
                    blurRadius = 200f,
                    shape = RectangleShape,
                    modifier = Modifier
                        .size(88.dp)
                        .graphicsLayer {
                            val progress = ((scrollProgress - 0.35f) / 0.15f).coerceIn(0f, 1f)
                            alpha = 1f - progress
                            scaleX = 1f - (progress * 0.05f)
                            scaleY = 1f - (progress * 0.05f)
                        },
                )
                Spacer(Modifier.height(12.dp))
                GlassArtwork(
                    resourceId = R.drawable.about_wordmark,
                    backdrop = backdrop,
                    darkMode = darkMode,
                    blurRadius = 150f,
                    shape = RoundedCornerShape(16.dp),
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier
                        .width(193.dp)
                        .height(40.dp)
                        .graphicsLayer {
                            val progress = ((scrollProgress - 0.20f) / 0.15f).coerceIn(0f, 1f)
                            alpha = 1f - progress
                            scaleX = 1f - (progress * 0.05f)
                            scaleY = 1f - (progress * 0.05f)
                        },
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    text = "${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})",
                    modifier = Modifier.graphicsLayer {
                        val progress = ((scrollProgress - 0.05f) / 0.15f).coerceIn(0f, 1f)
                        alpha = 1f - progress
                        scaleX = 1f - (progress * 0.05f)
                        scaleY = 1f - (progress * 0.05f)
                    },
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 14.sp,
                )
            }

            LazyColumn(
                state = lazyListState,
                modifier = Modifier
                    .fillMaxSize()
                    .overScrollVertical()
                    .nestedScroll(topAppBarScrollBehavior.nestedScrollConnection),
                contentPadding = PaddingValues(
                    top = contentTopPadding,
                    start = 16.dp,
                    end = 16.dp,
                ),
            ) {
                item(key = LOGO_SPACER_KEY) {
                    // Hero area + the example page's 126dp scroll budget.
                    Spacer(
                        Modifier.height(
                            heroHeightDp + 52.dp + HERO_EXTRA_TOP + 126.dp,
                        ),
                    )
                }
                item(key = "about") {
                    Box {
                        Spacer(Modifier.fillParentMaxHeight())
                        Column(
                            modifier = Modifier.padding(
                                bottom = padding.calculateBottomPadding() + 28.dp,
                            ),
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                SectionTitle(stringResource(R.string.about_developer))
                                DeveloperCard()
                            }
                            Spacer(Modifier.height(12.dp))
                            Card(modifier = Modifier.fillMaxWidth()) {
                                ArrowPreference(
                                    title = stringResource(R.string.about_source_code),
                                    endActions = {
                                        Text(
                                            text = "GitHub",
                                            fontSize = MiuixTheme.textStyles.body2.fontSize,
                                            color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                                        )
                                    },
                                    onClick = { context.openLink(SOURCE_URL) },
                                )
                                ArrowPreference(
                                    title = stringResource(R.string.about_references),
                                    onClick = onOpenReferences,
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Artwork whose pixels are replaced by a frosted sample of the background behind
 * it (the example app's glass title treatment): the image's alpha acts as the mask
 * via [BlendMode.DstIn].
 */
@Composable
private fun GlassArtwork(
    resourceId: Int,
    backdrop: LayerBackdrop?,
    darkMode: Boolean,
    blurRadius: Float,
    shape: Shape,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
) {
    val blendColors = remember(darkMode) { aboutArtworkBlendColors(darkMode) }
    val effectModifier = if (backdrop != null) {
        Modifier.textureBlur(
            backdrop = backdrop,
            shape = shape,
            blurRadius = blurRadius,
            noiseCoefficient = 0f,
            colors = BlurDefaults.blurColors(blendColors = blendColors),
            contentBlendMode = BlendMode.DstIn,
        )
    } else {
        Modifier
    }
    Image(
        painter = painterResource(resourceId),
        contentDescription = null,
        contentScale = contentScale,
        modifier = modifier.then(effectModifier),
    )
}

private fun aboutArtworkBlendColors(darkMode: Boolean): List<BlendColorEntry> =
    if (darkMode) {
        listOf(
            BlendColorEntry(Color(0xE6A1A1A1), BlurBlendMode.ColorDodge),
            BlendColorEntry(Color(0x4DE6E6E6), BlurBlendMode.LinearLight),
            BlendColorEntry(Color(0xFF1AF500), BlurBlendMode.Lab),
        )
    } else {
        listOf(
            BlendColorEntry(Color(0xCC4A4A4A), BlurBlendMode.ColorBurn),
            BlendColorEntry(Color(0xFF4F4F4F), BlurBlendMode.LinearLight),
            BlendColorEntry(Color(0xFF1AF200), BlurBlendMode.Lab),
        )
    }

@Composable
private fun DeveloperCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(),
        onClick = { context.openLink(DEVELOPER_URL) },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(R.drawable.about_developer_avatar),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .border(2.dp, Color.White.copy(alpha = 0.68f), CircleShape),
            )
            Column(modifier = Modifier.padding(start = 14.dp)) {
                Text(
                    text = stringResource(R.string.about_developer_name),
                    fontSize = 19.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Text(
                    text = "@" + stringResource(R.string.about_developer_id),
                    modifier = Modifier.padding(top = 1.dp),
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = "›",
                fontSize = 32.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantActions,
            )
        }
    }
}

/**
 * Opens a link without letting a missing browser take the app down: with Android 11
 * package visibility an unresolvable VIEW intent throws instead of doing nothing.
 */
private fun Context.openLink(url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, R.string.link_open_failed, Toast.LENGTH_SHORT).show()
    } catch (_: SecurityException) {
        Toast.makeText(this, R.string.link_open_failed, Toast.LENGTH_SHORT).show()
    }
}

private const val LOGO_SPACER_KEY = "logoSpacer"
private const val DEVELOPER_URL = "https://github.com/Mercury000"
private const val SOURCE_URL = "https://github.com/Mercury000/SonyPods"
private val HERO_EXTRA_TOP = 40.dp
