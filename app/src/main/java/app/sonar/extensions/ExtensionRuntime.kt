package app.sonar.extensions

import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.Function
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined
import java.util.Locale

internal class ExtensionRuntime(private val extension: ExtensionPackage) {
    fun status(locale: Locale): String? {
        check(Context.getCurrentContext() == null) { "An extension requires its own Rhino context" }
        val deadline = System.nanoTime() + 1_000_000_000L
        val factory = object : ContextFactory() {
            override fun makeContext(): Context = super.makeContext().apply {
                isInterpretedMode = true
                languageVersion = Context.VERSION_ES6
                instructionObserverThreshold = 5_000
                setMaximumInterpreterStackDepth(128)
                setClassShutter { false }
            }

            override fun observeInstructionCount(cx: Context, instructionCount: Int) {
                if (System.nanoTime() - deadline >= 0 || Thread.currentThread().isInterrupted) {
                    throw TimeLimit()
                }
            }
        }
        try {
            return factory.call { cx ->
                val scope = cx.initSafeStandardObjects()
                val manifest = extension.manifest
                val info = cx.newObject(scope) as ScriptableObject
                info.defineProperty("id", manifest.id, 0)
                info.defineProperty("name", manifest.name, 0)
                info.defineProperty("version", manifest.version, 0)
                scope.defineProperty("__extensionInfo", info, 0)
                scope.defineProperty("__locale", locale.toLanguageTag(), 0)
                val log = object : BaseFunction(scope, ScriptableObject.getFunctionPrototype(scope)) {
                    override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<Any?>): Any =
                        Undefined.instance
                }
                scope.defineProperty("__log", log, 0)
                val dispatch = cx.evaluateString(scope, prelude, "prelude.js", 1, null) as Function
                cx.evaluateString(scope, extension.script, manifest.main, 1, null)
                (dispatch.call(cx, scope, scope, emptyArray()) as? CharSequence)?.let {
                    require(it.length <= 4096) { "Status response is too large" }
                    it.toString()
                }
            }
        } catch (_: TimeLimit) {
            throw IllegalStateException("Extension exceeded its execution deadline")
        }
    }

    private class TimeLimit : Error()

    private companion object {
        val prelude by lazy {
            checkNotNull(ExtensionRuntime::class.java.getResourceAsStream("prelude.js"))
                .bufferedReader().use { it.readText() }
        }
    }
}
