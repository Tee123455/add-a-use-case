package team.projectpulse.student;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import team.projectpulse.AbstractIntegrationTest;
import team.projectpulse.system.exception.ObjectNotFoundException;
import team.projectpulse.user.UserRepository;
import java.util.List;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class StudentDeactivationIntegrationTest extends AbstractIntegrationTest {
    @Autowired StudentService service;
    @Autowired StudentSecurityService guard;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired StudentRepository students;
    @Autowired UserRepository users;
    @Autowired EntityManager em;
    @Autowired team.projectpulse.evaluation.EvaluationService evaluations;
    @Autowired team.projectpulse.evaluation.PeerEvaluationRepository evaluationRepository;
    @Autowired PlatformTransactionManager transactions;

    void caller(String email, String... roles) {
        int id = users.findByEmail(email).orElseThrow().getId();
        Jwt jwt = Jwt.withTokenValue("service-test").header("alg", "none")
                .claim("userId", id).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt,
                java.util.Arrays.stream(roles).map(SimpleGrantedAuthority::new).toList()));
    }
    Student john() { return students.findById(users.findByEmail("j.smith@abc.edu").orElseThrow().getId()).orElseThrow(); }
    @AfterEach void clearCaller() { SecurityContextHolder.clearContext(); }

    // Oracle: extension 3a; T2. Exercise actual JPQL, independently of the route guard.
    @Test
    void scopesAssignedInstructorAndCourseOwnerWithoutDefaultSectionRestriction() {
        Student target = john();
        int bill = users.findByEmail("b.gates@abc.edu").orElseThrow().getId();
        int owner = users.findByEmail("b.wei@abc.edu").orElseThrow().getId();
        int other = users.findByEmail("t.cook@abc.edu").orElseThrow().getId();
        ((team.projectpulse.instructor.Instructor) users.findById(bill).orElseThrow()).setDefaultSection(null);
        caller("b.gates@abc.edu", "ROLE_instructor");
        assertThat(guard.canDeactivateStudent(target.getId())).isTrue();
        assertThat(guard.canDeactivateStudent(Integer.MAX_VALUE)).isFalse();
        assertThat(guard.canDeactivateStudent(null)).isFalse();
        assertThat(students.findDeactivationTarget(target.getId(), bill, true, false)).isPresent();
        assertThat(students.findDeactivationTarget(target.getId(), owner, false, true)).isPresent();
        assertThat(students.findDeactivationTarget(target.getId(), other, true, true)).isEmpty();
        assertThat(students.findDeactivationTarget(target.getId(), target.getId(), false, false)).isEmpty();
        caller("t.cook@abc.edu", "ROLE_admin", "ROLE_instructor");
        assertThat(guard.canDeactivateStudent(target.getId())).isFalse();
        assertThatThrownBy(() -> service.deactivateStudent(target.getId())).isInstanceOf(ObjectNotFoundException.class);
        assertThat(target.isEnabled()).isTrue();
    }

    // Oracle: main step 6, 6a/6c; T4/T6. Flush/clear checks persisted membership and profile.
    @Test
    void persistsOnlyStatusAndSupportsNoTeamAndRepeatedRequests() {
        caller("b.gates@abc.edu", "ROLE_instructor");
        Student target = john();
        int id = target.getId();
        var teamId = target.getTeam().getTeamId();
        var sectionId = target.getSection().getSectionId();
        var activities = jdbc.queryForList("select * from activity order by activity_id");
        var evaluations = jdbc.queryForList("select * from peer_evaluation order by peer_evaluation_id");
        var ratings = jdbc.queryForList("select * from rating order by rating_id");
        assertThat(service.deactivateStudent(id).changed()).isTrue();
        em.flush(); em.clear();
        // Oracle: 6d/BR-student-lifecycle, T7: complete stored rows, not just counts.
        assertThat(jdbc.queryForList("select * from activity order by activity_id")).isEqualTo(activities);
        assertThat(jdbc.queryForList("select * from peer_evaluation order by peer_evaluation_id")).isEqualTo(evaluations);
        assertThat(jdbc.queryForList("select * from rating order by rating_id")).isEqualTo(ratings);
        Student saved = students.findById(id).orElseThrow();
        assertThat(saved.isEnabled()).isFalse();
        assertThat(saved.getTeam().getTeamId()).isEqualTo(teamId);
        assertThat(saved.getSection().getSectionId()).isEqualTo(sectionId);
        assertThat(saved.getFirstName()).isEqualTo("John");
        assertThat(service.deactivateStudent(id).changed()).isFalse();
        saved.setTeam(null);
        saved.setEnabled(true);
        em.flush();
        assertThat(service.deactivateStudent(id).changed()).isTrue();
        assertThat(saved.getTeam()).isNull();
    }

    // Oracle: 6d, BR-evaluation-active-participants; T8/T9. Existing authors still contribute.
    @Test
    void deactivationBlocksNewEvaluationsButPreservesRecordedWeekWithoutTeam() {
        caller("b.gates@abc.edu", "ROLE_instructor");
        Student author = john();
        Student target = students.findById(users.findByEmail("e.hudson@abc.edu").orElseThrow().getId()).orElseThrow();
        int sectionId = target.getSection().getSectionId();
        service.deactivateStudent(target.getId());
        long count = evaluationRepository.count();
        var attempted = new team.projectpulse.evaluation.PeerEvaluation("2023-W32", author, target, List.of(), "new", "new");
        assertThatThrownBy(() -> evaluations.addPeerEvaluation(attempted))
                .isInstanceOf(team.projectpulse.system.exception.PeerEvaluationIllegalArgumentException.class)
                .hasMessageContaining("inactive");
        assertThat(evaluationRepository.count()).isEqualTo(count);
        service.deactivateStudent(author.getId());
        target.setTeam(null); // separate authorized membership change, not deactivation behavior
        em.flush(); em.clear();
        var report = evaluations.generateWeeklyPeerEvaluationReportForSection(sectionId, "2023-W31");
        var historical = report.getPeerEvaluationAverages().stream()
                .filter(row -> row.getStudentId().equals(target.getId())).findFirst().orElseThrow();
        // Seeded recorded totals are 47, 54 and 49: all three contribute after their authors deactivate.
        assertThat(historical.getAverageTotalScore()).isEqualTo(50.0);
        assertThat(historical.getTeamName()).isNull();
        assertThat(historical.getPublicComments()).hasSize(3);
        var emptyWeek = evaluations.generateWeeklyPeerEvaluationReportForSection(sectionId, "2099-W01");
        assertThat(emptyWeek.getPeerEvaluationAverages()).extracting(team.projectpulse.evaluation.PeerEvaluationAverage::getStudentId)
                .doesNotContain(target.getId(), author.getId());
    }

    // Oracle: Associated Information competing saves; T14. Waiter must see the committed status.
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void staleProfileWaitingBehindDeactivationCannotReactivate() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(transactions);
        int id = tx.execute(status -> john().getId());
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch saving = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> deactivate = pool.submit(() -> {
                try {
                    return tx.execute(status -> {
                        caller("b.gates@abc.edu", "ROLE_instructor");
                        boolean changed = service.deactivateStudent(id).changed();
                        locked.countDown();
                        try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("release timeout"); }
                        catch (InterruptedException ex) { throw new IllegalStateException(ex); }
                        return changed;
                    });
                } finally { SecurityContextHolder.clearContext(); }
            });
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> save = pool.submit(() -> {
                try {
                    tx.executeWithoutResult(status -> {
                        caller("b.gates@abc.edu", "ROLE_instructor");
                        Student update = new Student("changed", "Changed", "Smith", "changed@abc.edu", "", true, "student");
                        saving.countDown();
                        service.updateStudent(id, update, true);
                    });
                } finally { SecurityContextHolder.clearContext(); }
            });
            assertThat(saving.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> save.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            assertThat(deactivate.get(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> save.get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(org.springframework.orm.ObjectOptimisticLockingFailureException.class);
            tx.executeWithoutResult(status -> {
                Student persisted = students.findById(id).orElseThrow();
                assertThat(persisted.isEnabled()).isFalse();
                assertThat(persisted.getFirstName()).isEqualTo("John");
            });
        } finally {
            release.countDown();
            pool.shutdown();
            if (!pool.awaitTermination(20, TimeUnit.SECONDS)) pool.shutdownNow();
            tx.executeWithoutResult(status -> students.findById(id).orElseThrow().setEnabled(true));
        }
    }
    // Oracle: extension 6a, T4. Simultaneous requests have exactly one changed outcome.
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void simultaneousDeactivationsProduceOneChangeAndOneNoOp() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(transactions);
        int id = tx.execute(status -> john().getId());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Boolean> deactivate = () -> {
            ready.countDown();
            if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
            try {
                return tx.execute(status -> {
                    caller("b.gates@abc.edu", "ROLE_instructor");
                    return service.deactivateStudent(id).changed();
                });
            } finally { SecurityContextHolder.clearContext(); }
        };
        try {
            Future<Boolean> first = pool.submit(deactivate);
            Future<Boolean> second = pool.submit(deactivate);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
            tx.executeWithoutResult(status -> assertThat(students.findById(id).orElseThrow().isEnabled()).isFalse());
        } finally {
            start.countDown();
            pool.shutdown();
            if (!pool.awaitTermination(20, TimeUnit.SECONDS)) pool.shutdownNow();
            tx.executeWithoutResult(status -> students.findById(id).orElseThrow().setEnabled(true));
        }
    }

}
