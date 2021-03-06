package org.baeldung.config;

import java.io.IOException;
import java.net.URI;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.client.filter.state.DefaultStateKeyGenerator;
import org.springframework.security.oauth2.client.filter.state.StateKeyGenerator;
import org.springframework.security.oauth2.client.resource.OAuth2AccessDeniedException;
import org.springframework.security.oauth2.client.resource.OAuth2ProtectedResourceDetails;
import org.springframework.security.oauth2.client.resource.UserApprovalRequiredException;
import org.springframework.security.oauth2.client.resource.UserRedirectRequiredException;
import org.springframework.security.oauth2.client.token.AccessTokenRequest;
import org.springframework.security.oauth2.client.token.DefaultRequestEnhancer;
import org.springframework.security.oauth2.client.token.RequestEnhancer;
import org.springframework.security.oauth2.client.token.grant.code.AuthorizationCodeAccessTokenProvider;
import org.springframework.security.oauth2.client.token.grant.code.AuthorizationCodeResourceDetails;
import org.springframework.security.oauth2.common.OAuth2AccessToken;
import org.springframework.security.oauth2.common.exceptions.InvalidRequestException;
import org.springframework.security.oauth2.common.util.OAuth2Utils;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResponseExtractor;

import com.google.common.base.Joiner;

public class MyAuthorizationCodeAccessTokenProvider extends AuthorizationCodeAccessTokenProvider {

    private StateKeyGenerator stateKeyGenerator = new DefaultStateKeyGenerator();

    private String scopePrefix = OAuth2Utils.SCOPE_PREFIX;

    private RequestEnhancer authorizationRequestEnhancer = new DefaultRequestEnhancer();

    @Override
    public String obtainAuthorizationCode(OAuth2ProtectedResourceDetails details, AccessTokenRequest request) throws UserRedirectRequiredException, UserApprovalRequiredException, AccessDeniedException, OAuth2AccessDeniedException {
        AuthorizationCodeResourceDetails resource = (AuthorizationCodeResourceDetails) details;

        HttpHeaders headers = getHeadersForAuthorizationRequest(request);
        MultiValueMap<String, String> form = new LinkedMultiValueMap<String, String>();
        if (request.containsKey(OAuth2Utils.USER_OAUTH_APPROVAL)) {
            form.set(OAuth2Utils.USER_OAUTH_APPROVAL, request.getFirst(OAuth2Utils.USER_OAUTH_APPROVAL));
            for (String scope : details.getScope()) {
                form.set(scopePrefix + scope, request.getFirst(OAuth2Utils.USER_OAUTH_APPROVAL));
            }
        } else {
            form.putAll(getParametersForAuthorizeRequest(resource, request));
        }
        form.set("duration", "permanent");
        authorizationRequestEnhancer.enhance(request, resource, form, headers);
        final AccessTokenRequest copy = request;

        final ResponseExtractor<ResponseEntity<Void>> delegate = getAuthorizationResponseExtractor();
        ResponseExtractor<ResponseEntity<Void>> extractor = new ResponseExtractor<ResponseEntity<Void>>() {
            @Override
            public ResponseEntity<Void> extractData(ClientHttpResponse response) throws IOException {
                if (response.getHeaders().containsKey("Set-Cookie")) {
                    copy.setCookie(response.getHeaders().getFirst("Set-Cookie"));
                }
                return delegate.extractData(response);
            }
        };

        ResponseEntity<Void> response = getRestTemplate().execute(resource.getUserAuthorizationUri(), HttpMethod.POST, getRequestCallback(resource, form, headers), extractor, form.toSingleValueMap());

        if (response.getStatusCode() == HttpStatus.OK) {
            throw getUserApprovalSignal(resource, request);
        }

        URI location = response.getHeaders().getLocation();
        String query = location.getQuery();
        Map<String, String> map = OAuth2Utils.extractMap(query);
        if (map.containsKey("state")) {
            request.setStateKey(map.get("state"));
            if (request.getPreservedState() == null) {
                String redirectUri = resource.getRedirectUri(request);
                if (redirectUri != null) {
                    request.setPreservedState(redirectUri);
                } else {
                    request.setPreservedState(new Object());
                }
            }
        }

        String code = map.get("code");
        if (code == null) {
            throw new UserRedirectRequiredException(location.toString(), form.toSingleValueMap());
        }
        request.set("code", code);
        return code;
    }

    @Override
    public OAuth2AccessToken obtainAccessToken(OAuth2ProtectedResourceDetails details, AccessTokenRequest request) throws UserRedirectRequiredException, UserApprovalRequiredException, AccessDeniedException, OAuth2AccessDeniedException {
        AuthorizationCodeResourceDetails resource = (AuthorizationCodeResourceDetails) details;

        if (request.getAuthorizationCode() == null) {
            if (request.getStateKey() == null) {
                throw getRedirectForAuthorization(resource, request);
            }
            obtainAuthorizationCode(resource, request);
        }
        return retrieveToken(request, resource, getParametersForTokenRequest(resource, request), getHeadersForTokenRequest(request));
    }

    private HttpHeaders getHeadersForTokenRequest(AccessTokenRequest request) {
        HttpHeaders headers = new HttpHeaders();
        return headers;
    }

    private HttpHeaders getHeadersForAuthorizationRequest(AccessTokenRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.putAll(request.getHeaders());
        if (request.getCookie() != null) {
            headers.set("Cookie", request.getCookie());
        }
        return headers;
    }

    private MultiValueMap<String, String> getParametersForTokenRequest(AuthorizationCodeResourceDetails resource, AccessTokenRequest request) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<String, String>();
        form.set("grant_type", "authorization_code");
        form.set("code", request.getAuthorizationCode());

        Object preservedState = request.getPreservedState();
        if (request.getStateKey() != null) {
            if (preservedState == null) {
                throw new InvalidRequestException("Possible CSRF detected - state parameter was present but no state could be found");
            }
        }

        String redirectUri = null;
        if (preservedState instanceof String) {
            redirectUri = String.valueOf(preservedState);
        } else {
            redirectUri = resource.getRedirectUri(request);
        }

        if (redirectUri != null && !"NONE".equals(redirectUri)) {
            form.set("redirect_uri", redirectUri);
        }

        return form;
    }

    private MultiValueMap<String, String> getParametersForAuthorizeRequest(AuthorizationCodeResourceDetails resource, AccessTokenRequest request) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<String, String>();
        form.set("response_type", "code");
        form.set("client_id", resource.getClientId());

        if (request.get("scope") != null) {
            form.set("scope", request.getFirst("scope"));
        } else {
            form.set("scope", Joiner.on(',').join(resource.getScope()));
        }

        String redirectUri = resource.getPreEstablishedRedirectUri();

        Object preservedState = request.getPreservedState();
        if (redirectUri == null && preservedState != null) {
            redirectUri = String.valueOf(preservedState);
        } else {
            redirectUri = request.getCurrentUri();
        }

        String stateKey = request.getStateKey();
        if (stateKey != null) {
            form.set("state", stateKey);
            if (preservedState == null) {
                throw new InvalidRequestException("Possible CSRF detected - state parameter was present but no state could be found");
            }
        }

        if (redirectUri != null) {
            form.set("redirect_uri", redirectUri);
        }

        return form;
    }

    private UserRedirectRequiredException getRedirectForAuthorization(AuthorizationCodeResourceDetails resource, AccessTokenRequest request) {
        TreeMap<String, String> requestParameters = new TreeMap<String, String>();
        requestParameters.put("response_type", "code");
        requestParameters.put("client_id", resource.getClientId());
        requestParameters.put("duration", "permanent");

        String redirectUri = resource.getRedirectUri(request);
        if (redirectUri != null) {
            requestParameters.put("redirect_uri", redirectUri);
        }

        if (resource.isScoped()) {

            StringBuilder builder = new StringBuilder();
            List<String> scope = resource.getScope();

            if (scope != null) {
                Iterator<String> scopeIt = scope.iterator();
                while (scopeIt.hasNext()) {
                    builder.append(scopeIt.next());
                    if (scopeIt.hasNext()) {
                        builder.append(',');
                    }
                }
            }

            requestParameters.put("scope", builder.toString());
        }

        UserRedirectRequiredException redirectException = new UserRedirectRequiredException(resource.getUserAuthorizationUri(), requestParameters);

        String stateKey = stateKeyGenerator.generateKey(resource);
        redirectException.setStateKey(stateKey);
        request.setStateKey(stateKey);
        redirectException.setStateToPreserve(redirectUri);
        request.setPreservedState(redirectUri);

        return redirectException;
    }

}
