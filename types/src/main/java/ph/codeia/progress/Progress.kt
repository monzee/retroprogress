package ph.codeia.progress

/*
 * This file is a part of the RetroProgress project.
 */


sealed class Progress<out T> {
	/**
	 * Must be true when [Done] or [Failed] unless [Failed.canRetry] is true.
	 */
	open val isTerminal: Boolean = false

	/**
	 * Intended for java use, but can be useful if you want to extract a `when`
	 * block into a class. You can make that class implement [Visitor] instead
	 * of copying the `when`. Often leads to less-indented code.
	 */
	abstract fun <R> select(k: Visitor<T, R>): R

	/**
	 * Transform the value in the [Done] case; receive the same instance for
	 * other cases.
	 */
	abstract fun <R> map(transform: (T) -> R): Progress<R>

	/**
	 * Call the block with the result value in the [Done] case; noop for other
	 * cases.
	 */
	open fun forSome(block: (T) -> Unit) {
	}

	class New(task: Procedure) : Progress<Nothing>(), Procedure by task {
		override fun <R> select(k: Visitor<Nothing, R>): R = run {
			k.on(this)
		}

		override fun <R> map(transform: (Nothing) -> R): Progress<R> = run {
			this
		}
	}

	class Busy(
		@JvmField val done: Long,
		@JvmField val total: Long,
		private val abort: Procedure = Pass
	) : Progress<Nothing>() {
		@JvmField val isIndeterminate = total <= 0
		@JvmField val percentDone = done / total.toFloat()

		override fun <R> select(k: Visitor<Nothing, R>): R = run {
			k.on(this)
		}

		override fun <R> map(transform: (Nothing) -> R): Progress<R> = run {
			this
		}

		fun abort() {
			abort.invoke()
		}
	}

	class Failed(
		@JvmField val reason: Throwable,
		@JvmField val retries: Int,
		@JvmField val canRetry: Boolean,
		private val retry: Procedure,
		private val abort: Procedure
	) : Progress<Nothing>() {
		constructor(reason: Throwable, retries: Int = -1) :
			this(reason, retries, false, Pass, Pass)

		override val isTerminal: Boolean = !canRetry

		override fun <R> select(k: Visitor<Nothing, R>): R = run {
			k.on(this)
		}

		override fun <R> map(transform: (Nothing) -> R): Progress<R> = run {
			this
		}

		fun abort() {
			abort.invoke()
		}

		fun retry() {
			retry.invoke()
		}
	}

	class Done<T> internal constructor() : Progress<T>() {
		private var result: T? = null

		constructor(result: T) : this() {
			this.result = result
		}

		@get:JvmName("value")
		val value: T get() = result ?: error("No content")

		override val isTerminal: Boolean = true

		override fun <R> select(k: Visitor<T, R>): R = run {
			k.on(this)
		}

		override fun <R> map(transform: (T) -> R): Progress<R> = run {
			Done(transform(value))
		}

		override fun forSome(block: (T) -> Unit) {
			block(value)
		}
	}

	interface Scope {
		val retries: Int
		suspend fun advance()
		suspend fun advance(done: Long, total: Long = 0)
		fun abort(message: String? = null)
	}

	interface Visitor<in T, R> {
		fun on(start: New): R
		fun on(work: Busy): R
		fun on(error: Failed): R
		fun on(result: Done<out T>): R
	}

	companion object {
		const val InitialWork = -1L
		val New = New(Pass)
		val Busy = Busy(0, InitialWork)
		val Done = Done<Nothing>()
		val Aborted = ProgressAborted("Aborted")

///////////////////////////////////////////////////////////////////////////
// Static factories for java use only!
///////////////////////////////////////////////////////////////////////////

		/**
		 * Fixes the unchecked warnings when assigning `Progress<Nothing>`
		 * variants to `Progress<T>`.
		 */
		@JvmStatic
		fun <T> of(progress: Progress<Nothing>): Progress<T> = run {
			progress
		}

		/**
		 * Fixes the unchecked warning when assigning a `Done<? extends T>` to
		 * `Progress<T>`.
		 */
		@JvmStatic
		fun <T> of(progress: Done<out T>): Progress<T> = run {
			progress
		}

		@[JvmStatic JvmOverloads]
		fun <T> of(task: Runnable? = null): Progress<T> = run {
			New(task.toFunction())
		}

		@[JvmStatic JvmOverloads]
		fun <T> busy(
			done: Long = 0,
			total: Long = InitialWork,
			abort: Runnable? = null
		): Progress<T> = run {
			Busy(done, total, abort.toFunction())
		}

		@JvmStatic
		fun <T> busy(abort: Runnable): Progress<T> = run {
			busy(0, InitialWork, abort)
		}

		@[JvmStatic JvmOverloads]
		fun <T> failed(
			reason: Throwable,
			retries: Int = -1,
			retry: Runnable? = null,
			abort: Runnable? = null
		): Progress<T> = run {
			Failed(
				reason,
				retries,
				retry != null,
				retry.toFunction(),
				abort.toFunction()
			)
		}

		@[JvmStatic JvmOverloads]
		fun <T> done(result: T? = null): Progress<T> = run {
			result?.let { Done(it) } ?: Done
		}

		private fun Runnable?.toFunction(): Procedure = {
			this?.run()
		}
	}

///////////////////////////////////////////////////////////////////////////
// For java use only!
///////////////////////////////////////////////////////////////////////////

	/**
	 * Extends [Visitor] to get rid of the `return null`s in `<T, Void>` impls.
	 *
	 * TODO: add a method for the default (`otherwise`) branch?
	 */
	interface When<T> : Visitor<T, Void?> {
		fun `when`(error: Failed)
		fun `when`(result: Done<out T>)

		@JvmDefault
		fun `when`(start: New) {
			start()
		}

		@JvmDefault
		fun `when`(work: Busy) {
		}

		@JvmDefault
		override fun on(start: New): Void? = run {
			`when`(start)
			null
		}

		@JvmDefault
		override fun on(work: Busy): Void? = run {
			`when`(work)
			null
		}

		@JvmDefault
		override fun on(error: Failed): Void? = run {
			`when`(error)
			null
		}

		@JvmDefault
		override fun on(result: Done<out T>): Void? = run {
			`when`(result)
			null
		}
	}
}
