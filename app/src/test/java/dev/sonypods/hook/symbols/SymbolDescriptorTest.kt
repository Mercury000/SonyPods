package dev.sonypods.hook.symbols

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SymbolDescriptorTest {
    @Test
    fun acceptsCanonicalDexDescriptors() {
        assertTrue(SymbolDescriptor.isValid(SymbolKind.CLASS, "Lcom/example/Foo;"))
        assertTrue(SymbolDescriptor.isValid(SymbolKind.METHOD, "Lcom/example/Foo;->a(Ljava/lang/String;[I)Z"))
        assertTrue(SymbolDescriptor.isValid(SymbolKind.FIELD, "Lcom/example/Foo;->a:[Ljava/lang/String;"))
    }

    @Test
    fun rejectsMalformedOrVoidFieldsAndParameters() {
        assertFalse(SymbolDescriptor.isValid(SymbolKind.CLASS, "com.example.Foo"))
        assertFalse(SymbolDescriptor.isValid(SymbolKind.METHOD, "Lx/Y;->a(V)V"))
        assertFalse(SymbolDescriptor.isValid(SymbolKind.FIELD, "Lx/Y;->a:V"))
        assertFalse(SymbolDescriptor.isValid(SymbolKind.METHOD, "Lx/Y;->a(Ljava/lang/String)V"))
    }
}