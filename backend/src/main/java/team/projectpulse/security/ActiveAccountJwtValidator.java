package team.projectpulse.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import team.projectpulse.user.UserRepository;

/** Compose with the decoder's default validators; never substitute for signature/time checks. */
public class ActiveAccountJwtValidator implements OAuth2TokenValidator<Jwt> {
    private final UserRepository userRepository;

    public ActiveAccountJwtValidator(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        Object claim = token.getClaims().get("userId");
        Integer id;
        try {
            // Reject fractional, overflowing and nonnumeric identities instead of truncating them.
            if (!(claim instanceof Integer || claim instanceof Long || claim instanceof String)) {
                return denied();
            }
            id = Integer.valueOf(claim.toString());
            if (id <= 0) return denied();
        } catch (NumberFormatException ex) {
            return denied();
        }
        // Infrastructure failures propagate; they must never grant access.
        return this.userRepository.findById(id).filter(user -> user.isEnabled()).isPresent()
                ? OAuth2TokenValidatorResult.success() : denied();
    }

    private OAuth2TokenValidatorResult denied() {
        return OAuth2TokenValidatorResult.failure(
                new OAuth2Error("invalid_token", "The account is unavailable or inactive.", null));
    }
}
