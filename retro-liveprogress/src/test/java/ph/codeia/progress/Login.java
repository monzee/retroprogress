package ph.codeia.progress;

/*
 * This file is a part of the RetroProgress project.
 */

import androidx.lifecycle.LiveData;

import org.jetbrains.annotations.NotNull;

import java.util.function.Consumer;
import java.util.regex.Pattern;

public class Login {
}

class LoginState {
	String username;
	String password;
	String usernameError;
	String passwordError;
	boolean isActive;
	Progress<String> login;

	boolean isValid() {
		return usernameError == null && passwordError == null;
	}
}

interface LoginService {
	LiveData<Progress<String>> login(String username, String password);
}

class LoginModel {
	private final LoginService service;
	private final Pattern email;
	private final LoginState state;
	private final Consumer<LoginState> update;
	private final Progress<String> init = Progress.of(this::login);

	LoginModel(
		LoginService service,
		Pattern email,
		LoginState state,
		Consumer<LoginState> update
	) {
		this.service = service;
		this.email = email;
		this.state = state;
		this.update = update;
		state.login = init;
	}

	void init() {
		state.login = init;
		update.accept(state);
	}

	void setUsername(String value) {
		state.username = value;
		if (state.isActive) {
			validate();
			update.accept(state);
		}
	}

	void setPassword(String value) {
		state.password = value;
		if (state.isActive) {
			validate();
			update.accept(state);
		}
	}

	void login() {
		validate();
		if (!state.isValid()) update.accept(state);
		else LiveProgress.collect(
			service.login(state.username.trim(), state.password.trim()),
			new Progress.When<String>() {
				@Override
				public void when(@NotNull Progress.New start) {
					start.invoke();
				}

				@Override
				public void when(@NotNull Progress.Busy work) {
					state.login = Progress.of(work);
					update.accept(state);
				}

				@Override
				public void when(@NotNull Progress.Failed error) {
					state.login = Progress.failed(
						error.reason,
						error.retries,
						error::retry,
						LoginModel.this::init
					);
					update.accept(state);
				}

				@Override
				public void when(@NotNull Progress.Done<? extends String> result) {
					state.login = Progress.of(result);
					update.accept(state);
				}
			}
		);
	}

	void validate() {
		String username = state.username;
		String password = state.password;
		state.usernameError = null;
		state.passwordError = null;
		state.isActive = true;
		if (username == null || username.trim().length() == 0) {
			state.usernameError = "This is required";
		}
		else if (!email.matcher(username.trim()).matches()) {
			state.usernameError = "Bad email address";
		}
		if (password == null || password.trim().length() == 0) {
			state.passwordError = "This is required";
		}
	}
}
