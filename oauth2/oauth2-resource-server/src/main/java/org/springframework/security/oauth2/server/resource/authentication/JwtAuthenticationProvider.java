/*
 * Copyright 2002-2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.security.oauth2.server.resource.authentication;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.BearerTokenAuthenticationToken;
import org.springframework.security.oauth2.server.resource.BearerTokenError;
import org.springframework.security.oauth2.server.resource.BearerTokenErrorCodes;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * An {@link AuthenticationProvider} implementation of the {@link Jwt}-encoded
 * <a href="https://tools.ietf.org/html/rfc6750#section-1.2" target="_blank">Bearer Token</a>s
 * for protecting OAuth 2.0 Resource Servers.
 * <p>
 * <p>
 * This {@link AuthenticationProvider} is responsible for decoding and verifying a {@link Jwt}-encoded access token,
 * returning its claims set as part of the {@see Authentication} statement.
 * <p>
 * <p>
 * Scopes are translated into {@link GrantedAuthority}s according to the following algorithm:
 *
 * 1. If there is a "scope" or "scp" attribute, then
 * 		if a {@link String}, then split by spaces and return, or
 * 		if a {@link Collection}, then simply return
 * 2. Take the resulting {@link Collection} of {@link String}s and prepend the "SCOPE_" keyword, adding
 * 		as {@link GrantedAuthority}s.
 *
 * @author Josh Cummings
 * @author Joe Grandja
 * @since 5.1
 * @see AuthenticationProvider
 * @see JwtDecoder
 */
public final class JwtAuthenticationProvider implements AuthenticationProvider {
	private final JwtDecoder jwtDecoder;

	private static final Collection<String> WELL_KNOWN_SCOPE_ATTRIBUTE_NAMES =
			Arrays.asList("scope", "scp");

	private static final String SCOPE_AUTHORITY_PREFIX = "SCOPE_";

	public JwtAuthenticationProvider(JwtDecoder jwtDecoder) {
		Assert.notNull(jwtDecoder, "jwtDecoder cannot be null");

		this.jwtDecoder = jwtDecoder;
	}

	/**
	 * Decode and validate the
	 * <a href="https://tools.ietf.org/html/rfc6750#section-1.2" target="_blank">Bearer Token</a>.
	 *
	 * @param authentication the authentication request object.
	 *
	 * @return A successful authentication
	 * @throws AuthenticationException if authentication failed for some reason
	 */
	@Override
	public Authentication authenticate(Authentication authentication) throws AuthenticationException {
		BearerTokenAuthenticationToken bearer = (BearerTokenAuthenticationToken) authentication;

		Jwt jwt;
		try {
			jwt = this.jwtDecoder.decode(bearer.getToken());
		} catch (JwtException failed) {
			OAuth2Error invalidToken;
			try {
				invalidToken = invalidToken(failed.getMessage());
			} catch ( IllegalArgumentException malformed ) {
				// some third-party library error messages are not suitable for RFC 6750's error message charset
				invalidToken = invalidToken("An error occurred while attempting to decode the Jwt: Invalid token");
			}
			throw new OAuth2AuthenticationException(invalidToken, failed);
		}

		Collection<GrantedAuthority> authorities =
				this.getScopes(jwt)
						.stream()
						.map(authority -> SCOPE_AUTHORITY_PREFIX + authority)
						.map(SimpleGrantedAuthority::new)
						.collect(Collectors.toList());

		JwtAuthenticationToken token = new JwtAuthenticationToken(jwt, authorities);

		token.setDetails(bearer.getDetails());

		return token;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public boolean supports(Class<?> authentication) {
		return BearerTokenAuthenticationToken.class.isAssignableFrom(authentication);
	}

	private static OAuth2Error invalidToken(String message) {
		return new BearerTokenError(
				BearerTokenErrorCodes.INVALID_TOKEN,
				HttpStatus.UNAUTHORIZED,
				message,
				"https://tools.ietf.org/html/rfc6750#section-3.1");
	}

	private static Collection<String> getScopes(Jwt jwt) {
		for ( String attributeName : WELL_KNOWN_SCOPE_ATTRIBUTE_NAMES ) {
			Object scopes = jwt.getClaims().get(attributeName);
			if (scopes instanceof String) {
				if (StringUtils.hasText((String) scopes)) {
					return Arrays.asList(((String) scopes).split(" "));
				} else {
					return Collections.emptyList();
				}
			} else if (scopes instanceof Collection) {
				return (Collection<String>) scopes;
			}
		}

		return Collections.emptyList();
	}
}
