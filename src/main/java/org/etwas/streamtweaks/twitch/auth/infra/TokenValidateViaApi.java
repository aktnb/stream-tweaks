package org.etwas.streamtweaks.twitch.auth.infra;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.List;
import org.etwas.streamtweaks.twitch.auth.AccessToken;
import org.etwas.streamtweaks.twitch.auth.TokenValidationResult;
import org.etwas.streamtweaks.twitch.auth.TokenValidator;
import org.etwas.streamtweaks.twitch.core.Login;
import org.etwas.streamtweaks.twitch.core.UserId;

// https://dev.twitch.tv/docs/authentication/validate-tokens/
public class TokenValidateViaApi implements TokenValidator {
    // https://dev.twitch.tv/docs/authentication/validate-tokens/#how-to-validate-a-token
    private final String VALIDATE_URL = "https://id.twitch.tv/oauth2/validate";
    // validateToken()はclient.send()で同期ブロックする。タイムアウトが無いと、id.twitch.tvが
    // 応答不能になった場合にAuthenticationOrchestrator.startAuthentication()のFutureが
    // 永久にハングする（callbackServer.onCallback()に付けたorTimeoutはコールバック待ちの区間だけを
    // 保護しており、コールバック受信後のこのトークン検証呼び出し自体はその対象外のため）。
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private final HttpClient client =
            HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build();
    private final Gson gson = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .create();

    @Override
    public TokenValidationResult validateToken(AccessToken token, List<String> requiredScopes) {
        var request = HttpRequest.newBuilder()
                .uri(java.net.URI.create(VALIDATE_URL))
                .header("Authorization", "OAuth " + token.value())
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();

        try {
            var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                return TokenValidationResult.INVALID;
            }

            ValidateResponse validateResponse = gson.fromJson(response.body(), ValidateResponse.class);

            if (!validateResponse.scopes.containsAll(requiredScopes)) {
                return TokenValidationResult.INVALID;
            }

            return TokenValidationResult.valid(new UserId(validateResponse.userId), new Login(validateResponse.login));
        } catch (Exception e) {
            return TokenValidationResult.INVALID;
        }
    }

    // https://dev.twitch.tv/docs/authentication/validate-tokens/#how-to-validate-a-token
    class ValidateResponse {
        String clientId;
        String login;
        List<String> scopes;
        String userId;
        int expiresIn;
    }
}
