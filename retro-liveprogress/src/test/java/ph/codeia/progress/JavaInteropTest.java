package ph.codeia.progress;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.lifecycle.LiveData;

import org.jetbrains.annotations.NotNull;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;

import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.OkHttpClient;
import okhttp3.ResponseBody;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okio.Buffer;
import retrofit2.Converter;
import retrofit2.Retrofit;
import retrofit2.http.GET;
import retrofit2.http.Path;

import static org.hamcrest.CoreMatchers.*;
import static org.junit.Assert.*;

/*
 * This file is a part of the RetroProgress project.
 */


public class JavaInteropTest {
	interface Api {
		@GET("somewhere")
		LiveData<Progress<String>> typical();

		@GET("r/{sub}/.json")
		@ManualStart
		LiveData<Progress<String>> live(@Path("sub") String sub);
	}

	static class LetterA extends InputStream {
		static Buffer repeat(int times) throws IOException {
			return new Buffer().readFrom(new LetterA(times));
		}

		int remaining;

		LetterA(int remaining) {
			this.remaining = remaining;
		}

		@Override
		public int read() {
			if (remaining < 1) {
				return -1;
			}
			else {
				remaining -= 1;
				return 65;
			}
		}
	}

	static final Converter.Factory TO_STRING = new Converter.Factory() {
		@Override
		public Converter<ResponseBody, ?> responseBodyConverter(
			Type type,
			Annotation[] annotations,
			Retrofit retrofit
		) {
			return getRawType(type) == String.class ? ResponseBody::string : null;
		}
	};

	@Rule
	public final TestRule noLooper = new InstantTaskExecutorRule();

	private MockWebServer server;
	private Api service;

	@Before
	public void setup() {
		server = new MockWebServer();
		service = LiveProgress
			.install(new Retrofit.Builder(), new OkHttpClient())
			.addConverterFactory(TO_STRING)
			.baseUrl(server.url("/"))
			.build()
			.create(Api.class);
	}

	@After
	public void tearDown() throws IOException {
		server.shutdown();
	}

	@Test(timeout = 5000)
	public void it_works()
	throws IOException, ExecutionException, InterruptedException {
		int length = 102400;
		server.enqueue(new MockResponse().setBody(LetterA.repeat(length)));
		AtomicInteger busyCalls = new AtomicInteger(0);
		CompletableFuture<String> done = new CompletableFuture<>();
		service.typical().observeForever(new ProgressObserver<String>() {
			boolean newCalled;

			@Override
			public void when(@NotNull Progress.New start) {
				newCalled = true;
			}

			@Override
			public void when(@NotNull Progress.Busy work) {
				assert newCalled : "#when(New) was called";
				assert work.isIndeterminate || length == work.total
					: "total work is set to the length of the response if known";
				busyCalls.incrementAndGet();
			}

			@Override
			public void when(@NotNull Progress.Failed error) {
				done.completeExceptionally(error.reason);
			}

			@Override
			public void when(@NotNull Progress.Done<? extends String> result) {
				done.complete(result.value());
			}
		});
		assertEquals(length, done.get().length());
		assert busyCalls.get() > 1 : "#when(Busy) called more than once";
	}

	@Test(timeout = 5000)
	public void abort_actually_cancels_the_request_in_flight()
	throws IOException, InterruptedException {
		server.enqueue(new MockResponse().setBody(LetterA.repeat(100_000)));
		CompletableFuture<String> done = new CompletableFuture<>();
		LiveProgress.collect(service.typical(), new Progress.When<String>() {
			@Override
			public void when(@NotNull Progress.Busy work) {
				if (work.percentDone > 0.5) {
					work.abort();
				}
			}

			@Override
			public void when(@NotNull Progress.Failed error) {
				done.completeExceptionally(error.reason);
			}

			@Override
			public void when(@NotNull Progress.Done<? extends String> result) {
				done.complete(result.value());
			}
		});
		try {
			done.get();
			assert false : "unreachable";
		}
		catch (ExecutionException e) {
			assertThat(e.getCause(), instanceOf(IOException.class));
		}
	}

	@Test(timeout = 5000)
	public void can_retry_failed_requests()
	throws ExecutionException, InterruptedException {
		server.enqueue(new MockResponse().setResponseCode(404));
		server.enqueue(new MockResponse().setBody("ok!"));
		CompletableFuture<String> done = new CompletableFuture<>();
		LiveProgress.collect(service.typical(), new Progress.When<String>() {
			boolean retried;

			@Override
			public void when(@NotNull Progress.Failed error) {
				error.retry();
				retried = true;
			}

			@Override
			public void when(@NotNull Progress.Done<? extends String> result) {
				assert retried : "#when(Failed) was called";
				done.complete(result.value());
			}
		});
		assertEquals("ok!", done.get());
	}

	@Ignore("slow and noisy test hitting a live server")
	@Test(timeout = 5000)
	public void reddit_test() throws ExecutionException, InterruptedException {
		Api reddit = LiveProgress
			.install(new Retrofit.Builder(), new OkHttpClient.Builder())
			.addConverterFactory(TO_STRING)
			.baseUrl("https://old.reddit.com")
			.build()
			.create(Api.class);
		CompletableFuture<String> frontPage = new CompletableFuture<>();
		AtomicInteger counter = new AtomicInteger();
		LiveProgress.collect(reddit.live("dota2"), new Progress.When<String>() {
			@Override
			public void when(@NotNull Progress.New start) {
				start.invoke();
			}

			@Override
			public void when(@NotNull Progress.Busy work) {
				counter.incrementAndGet();
				System.out.printf("busy; %.2f%% (%d/%d)%n", work.percentDone, work.done, work.total);
			}

			@Override
			public void when(@NotNull Progress.Failed error) {
				frontPage.completeExceptionally(error.reason);
			}

			@Override
			public void when(@NotNull Progress.Done<? extends String> result) {
				frontPage.complete(result.value());
			}
		});
		System.out.println(frontPage.get());
		System.out.println(counter.get());
	}
}
