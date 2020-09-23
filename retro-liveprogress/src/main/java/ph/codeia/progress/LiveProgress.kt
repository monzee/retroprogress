@file:JvmMultifileClass
@file:JvmName("LiveProgress")

package ph.codeia.progress

import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/*
 * This file is a part of the RetroProgress project.
 */


typealias LiveProgress<T> = LiveData<Progress<T>>

interface ProgressObserver<T> : Observer<Progress<T>>, Progress.When<T> {
	@JvmDefault
	override fun onChanged(t: Progress<T>?) {
		t?.select(this)
	}
}

fun <T> LiveProgress<T>.collect(k: Progress.When<T>) {
	observeForever(object : Observer<Progress<T>> {
		override fun onChanged(t: Progress<T>?) {
			t?.select(k)
			t?.takeIf { it.isTerminal }?.let {
				removeObserver(this)
			}
		}
	})
}

@JvmSynthetic
suspend fun <T> LiveProgress<T>.await(): T = suspendCoroutine { k ->
	collect(object : Progress.When<T> {
		override fun `when`(error: Progress.Failed) {
			k.resumeWithException(error.reason)
		}

		override fun `when`(result: Progress.Done<out T>) {
			try {
				k.resume(result.value)
			}
			catch (noContent: IllegalStateException) {
				k.resumeWithException(noContent)
			}
		}
	})
}

