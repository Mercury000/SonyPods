package dev.sonypods.ui.pages

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.sonypods.R
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card

@Composable
fun AboutPage(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val context = LocalContext.current

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding() + 12.dp,
            bottom = contentPadding.calculateBottomPadding() + 12.dp,
            start = 12.dp,
            end = 12.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card {
                BasicComponent(
                    title = "SonyPods-Enhanced",
                    summary = "https://github.com/1812z/OppoPods",
                    onClick = {
                        Intent(Intent.ACTION_VIEW).apply {
                            this.data = Uri.parse("https://github.com/1812z/OppoPods")
                            context.startActivity(this)
                        }
                    }
                )
                BasicComponent(
                    title = "SonyPods",
                    summary = "https://github.com/Leaf-lsgtky/OppoPods",
                    onClick = {
                        Intent(Intent.ACTION_VIEW).apply {
                            this.data = Uri.parse("https://github.com/Leaf-lsgtky/OppoPods")
                            context.startActivity(this)
                        }
                    }
                )
                BasicComponent(
                    title = stringResource(R.string.based_on),
                    summary = "HyperPods by Art_Chen"
                )
                BasicComponent(
                    title = "Github",
                    summary = "https://github.com/Art-Chen/HyperPods",
                    onClick = {
                        Intent(Intent.ACTION_VIEW).apply {
                            this.data = Uri.parse("https://github.com/Art-Chen/HyperPods")
                            context.startActivity(this)
                        }
                    }
                )
            }
        }
    }
}
