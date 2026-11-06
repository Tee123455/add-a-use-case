package team.projectpulse.student;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface StudentRepository extends JpaRepository<Student, Integer>, JpaSpecificationExecutor<Student> {

    // The caller's default section is deliberately not the authorization boundary.
    @Query("""
            select s from Student s where s.id = :studentId and
            ((:instructor = true and exists
              (select i.id from Section sec join sec.instructors i
               where sec = s.section and i.id = :callerId))
             or (:admin = true and s.section.course.courseAdmin.id = :callerId))
            """)
    Optional<Student> findDeactivationTarget(Integer studentId, Integer callerId,
                                              boolean instructor, boolean admin);

    // Find all students in a section
    List<Student> findBySectionSectionId(Integer sectionId);

    List<Student> findByTeamTeamId(Integer teamId);
}
