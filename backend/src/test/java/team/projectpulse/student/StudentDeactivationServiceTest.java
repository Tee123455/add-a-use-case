package team.projectpulse.student;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import team.projectpulse.section.Section;
import team.projectpulse.system.UserUtils;
import team.projectpulse.system.exception.ObjectNotFoundException;
import team.projectpulse.team.Team;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.Optional;

@ExtendWith(MockitoExtension.class)
class StudentDeactivationServiceTest {
    @Mock StudentRepository studentRepository;
    @Mock UserUtils userUtils;
    @Mock EntityManager entityManager;
    @InjectMocks StudentService service;

    // Oracle: UC-STU-deactivate-student 6a/6c; BR-student-lifecycle; design T4/T6.
    @Test
    void changesOnlyEnabledAndRepeatDoesNotSave() {
        Student student = new Student("john", "John", "Smith", "john@abc.edu", "secret", true, "student");
        Team team = new Team();
        Section section = new Section();
        student.setTeam(team);
        student.setSection(section);
        when(userUtils.getUserId()).thenReturn(42);
        when(userUtils.hasRole("ROLE_instructor")).thenReturn(true);
        when(userUtils.hasRole("ROLE_admin")).thenReturn(false);
        when(studentRepository.findDeactivationTarget(1, 42, true, false)).thenReturn(Optional.of(student));
        assertThat(service.deactivateStudent(1).changed()).isTrue();
        assertThat(service.deactivateStudent(1).changed()).isFalse();
        assertThat(student.isEnabled()).isFalse();
        assertThat(student.getTeam()).isSameAs(team);
        assertThat(student.getSection()).isSameAs(section);
        assertThat(student.getFirstName()).isEqualTo("John");
        verify(studentRepository, times(1)).save(student);
        verify(entityManager, times(2)).refresh(student, LockModeType.PESSIMISTIC_WRITE);
        verify(studentRepository, never()).deleteById(any());
    }

    // Oracle: extension 3a; design T2. Caller scope is independent of selected section.
    @Test
    void refusesForeignTargetBeforeMutation() {
        when(userUtils.getUserId()).thenReturn(42);
        when(userUtils.hasRole("ROLE_admin")).thenReturn(true);
        when(userUtils.hasRole("ROLE_instructor")).thenReturn(false);
        when(studentRepository.findDeactivationTarget(1, 42, false, true)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.deactivateStudent(1)).isInstanceOf(ObjectNotFoundException.class);
        verifyNoInteractions(entityManager);
        verify(studentRepository, never()).save(any());
    }

    @Test
    void studentCannotDeactivateEvenHerself() {
        when(userUtils.getUserId()).thenReturn(1);
        assertThatThrownBy(() -> service.deactivateStudent(1)).isInstanceOf(ObjectNotFoundException.class);
        verifyNoInteractions(studentRepository, entityManager);
    }
    // Oracles: dedicated-action contract T13 and competing profile saves T14.
    @Test
    void genericEditCannotDeactivateOrSaveProfileChanges() {
        Student stored = new Student("john", "John", "Smith", "john@abc.edu", "secret", true, "student");
        Student update = new Student("forged", "Changed", "Smith", "other@abc.edu", "", false, "student");
        when(studentRepository.findById(1)).thenReturn(Optional.of(stored));
        assertThatThrownBy(() -> service.updateStudent(1, update, true))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("dedicated deactivation action");
        assertThat(stored.getFirstName()).isEqualTo("John");
        assertThat(stored.isEnabled()).isTrue();
        verify(studentRepository, never()).save(any());
    }

    @Test
    void staleProfileSaveConflictsWithoutChangingAnyFields() {
        Student stored = new Student("john", "John", "Smith", "john@abc.edu", "secret", false, "student");
        Student update = new Student("forged", "Changed", "Smith", "other@abc.edu", "", true, "student");
        when(studentRepository.findById(1)).thenReturn(Optional.of(stored));
        assertThatThrownBy(() -> service.updateStudent(1, update, true))
                .isInstanceOf(org.springframework.orm.ObjectOptimisticLockingFailureException.class);
        assertThat(stored.getFirstName()).isEqualTo("John");
        assertThat(stored.isEnabled()).isFalse();
        verify(studentRepository, never()).save(any());
    }

    // Oracle: T14's retained pre-existing intentional false-to-true path, no new reactivation flow.
    @Test
    void freshInactiveProfileRetainsExistingInstructorStatusUpdate() {
        Student stored = new Student("john", "John", "Smith", "john@abc.edu", "secret", false, "student");
        Student update = new Student("john", "John", "Smith", "john@abc.edu", "", true, "student");
        when(studentRepository.findById(1)).thenReturn(Optional.of(stored));
        when(studentRepository.save(stored)).thenReturn(stored);
        when(userUtils.hasRole("ROLE_instructor")).thenReturn(true);
        assertThat(service.updateStudent(1, update, false).isEnabled()).isTrue();
    }

}
