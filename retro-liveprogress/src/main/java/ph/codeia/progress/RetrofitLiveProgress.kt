@file:JvmMultifileClass
@file:JvmName("LiveProgress")

package ph.codeia.progress

import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Okio
import retrofit2.*
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import okhttp3.Call.Factory as CallFactory

/*
 * This file is a part of the RetroProgress project.
 */


@JvmName("install")
fun Retrofit.Builder.installLiveProgress() = apply {
	addCallAdapterFactory(LiveProgressInstaller)
}

@JvmName("install")
fun Retrofit.Builder.installLiveProgress(client: OkHttpClient) = apply {
	installLiveProgress(client.newBuilder())
}

@JvmName("install")
fun Retrofit.Builder.installLiveProgress(okHttp: OkHttpClient.Builder) = apply {
	installLiveProgress()
	okHttp.addInterceptor(LiveProgressInstaller.observeTagged)
		.build()
		.let {
			client(it)
			callFactory(LiveProgressInstaller.tagRequests(it))
		}
}


private object LiveProgressInstaller : CallAdapter.Factory() {
	val observeTagged = Interceptor {
		val request = it.request()
		val response = it.proceed(request)
		response.body()
			?.let { body ->
				request.tag(LiveTag::class.java)?.let { tag ->
					response.newBuilder()
						.body(tag.observe(body))
						.build()
				}
			}
			?: response
	}

	override fun get(
		returnType: Type,
		annotations: Array<Annotation>,
		retrofit: Retrofit
	): CallAdapter<*, *>? = run {
		(returnType as? ParameterizedType)
			?.takeIf { getRawType(it) == LiveData::class.java }
			?.let { getParameterUpperBound(0, it) as? ParameterizedType }
			?.takeIf { getRawType(it) == Progress::class.java }
			?.let {
				LiveProgressAdapter(
					getRawType(getParameterUpperBound(0, it)),
					annotations
				)
			}
	}

	fun tagRequests(client: OkHttpClient) = CallFactory {
		val request = it.newBuilder()
			.tag(LiveTag::class.java, LiveTag())
			.build()
		client.newCall(request)
	}
}


private class LiveProgressAdapter<T>(
	private val type: Class<T>,
	private val annotations: Array<Annotation> = emptyArray()
) : CallAdapter<T, LiveProgress<T>> {
	override fun adapt(call: Call<T>): LiveData<Progress<T>> = run {
		CallProgress(
			call,
			annotations.any { it is ManualStart },
			annotations.any { it is NoRetry }
		)
	}

	override fun responseType(): Type = run {
		type
	}
}


private class CallProgress<T>(
	val source: Call<T>,
	val manualStart: Boolean = false,
	val noRetry: Boolean = false
) : MediatorLiveData<Progress<T>>() {
	val start = MutableLiveData<Int>()
	var inFlight: Call<*>? = null

	init {
		addSource(start) { retries ->
			val call = source.clone();
			var totalRead = 0L
			inFlight?.cancel()
			inFlight = call
			value = Progress.Busy(0, Progress.InitialWork, ::cancel)
			call.withTag<LiveTag> { tag ->
				addSource(tag.bytesRead) {
					if (it < 0) removeSource(tag.bytesRead)
					else {
						totalRead += it
						value = Progress.Busy(totalRead, tag.totalBytes, ::cancel)
					}
				}
			}
			call.enqueue(object : Callback<T> {
				override fun onFailure(call: Call<T>, t: Throwable) {
					value =
						if (noRetry) Progress.Failed(t, retries)
						else Progress.Failed(
							t,
							retries,
							true,
							retry = { start.value = retries + 1 },
							abort = { value = Progress.Failed(t, retries) }
						)
				}

				override fun onResponse(call: Call<T>, response: Response<T>) {
					if (response.isSuccessful) {
						value = response.body()
							?.let { Progress.Done(it) }
							?: Progress.Done
					}
					else onFailure(call, ServiceException(
						response.message() ?: "(No status message)",
						response.code(),
						response.errorBody()?.string()
					))
				}
			})
		}
	}

	override fun onActive() {
		when {
			value != null -> return
			manualStart -> value = Progress.New {
				start.value = 0
			}
			else -> {
				value = Progress.New
				start.value = 0
			}
		}
		super.onActive()
	}

	fun cancel() {
		inFlight?.let { call ->
			call.cancel()
			call.withTag<LiveTag> {
				removeSource(it.bytesRead)
			}
		}
		removeSource(start)
	}
}


private class LiveTag {
	var totalBytes = 0L
	val bytesRead = MutableLiveData<Long>()

	fun observe(body: ResponseBody) = object : ResponseBody() {
		val bufferedSource by lazy(LazyThreadSafetyMode.NONE) {
			val source = body.source()
			totalBytes = body.contentLength()
			Okio.buffer(object : ForwardingSource(source) {
				override fun read(sink: Buffer, byteCount: Long): Long = run {
					val count = source.read(sink, byteCount)
					bytesRead.postValue(count)
					count
				}
			})
		}

		override fun contentLength(): Long = run {
			totalBytes
		}

		override fun contentType(): MediaType? = run {
			body.contentType()
		}

		override fun source(): BufferedSource = run {
			bufferedSource
		}
	}
}


private inline fun <reified T> Call<*>.withTag(block: (T) -> Unit) {
	request().tag(T::class.java)?.let {
		block(it)
	}
}
