package team.projectpulse.security;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import team.projectpulse.AbstractIntegrationTest;
import team.projectpulse.student.StudentRepository;
import team.projectpulse.user.UserRepository;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class StudentDeactivationSecurityIntegrationTest extends AbstractIntegrationTest {
    static final String BASE = "/api/v1";
    @Autowired MockMvc mvc;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean UserRepository users;
    @Autowired StudentRepository students;
    @Autowired JwtEncoder encoder;
    @Autowired PlatformTransactionManager transactions;

    String login(String email) throws Exception {
        var result = mvc.perform(post(BASE + "/users/login").with(httpBasic(email, "123456")))
                .andExpect(status().isOk()).andReturn();
        return "Bearer " + new JSONObject(result.getResponse().getContentAsString()).getJSONObject("data").getString("token");
    }
    int id(String email) { return users.findByEmail(email).orElseThrow().getId(); }
    String signed(Object userId, String authorities, Instant expires, Instant notBefore) {
        var claims = JwtClaimsSet.builder().subject("security-test").issuedAt(Instant.now().minusSeconds(1000))
                .expiresAt(expires).notBefore(notBefore).claim("authorities", authorities);
        if (userId != null) claims.claim("userId", userId);
        return "Bearer " + encoder.encode(JwtEncoderParameters.from(claims.build())).getTokenValue();
    }

    // Oracle: main step 6/6a; T1/T4. Both authorized actor types use the actual filter chain and persistence.
    @ParameterizedTest
    @ValueSource(strings = {"b.gates@abc.edu", "b.wei@abc.edu"})
    void authorizedActorsDeactivateAndRepeatWithExactMessage(String email) throws Exception {
        String token = login(email);
        int target = id("j.smith@abc.edu");
        mvc.perform(put(BASE + "/students/" + target + "/deactivate").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.flag").value(true))
                .andExpect(jsonPath("$.message").value("Student deactivated successfully"))
                .andExpect(jsonPath("$.data.enabled").value(false));
        assertThat(students.findById(target).orElseThrow().isEnabled()).isFalse();
        mvc.perform(put(BASE + "/students/" + target + "/deactivate").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("Student is already deactivated"));
        mvc.perform(put(BASE + "/students/" + target + "/deactivate").header(HttpHeaders.AUTHORIZATION, login("t.cook@abc.edu")))
                .andExpect(status().isForbidden());
    }

    // Oracle: extension 3a; T2. Unrelated instructor/admin, student self/other and unresolved IDs fail closed.
    @Test
    void unauthorizedTargetsAndMalformedIdsNeverMutate() throws Exception {
        int target = id("j.smith@abc.edu");
        for (String email : new String[]{"t.cook@abc.edu", "j.smith@abc.edu", "e.hudson@abc.edu"}) {
            mvc.perform(put(BASE + "/students/" + target + "/deactivate").header(HttpHeaders.AUTHORIZATION, login(email)))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(403));
        }
        String unrelatedInstructor = signed(id("t.cook@abc.edu"), "ROLE_instructor", Instant.now().plusSeconds(600), Instant.now().minusSeconds(10));
        mvc.perform(put(BASE + "/students/" + target + "/deactivate").header(HttpHeaders.AUTHORIZATION, unrelatedInstructor))
                .andExpect(status().isForbidden());
        String token = login("b.gates@abc.edu");
        for (String invalid : new String[]{"not-a-number", "2147483648", "999999"}) {
            mvc.perform(put(BASE + "/students/" + invalid + "/deactivate").header(HttpHeaders.AUTHORIZATION, token))
                    .andExpect(status().isForbidden());
        }
        assertThat(students.findById(target).orElseThrow().isEnabled()).isTrue();
    }

    // Oracle: extension 6b / FR-SEC-active-account; T5. Each request starts after a real commit.
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void committedDeactivationRejectsOldTokenReadWriteAndFreshBasicLogin() throws Exception {
        int target = id("j.smith@abc.edu");
        String oldToken = login("j.smith@abc.edu");
        TransactionTemplate tx = new TransactionTemplate(transactions);
        try {
            mvc.perform(put(BASE + "/students/" + target + "/deactivate")
                    .header(HttpHeaders.AUTHORIZATION, login("b.gates@abc.edu"))).andExpect(status().isOk());
            mvc.perform(get(BASE + "/students/" + target).header(HttpHeaders.AUTHORIZATION, oldToken))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(401));
            mvc.perform(put(BASE + "/students/" + target).param("expectedEnabled", "true")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"username\":\"j.smith@abc.edu\",\"firstName\":\"Changed\",\"lastName\":\"Smith\",\"email\":\"j.smith@abc.edu\",\"enabled\":true}")
                    .header(HttpHeaders.AUTHORIZATION, oldToken)).andExpect(status().isUnauthorized());
            mvc.perform(post(BASE + "/users/login").with(httpBasic("j.smith@abc.edu", "123456")))
                    .andExpect(status().isUnauthorized());
            tx.executeWithoutResult(status -> {
                var saved = students.findById(target).orElseThrow();
                assertThat(saved.isEnabled()).isFalse();
                assertThat(saved.getFirstName()).isEqualTo("John");
            });
        } finally {
            tx.executeWithoutResult(status -> students.findById(target).orElseThrow().setEnabled(true));
        }
    }

    // Oracle: FR-SEC-active-account; T12. Instructor status is enforced too; active roles retain access.
    @Test
    void activeRolesWorkAndInactiveInstructorCannotLoginOrReuseToken() throws Exception {
        for (String email : new String[]{"j.smith@abc.edu", "b.gates@abc.edu", "b.wei@abc.edu"}) {
            mvc.perform(get(BASE + (email.equals("j.smith@abc.edu") ? "/students/" : "/instructors/") + id(email))
                    .header(HttpHeaders.AUTHORIZATION, login(email))).andExpect(status().isOk());
        }
        String token = login("b.gates@abc.edu");
        users.findById(id("b.gates@abc.edu")).orElseThrow().setEnabled(false);
        users.flush();
        mvc.perform(get(BASE + "/instructors/" + id("b.gates@abc.edu")).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(BASE + "/users/login").with(httpBasic("b.gates@abc.edu", "123456")))
                .andExpect(status().isUnauthorized());
    }

    // Oracle: T12. Adding a validator must retain time/signature validation and fail closed on bad identities.
    @Test
    void rejectsExpiredFutureMalformedAndUnresolvableIdentityTokens() throws Exception {
        Instant now = Instant.now();
        int target = id("j.smith@abc.edu");
        for (String token : new String[]{
                signed(target, "ROLE_student", now.minusSeconds(120), now.minusSeconds(1000)),
                signed(target, "ROLE_student", now.plusSeconds(1200), now.plusSeconds(600)),
                "Bearer malformed",
                signed(null, "ROLE_student", now.plusSeconds(600), now.minusSeconds(10)),
                signed("bad", "ROLE_student", now.plusSeconds(600), now.minusSeconds(10)),
                signed(999999, "ROLE_student", now.plusSeconds(600), now.minusSeconds(10))}) {
            mvc.perform(get(BASE + "/students/" + target).header(HttpHeaders.AUTHORIZATION, token))
                    .andExpect(status().isUnauthorized());
        }
    }
    @Test
    void deletedAccountAndTamperedSignatureAreRejected() throws Exception {
        var temporary = users.saveAndFlush(new team.projectpulse.instructor.Instructor(
                "deleted@abc.edu", "Deleted", "User", "deleted@abc.edu", "encoded", true, "instructor"));
        String token = signed(temporary.getId(), "ROLE_instructor", Instant.now().plusSeconds(600), Instant.now().minusSeconds(10));
        users.delete(temporary);
        users.flush();
        mvc.perform(get(BASE + "/students/4").header(HttpHeaders.AUTHORIZATION, token)).andExpect(status().isUnauthorized());
        String valid = login("j.smith@abc.edu");
        int signature = valid.lastIndexOf('.') + 1;
        String corrupt = valid.substring(0, signature) + (valid.charAt(signature) == 'A' ? 'B' : 'A') + valid.substring(signature + 1);
        mvc.perform(get(BASE + "/students/4").header(HttpHeaders.AUTHORIZATION, corrupt)).andExpect(status().isUnauthorized());
    }

    // Oracle: T12. An infrastructure failure may propagate, but protected work must never execute.
    @Test
    void accountLookupFailureDoesNotExecuteProtectedMutation() throws Exception {
        int target = id("j.smith@abc.edu");
        String token = login("j.smith@abc.edu");
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("database unavailable"))
                .when(users).findById(target);
        try {
            Throwable error = catchThrowable(() -> mvc.perform(put(BASE + "/students/" + target)
                    .param("expectedEnabled", "true").header(HttpHeaders.AUTHORIZATION, token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"username\":\"j.smith@abc.edu\",\"firstName\":\"Changed\",\"lastName\":\"Smith\",\"email\":\"j.smith@abc.edu\",\"enabled\":true}"))
                    .andExpect(result -> assertThat(result.getResponse().getStatus()).isGreaterThanOrEqualTo(400)));
            if (error != null) assertThat(error).hasStackTraceContaining("database unavailable");
        } finally {
            org.mockito.Mockito.reset(users);
        }
        assertThat(students.findById(target).orElseThrow().getFirstName()).isEqualTo("John");
    }

    // Oracle: T8, BR-evaluation-active-participants: deactivation does not introduce an edit prohibition.
    @Test
    void existingEvaluationAboutInactiveTargetRemainsEditableInOpenWindow() throws Exception {
        String authorToken = login("j.smith@abc.edu");
        int target = id("e.hudson@abc.edu");
        String json = mvc.perform(get(BASE + "/evaluations/evaluators/" + id("j.smith@abc.edu") + "/week/2023-W32")
                        .header(HttpHeaders.AUTHORIZATION, authorToken)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        org.json.JSONArray rows = new JSONObject(json).getJSONArray("data");
        JSONObject evaluation = null;
        for (int index = 0; index < rows.length(); index++) {
            if (rows.getJSONObject(index).getInt("evaluateeId") == target) evaluation = rows.getJSONObject(index);
        }
        assertThat(evaluation).isNotNull();
        mvc.perform(put(BASE + "/students/" + target + "/deactivate")
                .header(HttpHeaders.AUTHORIZATION, login("b.gates@abc.edu"))).andExpect(status().isOk());
        evaluation.put("publicComment", "Edited while the original week remains open");
        mvc.perform(put(BASE + "/evaluations/" + evaluation.getInt("evaluationId"))
                        .header(HttpHeaders.AUTHORIZATION, authorToken).contentType(MediaType.APPLICATION_JSON)
                        .content(evaluation.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.publicComment").value("Edited while the original week remains open"));
    }

    // Oracle: T14. A fresh inactive snapshot retains the existing generic status-update behavior.
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void freshInactiveProfileCanUseRetainedIntentionalStatusUpdate() throws Exception {
        int target = id("j.smith@abc.edu");
        String instructor = login("b.gates@abc.edu");
        try {
            mvc.perform(put(BASE + "/students/" + target + "/deactivate").header(HttpHeaders.AUTHORIZATION, instructor))
                    .andExpect(status().isOk());
            String json = mvc.perform(get(BASE + "/students/" + target).header(HttpHeaders.AUTHORIZATION, instructor))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            JSONObject profile = new JSONObject(json).getJSONObject("data");
            profile.put("enabled", true);
            mvc.perform(put(BASE + "/students/" + target).param("expectedEnabled", "false")
                            .header(HttpHeaders.AUTHORIZATION, instructor).contentType(MediaType.APPLICATION_JSON).content(profile.toString()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.enabled").value(true));
        } finally {
            new TransactionTemplate(transactions).executeWithoutResult(status -> students.findById(target).orElseThrow().setEnabled(true));
        }
    }

    // Oracle: main step 2 + 3a; owning course admins need not be assigned to the target section.
    @Test
    void courseOwnerCanViewDetailsAndDeactivateWithoutSectionAssignment() throws Exception {
        int target = id("j.smith@abc.edu");
        var owner = (team.projectpulse.instructor.Instructor) users.findByEmail("b.wei@abc.edu").orElseThrow();
        students.findById(target).orElseThrow().getSection().removeInstructor(owner);
        users.flush();
        String token = login("b.wei@abc.edu");
        mvc.perform(get(BASE + "/students/" + target).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(target));
        mvc.perform(put(BASE + "/students/" + target + "/deactivate").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.enabled").value(false));
    }

}
