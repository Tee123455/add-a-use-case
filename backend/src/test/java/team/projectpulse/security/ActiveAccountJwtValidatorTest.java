package team.projectpulse.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.oauth2.jwt.Jwt;
import team.projectpulse.student.Student;
import team.projectpulse.user.UserRepository;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ActiveAccountJwtValidatorTest {
    final UserRepository users = mock(UserRepository.class);
    final ActiveAccountJwtValidator validator = new ActiveAccountJwtValidator(users);
    Jwt token(Map<String, Object> claims) {
        return new Jwt("signed-elsewhere", Instant.now(), Instant.now().plusSeconds(600),
                Map.of("alg", "RS256"), claims);
    }
    // Oracle: FR-SEC-active-account; design T12. No cached enabled claim.
    @Test
    void observesDeactivationAndDeletionOnSubsequentValidation() {
        Student student = new Student();
        student.setEnabled(true);
        when(users.findById(4)).thenReturn(Optional.of(student));
        Jwt jwt = token(Map.of("userId", 4));
        assertThat(validator.validate(jwt).hasErrors()).isFalse();
        student.setEnabled(false);
        assertThat(validator.validate(jwt).hasErrors()).isTrue();
        when(users.findById(4)).thenReturn(Optional.empty());
        assertThat(validator.validate(jwt).hasErrors()).isTrue();
        verify(users, times(3)).findById(4);
    }
    @ParameterizedTest
    @ValueSource(strings = {"bad", "1.5", "2147483648", "-1", "0"})
    void invalidIdentityFailsClosed(String id) {
        assertThat(validator.validate(token(Map.of("userId", id))).hasErrors()).isTrue();
        verifyNoInteractions(users);
    }
    @Test
    void absentAndFractionalClaimsFailClosed() {
        assertThat(validator.validate(token(Map.of("sub", "student"))).hasErrors()).isTrue();
        assertThat(validator.validate(token(Map.of("userId", 4.5))).hasErrors()).isTrue();
        verifyNoInteractions(users);
    }
    @Test
    void databaseFailureCannotReturnSuccessfulValidation() {
        when(users.findById(4)).thenThrow(new IllegalStateException("database unavailable"));
        assertThatThrownBy(() -> validator.validate(token(Map.of("userId", 4L))))
                .isInstanceOf(IllegalStateException.class);
    }
}
