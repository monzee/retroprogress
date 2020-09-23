package ph.codeia.progress

import java.lang.IllegalStateException
import java.util.concurrent.CancellationException

/*
 * This file is a part of the RetroProgress project.
 */


@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER)
annotation class ManualStart

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER)
annotation class NoRetry

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER)
annotation class AlwaysCold


internal typealias Procedure = () -> Unit

val Pass: Procedure = {}


class ProgressAborted(override val message: String) : CancellationException(message)

class ServiceException(
	override val message: String,
	@JvmField val code: Int,
	@JvmField val body: String? = null
) : IllegalStateException(message) {

	@JvmField
	val type: HttpError = when (code) {
		400, 405 -> HttpError.Rejected
		401, 403 -> HttpError.NotAllowed
		404, 410 -> HttpError.NotFound
		408, 503 -> HttpError.Unavailable
		else -> HttpError.Unspecified
	}
}


enum class HttpError {
	Rejected, NotAllowed, NotFound, Unavailable, Unspecified
}
