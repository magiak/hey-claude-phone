package cz.magiak.asistent

import android.content.Context
import org.vosk.Model
import org.vosk.android.StorageService

/** The Vosk model, shared by the listener and the debug recording; loading takes seconds. */
object SpeechModel {
    @Volatile private var model: Model? = null
    private var waiting: MutableList<(Result<Model>) -> Unit>? = null

    // Main thread only; StorageService answers on the main looper too.
    fun get(context: Context, done: (Result<Model>) -> Unit) {
        model?.let { done(Result.success(it)); return }
        waiting?.let { it += done; return }
        waiting = mutableListOf(done)
        // First run copies the model out of the APK (~70 MB), later runs only load it.
        StorageService.unpack(context.applicationContext, "model", "model",
            { loaded -> model = loaded; finish(Result.success(loaded)) },
            { error -> finish(Result.failure(error)) })
    }

    private fun finish(result: Result<Model>) {
        val callbacks = waiting.orEmpty()
        waiting = null
        callbacks.forEach { it(result) }
    }
}
