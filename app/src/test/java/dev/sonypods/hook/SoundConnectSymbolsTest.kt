package dev.sonypods.hook

import dev.sonypods.hook.symbols.SymbolKind
import dev.sonypods.hook.symbols.SymbolReference
import org.junit.Assert.assertThrows
import org.junit.Test

class SoundConnectSymbolsTest {
    @Test
    fun serviceSymbolsMatchStableAndroidServiceAbi() {
        SoundConnectServiceSymbols.validate(SoundConnectServiceSymbols.symbols)
    }

    @Test
    fun sessionSymbolsAcceptOneConsistentControllerContract() {
        SoundConnectSessionSymbols.validate(validSessionSymbols())
    }

    @Test
    fun sessionSymbolsRejectMethodsFromAnotherController() {
        val symbols = validSessionSymbols().toMutableMap()
        symbols["firstSession"] = SymbolReference(
            SymbolKind.METHOD,
            "Lcom/example/Other;->d()Ljava/util/Map\$Entry;",
        )

        assertThrows(IllegalArgumentException::class.java) {
            SoundConnectSessionSymbols.validate(symbols)
        }
    }

    @Test
    fun sessionSymbolsRejectIncompatibleAddAndRemoveTypes() {
        val symbols = validSessionSymbols().toMutableMap()
        symbols["removeSession"] = SymbolReference(
            SymbolKind.METHOD,
            "Lcom/example/Controller;->b(Lcom/example/DeviceId;)Lcom/example/OtherSession;",
        )

        assertThrows(IllegalArgumentException::class.java) {
            SoundConnectSessionSymbols.validate(symbols)
        }
    }

    private fun validSessionSymbols(): Map<String, SymbolReference> = mapOf(
        "controller" to SymbolReference(SymbolKind.CLASS, "Lcom/example/Controller;"),
        "addSession" to SymbolReference(
            SymbolKind.METHOD,
            "Lcom/example/Controller;->a(Lcom/example/DeviceId;Lcom/example/Session;)V",
        ),
        "removeSession" to SymbolReference(
            SymbolKind.METHOD,
            "Lcom/example/Controller;->b(Lcom/example/DeviceId;)Lcom/example/Session;",
        ),
        "clearSessions" to SymbolReference(SymbolKind.METHOD, "Lcom/example/Controller;->c()V"),
        "firstSession" to SymbolReference(
            SymbolKind.METHOD,
            "Lcom/example/Controller;->d()Ljava/util/Map\$Entry;",
        ),
    )
}
