package moe.chenxy.oppopods.ui

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

object AppLocale {
    const val SYSTEM = 0
    const val CHINESE = 1
    const val ENGLISH = 2

    private var deviceLocale: Locale? = null

    fun rememberDeviceLocale(context: Context) {
        if (deviceLocale == null) {
            deviceLocale = context.resources.configuration.locales[0] ?: Locale.getDefault()
        }
    }

    fun apply(context: Context, language: Int) {
        rememberDeviceLocale(context)
        val locale = when (language) {
            CHINESE -> Locale.SIMPLIFIED_CHINESE
            ENGLISH -> Locale.ENGLISH
            else -> systemLocale()
        }
        val configuration = Configuration(context.resources.configuration)
        val localeList = LocaleList(locale)
        LocaleList.setDefault(localeList)
        configuration.setLocales(localeList)
        context.resources.updateConfiguration(configuration, context.resources.displayMetrics)
    }

    fun applyAndRecreate(activity: Activity, language: Int) {
        apply(activity, language)
        activity.recreate()
    }

    @Composable
    fun Provider(language: Int, content: @Composable () -> Unit) {
        val context = LocalContext.current
        rememberDeviceLocale(context)
        val activityResultRegistryOwner = LocalActivityResultRegistryOwner.current
        val baseConfiguration = LocalConfiguration.current
        val configuration = remember(baseConfiguration, language) {
            Configuration(baseConfiguration).apply {
                when (language) {
                    CHINESE -> setLocales(LocaleList(Locale.SIMPLIFIED_CHINESE))
                    ENGLISH -> setLocales(LocaleList(Locale.ENGLISH))
                    else -> setLocales(LocaleList(systemLocale()))
                }
            }
        }
        val localizedContext = remember(context, configuration) {
            context.createConfigurationContext(configuration)
        }
        if (activityResultRegistryOwner != null) {
            CompositionLocalProvider(
                LocalConfiguration provides configuration,
                LocalContext provides localizedContext,
                LocalActivityResultRegistryOwner provides activityResultRegistryOwner,
            ) {
                content()
            }
        } else {
            CompositionLocalProvider(
                LocalConfiguration provides configuration,
                LocalContext provides localizedContext,
            ) {
                content()
            }
        }
    }

    private fun systemLocale(): Locale {
        return deviceLocale
            ?: Resources.getSystem().configuration.locales[0]
            ?: Locale.getDefault()
    }
}
